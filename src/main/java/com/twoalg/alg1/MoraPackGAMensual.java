// MoraPackGAMensual.java
// GA con decodificador (órdenes sin origen): elige hub (entre EXPORT_HUBS) por subruta.
// Reglas: conexión mínima, due 2/3 días, ventana de recojo 2h, escalas, split entre subrutas.
// Control de capacidad de vuelo y de almacén por intervalos (difference array por slots).
// Mejoras:
// - Carga de pedidos mensuales dd-hh-mm-dest-###-IdClien (UTC).
// - Heurística: prioriza vuelos directos y “dos saltos” + evita revisitas.
// - Almacén destino: mantener ocupación hasta 2h después de COMPLETAR el pedido.

import java.io.*;
import java.nio.file.*;
import java.nio.charset.*;
import java.util.*;
import java.util.stream.*;

public class MoraPackGAMensual {

    // ===================== Parámetros de negocio =====================
    static final int MIN_TURN_MIN = 30;               // conexión mínima
    static final int DUE_SAME_MIN = 2 * 24 * 60;      // 2 días
    static final int DUE_CROSS_MIN = 3 * 24 * 60;     // 3 días
    static final int PICKUP_WINDOW_MIN = 120;         // 2 horas de recojo

    // ===================== Parámetros GA =============================
    static final int POP_SIZE = 40;
    static final int MAX_GEN  = 100;
    static final double PCROSS = 0.8;
    static final double PMUT   = 0.05;
    static final int ELITE_K   = 4;
    static final int TOURN_K   = 3;
    static final int NO_IMPROV_LIMIT = 40;

    // Objetivo
    static final double LAMBDA_ONTIME = 1.0;
    static final double LAMBDA_LATE   = 1.5;
    static final double LAMBDA_CAPVIO = 4.0;
    static final double LAMBDA_SLACK  = 0.001;

    // Exportadores
    static final Set<String> EXPORT_HUBS = Set.of(
        "SPIM",  // Lima
        "EBCI",  // Bruselas
        "UBBB"   // Bakú
    );

    // Stock/Almacén: granularidad de slots para difference arrays
    static final int SLOT_MIN = 60; // 60 minutos

    // Recorte opcional de branching
    static final int TOPK_CANDIDATES = 1_000_000;

    // ===================== Modelos de datos ==========================
    enum Continent { SOUTH_AMERICA, EUROPE, ASIA, OTHER }

    static class Airport {
        String code;
        int gmt;            // horas offset UTC (puede ser negativo)
        int storageCap;     // capacidad de almacén
        double lat, lon;    // grados decimales
        Continent continent;
        boolean isExporter;

        public Airport(String code, int gmt, int storageCap, double lat, double lon) {
            this.code = code;
            this.gmt = gmt;
            this.storageCap = storageCap;
            this.lat = lat;
            this.lon = lon;
            this.continent = inferContinent(code);
            this.isExporter = EXPORT_HUBS.contains(code);
        }
    }

    static Continent inferContinent(String icao) {
        if (icao == null || icao.isEmpty()) return Continent.OTHER;
        char c = icao.charAt(0);
        if (c == 'S') return Continent.SOUTH_AMERICA;
        if (c == 'E' || c == 'L' || c == 'U') return Continent.EUROPE;
        if (c == 'O' || c == 'V') return Continent.ASIA;
        return Continent.OTHER;
    }

    static class Flight {
        String orig, dest;     // ICAO
        int depLocalMin;       // minutos desde 00:00 (hora local en ORIG)
        int arrLocalMin;       // minutos desde 00:00 (hora local en DEST)
        int capacity;
        public Flight(String o, String d, int dep, int arr, int cap) {
            orig=o; dest=d; depLocalMin=dep; arrLocalMin=arr; capacity=cap;
        }
    }

    // Órdenes SIN origen (el algoritmo elige el hub)
    static class Order {
        String dest;
        int qty;
        int releaseMinUTC;
        // NEW: datos del nuevo formato
        int dayOfMonth;          // 1..31
        String clientId;         // 7 dígitos zero-padded
        public Order(String dest, int qty, int releaseMinUTC) {
            this.dest = dest; this.qty = qty; this.releaseMinUTC = releaseMinUTC;
        }
        public Order(String dest, int qty, int releaseMinUTC, int dayOfMonth, String clientId) {
            this.dest = dest; this.qty = qty; this.releaseMinUTC = releaseMinUTC;
            this.dayOfMonth = dayOfMonth; this.clientId = clientId;
        }
    }

    // Uso concreto de un vuelo plantilla en un día específico
    static class FlightUse {
        Flight flight;
        int dayIndex;
        int depUTC, arrUTC;
        int qtyAssigned;
        public FlightUse(Flight f, int d, int depUTC, int arrUTC, int qty) {
            this.flight=f; this.dayIndex=d; this.depUTC=depUTC; this.arrUTC=arrUTC; this.qtyAssigned=qty;
        }
    }

    // Una subruta mueve un BLOQUE fijo de cantidad desde un hub hasta el destino
    static class SubRoute {
        String originHub;
        List<FlightUse> legs = new ArrayList<>(8);
        int qty;
        int arrivalUTC;
    }

    static class Solution {
        Map<Order, List<SubRoute>> routes = new HashMap<>();
        Map<String,Integer> capUsed = new HashMap<>(); // (flight,day) -> used
        int servedOnTime, servedLate, capViol, avgSlack;
        double objective;
    }

    static class World {
        Map<String,Airport> airports = new HashMap<>();
        List<Flight> flights = new ArrayList<>();
        Map<String,List<Flight>> outByAirport = new HashMap<>();
        List<String> hubList = new ArrayList<>();
    }

    // ===================== Tiempo / Conversión =======================
    static int parseHHMM(String hhmm) {
        String[] p = hhmm.trim().split(":");
        return Integer.parseInt(p[0])*60 + Integer.parseInt(p[1]);
    }
    static int toUTCFromLocal(Airport a, int localMin) { return localMin - a.gmt*60; }
    static String mmToHHMM(int m) {
        int x = m % 1440; if (x<0) x+=1440;
        return String.format("%02d:%02d", x/60, x%60);
    }
    static int slotOf(int utcMin, int numSlots){
        int s = utcMin / SLOT_MIN;
        if (s<0) return 0;
        if (s>=numSlots) return numSlots-1;
        return s;
    }

    // ===================== Lectura robusta de archivos ===============
    static List<String> readAllLinesAuto(Path p) throws IOException {
        List<Charset> tries = List.of(StandardCharsets.UTF_8, StandardCharsets.ISO_8859_1, Charset.forName("windows-1252"));
        for (Charset cs: tries) {
            try (BufferedReader br = Files.newBufferedReader(p, cs)) {
                List<String> out = new ArrayList<>();
                String line;
                while ((line = br.readLine()) != null) {
                    if (!out.isEmpty() || !line.isEmpty()) {
                        if (!line.isEmpty() && line.charAt(0) == '\uFEFF') line = line.substring(1);
                    }
                    out.add(line);
                }
                return out;
            } catch (MalformedInputException ignore) {}
        }
        throw new IOException("No se pudo decodificar el archivo con UTF-8/ISO-8859-1/Windows-1252");
    }

    // airports: CODE, GMT (-12..14), CAP (100..10000), opcional lat lon
    static void loadAirports(Path file, World W) throws IOException {
        for (String s: readAllLinesAuto(file)) {
            String line = s.trim();
            if (line.isEmpty()) continue;
            String[] tok = line.split("\\s+|,");
            String code = null; Integer gmt=null, cap=null; Double lat=null, lon=null;
            for (String t: tok) {
                if (code==null && t.matches("[A-Z]{4}")) { code = t; continue; }
                if (gmt==null && t.matches("[+-]?\\d{1,2}")) {
                    int v = Integer.parseInt(t); if (v>=-12 && v<=14) { gmt=v; continue; }
                }
                if (cap==null && t.matches("\\d{2,5}")) {
                    int v = Integer.parseInt(t); if (v>=100 && v<=10000) { cap=v; continue; }
                }
                if (lat==null && t.matches("[+-]?\\d+(\\.\\d+)?")) { lat = Double.parseDouble(t); continue; }
                if (lon==null && t.matches("[+-]?\\d+(\\.\\d+)?")) { lon = Double.parseDouble(t); continue; }
            }
            if (code!=null && gmt!=null && cap!=null) {
                if (lat==null) lat = 0.0; if (lon==null) lon = 0.0;
                W.airports.put(code, new Airport(code, gmt, cap, lat, lon));
            }
        }
        for (String h: EXPORT_HUBS) if (W.airports.containsKey(h)) W.hubList.add(h);
    }

    // flights: ORIG-DEST-HH:MM-HH:MM-CAP
    static void loadFlights(Path file, World W) throws IOException {
        for (String s: readAllLinesAuto(file)) {
            String line = s.trim();
            if (line.isEmpty()) continue;
            String[] p = line.split("-");
            if (p.length < 5) continue;
            String orig = p[0].trim();
            String dest = p[1].trim();
            int dep = parseHHMM(p[2].trim());
            int arr = parseHHMM(p[3].trim());
            int cap = Integer.parseInt(p[4].trim());
            Flight f = new Flight(orig, dest, dep, arr, cap);
            W.flights.add(f);
            W.outByAirport.computeIfAbsent(orig, k->new ArrayList<>()).add(f);
        }
        W.outByAirport.values().forEach(lst -> lst.sort(Comparator.comparingInt(fl->fl.depLocalMin)));
    }

    // ===================== GA: Cromosoma =============================
    static class Chromosome {
        double[] keys; // una key por vuelo plantilla
        Chromosome(int n) { keys = new double[n]; }
        Chromosome copy(){ Chromosome c=new Chromosome(keys.length); System.arraycopy(keys,0,c.keys,0,keys.length); return c; }
    }
    static Chromosome randomChromosome(int n, Random rnd){
        Chromosome c=new Chromosome(n);
        for(int i=0;i<n;i++) c.keys[i]=rnd.nextDouble();
        return c;
    }

    // ===================== Haversine / progreso ======================
    static double haversineKm(double lat1,double lon1,double lat2,double lon2){
        double R=6371.0;
        double dLat=Math.toRadians(lat2-lat1);
        double dLon=Math.toRadians(lon2-lon1);
        double a = Math.sin(dLat/2)*Math.sin(dLat/2)
                + Math.cos(Math.toRadians(lat1))*Math.cos(Math.toRadians(lat2))*Math.sin(dLon/2)*Math.sin(dLon/2);
        double c = 2*Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
        return R*c;
    }

    // ===================== Precomputación de tiempos/capacidades =====
    static class Precomp {
        int[][] depUTC;   // [fi][d]
        int[][] arrUTC;   // [fi][d]
        int[][] capUsedArr; // [fi][d]
        Map<Flight,Integer> flightIndex = new HashMap<>();
        Map<String,int[]> outIdxByAirport = new HashMap<>();
    }

    static Precomp precompute(World W, int horizonDays){
        Precomp P = new Precomp();
        int n = W.flights.size();
        P.depUTC = new int[n][horizonDays];
        P.arrUTC = new int[n][horizonDays];
        P.capUsedArr = new int[n][horizonDays];

        for (int i=0;i<n;i++) P.flightIndex.put(W.flights.get(i), i);

        for (int fi=0; fi<n; fi++){
            Flight f = W.flights.get(fi);
            Airport aO = W.airports.get(f.orig), aD = W.airports.get(f.dest);
            int baseDep = toUTCFromLocal(aO, f.depLocalMin);
            int baseArr = toUTCFromLocal(aD, f.arrLocalMin);
            for (int d=0; d<horizonDays; d++){
                int dep = baseDep + d*1440;
                int arr = baseArr + d*1440;
                if (arr<dep) arr += 1440;
                P.depUTC[fi][d]=dep;
                P.arrUTC[fi][d]=arr;
            }
        }
        for (Map.Entry<String,List<Flight>> e: W.outByAirport.entrySet()){
            String ap = e.getKey();
            List<Flight> lst = e.getValue();
            int[] idx = new int[lst.size()];
            for (int i=0;i<idx.length;i++) idx[i] = P.flightIndex.get(lst.get(i));
            P.outIdxByAirport.put(ap, idx);
        }
        return P;
    }

    // ===================== Stock por intervalos (difference arrays) ==
    static class StockTracker {
        static class Store {
            final int[] delta;
            final int[] pref;
            boolean dirty = true;
            Store(int n){ delta=new int[n+1]; pref=new int[n+1]; }
        }
        final int numSlots;
        final World W;
        final Map<String,Store> stores = new HashMap<>();

        StockTracker(World W, int horizonDays){
            this.W = W;
            this.numSlots = (horizonDays*1440)/SLOT_MIN + 5;
        }
        Store get(String ap){
            return stores.computeIfAbsent(ap, k->new Store(numSlots));
        }
        int capacityOf(Airport a){
            if (a==null) return Integer.MAX_VALUE;
            return a.isExporter ? Integer.MAX_VALUE : a.storageCap;
        }
        boolean canFit(String ap, int slotStart, int slotEnd, int qty){
            if (slotStart>=slotEnd || qty<=0) return true;
            Airport a=W.airports.get(ap);
            int cap = capacityOf(a);
            if (cap==Integer.MAX_VALUE) return true;
            Store st = get(ap);
            if (st.dirty){
                int run=0;
                for (int i=0;i<st.pref.length;i++){ run += st.delta[i]; st.pref[i]=run; }
                st.dirty=false;
            }
            for (int t=slotStart; t<slotEnd; t++){
                if (st.pref[t] + qty > cap) return false;
            }
            return true;
        }
        void addInterval(String ap, int slotStart, int slotEnd, int qty){
            if (slotStart>=slotEnd || qty==0) return;
            Store st = get(ap);
            st.delta[slotStart]+=qty; st.delta[slotEnd]-=qty;
            st.dirty = true;
        }
    }

    // ===================== Selección de vuelos (score heurístico) ====
    static class FlightCandidate {
        int fi, dayIndex, depUTC, arrUTC;
    }

    static class SelectContext {
        World W;
        Precomp P;
        Chromosome chrom;
        String destTarget;
        double wKey = 1.0, wEarly = 0.1, wGeo = 0.05; // pesos existentes
        // NEW: pesos para “más directo”
        double wDirect = 50.0;   // gran bonus si dest == target
        double wTwoHop = 5.0;    // bonus si hay vuelo directo desde next -> target
        Map<String,Double> distToDestByAp; // cache distancias
        // NEW: aeropuertos con vuelo directo hacia el destino
        Set<String> hasDirectToTarget = new HashSet<>();
    }

    static FlightCandidate selectByPriority(List<FlightCandidate> cand, SelectContext ctx){
        if (cand.isEmpty()) return null;
        int minArr = Integer.MAX_VALUE;
        for (int i=0;i<cand.size();i++){
            int v = cand.get(i).arrUTC;
            if (v<minArr) minArr = v;
        }
        double bestScore = -1e18;
        FlightCandidate best = null;
        for (int i=0;i<cand.size();i++){
            FlightCandidate c = cand.get(i);
            Flight f = ctx.W.flights.get(c.fi);
            int idx = ctx.P.flightIndex.get(f);
            double key = ctx.chrom.keys[idx];

            double timeGainHours = -((c.arrUTC - minArr) / 60.0);

            Double db = ctx.distToDestByAp.get(f.orig);
            Double da = ctx.distToDestByAp.get(f.dest);
            double progress = 0.0;
            if (db!=null && da!=null) progress = db - da;

            // NEW: bonus por “más directo”
            double directBonus = f.dest.equals(ctx.destTarget) ? ctx.wDirect : 0.0;
            double twoHopBonus = ctx.hasDirectToTarget.contains(f.dest) ? ctx.wTwoHop : 0.0;

            double score = ctx.wKey*key + ctx.wEarly*timeGainHours + ctx.wGeo*progress
                         + directBonus + twoHopBonus;

            if (score > bestScore){ bestScore=score; best=c; }
        }
        return best;
    }

    // ===================== Decoder (elige hub por subruta) ===========
    static class DecodeContext {
        World W;
        int horizonDays;
        Map<String,Integer> capUsedMap;
        Precomp P;
        StockTracker stock;
        Chromosome chrom;
        Random rnd;
        int numSlots;
        List<FlightCandidate> candBuf = new ArrayList<>(64);
        Map<String,Double> distToDestByAp = new HashMap<>();
    }

    static String fkey(Flight f, int d){ return f.orig+">"+f.dest+"@D"+d+"#"+f.depLocalMin; }

    static int computeDueForHub(World W, String hub, String dest, int releaseMinUTC){
        Airport ah = W.airports.get(hub);
        Airport ad = W.airports.get(dest);
        boolean same = (ah!=null && ad!=null && ah.continent==ad.continent);
        return releaseMinUTC + (same ? DUE_SAME_MIN : DUE_CROSS_MIN) + PICKUP_WINDOW_MIN;
    }

    static List<String> hubs(World W){
        return W.hubList;
    }

    static int enumerateCandidates(DecodeContext dc, String current, int tNowUTC, int dueLimit, int neededQty){
        dc.candBuf.clear();
        int[] outIdx = dc.P.outIdxByAirport.getOrDefault(current, null);
        if (outIdx==null) return 0;

        Airport aOrig = dc.W.airports.get(current);
        if (aOrig==null) return 0;

        for (int k=0; k<outIdx.length; k++){
            int fi = outIdx[k];
            Flight f = dc.W.flights.get(fi);
            Airport aDest = dc.W.airports.get(f.dest);
            if (aDest==null) continue;

            for (int d=0; d<dc.horizonDays; d++){
                int depUTC = dc.P.depUTC[fi][d];
                int arrUTC = dc.P.arrUTC[fi][d];

                if (depUTC < tNowUTC + MIN_TURN_MIN) continue;
                if (arrUTC > dueLimit) continue;

                int used = dc.P.capUsedArr[fi][d];
                int avail = f.capacity - used;
                if (avail <= 0) continue;
                if (avail < neededQty) continue;

                FlightCandidate fc = new FlightCandidate();
                fc.fi = fi; fc.dayIndex = d; fc.depUTC = depUTC; fc.arrUTC = arrUTC;
                dc.candBuf.add(fc);
            }
        }

        if (dc.candBuf.size() > TOPK_CANDIDATES) {
            dc.candBuf.sort(Comparator.comparingInt(c -> c.arrUTC));
            dc.candBuf.subList(TOPK_CANDIDATES, dc.candBuf.size()).clear();
        }
        return dc.candBuf.size();
    }

    // Construye UNA subruta completa desde un hub hasta el destino
    static SubRoute buildSubrouteFromHub(DecodeContext dc, Order o, String hub, int dueLimit, int blockQty){
        String current = hub;
        int tNow = o.releaseMinUTC;

        SubRoute sr = new SubRoute();
        sr.originHub = hub;
        sr.qty = blockQty;

        SelectContext sctx = new SelectContext();
        sctx.W = dc.W; sctx.P = dc.P; sctx.chrom = dc.chrom; sctx.destTarget = o.dest;
        sctx.distToDestByAp = dc.distToDestByAp;

        // NEW: precalcular distancias y “dos saltos”
        if (sctx.distToDestByAp.isEmpty()){
            var ad = dc.W.airports.get(o.dest);
            if (ad!=null){
                for (Airport a: dc.W.airports.values()){
                    double d = haversineKm(a.lat, a.lon, ad.lat, ad.lon);
                    sctx.distToDestByAp.put(a.code, d);
                }
            }
        }
        // NEW: construir set de aeropuertos con vuelo directo hacia el destino
        for (var e: dc.W.outByAirport.entrySet()){
            String ap = e.getKey();
            boolean has = false;
            for (Flight f: e.getValue()){
                if (f.dest.equals(o.dest)) { has=true; break; }
            }
            if (has) sctx.hasDirectToTarget.add(ap);
        }

        int expansions = 0, maxExp = 2000;
        boolean firstLeg = true;
        // NEW: evitar revisitas
        Set<String> visited = new HashSet<>();
        visited.add(hub);

        while (!current.equals(o.dest) && expansions++ < maxExp) {
            int cc = enumerateCandidates(dc, current, tNow, dueLimit, sr.qty);
            if (cc==0) return null;

            // NEW: filtrar candidatos que ya fueron visitados (evita bucles)
            dc.candBuf.removeIf(c -> {
                String next = dc.W.flights.get(c.fi).dest;
                return visited.contains(next);
            });
            if (dc.candBuf.isEmpty()) return null;

            FlightCandidate chosen = null;
            for (;;) {
                FlightCandidate best = selectByPriority(dc.candBuf, sctx);
                if (best == null) return null;

                Airport apCur = dc.W.airports.get(current);
                boolean curIsHub = (apCur!=null && apCur.isExporter);
                if (!firstLeg && !curIsHub) {
                    int s = slotOf(tNow, dc.numSlots);
                    int e = slotOf(best.depUTC, dc.numSlots);
                    if (!dc.stock.canFit(current, s, e, sr.qty)) {
                        dc.candBuf.remove(best);
                        if (dc.candBuf.isEmpty()) return null;
                        continue;
                    }
                    dc.stock.addInterval(current, s, e, sr.qty);
                }
                chosen = best;
                break;
            }

            Flight chF = dc.W.flights.get(chosen.fi);
            int prev = dc.P.capUsedArr[chosen.fi][chosen.dayIndex];
            dc.P.capUsedArr[chosen.fi][chosen.dayIndex] = prev + sr.qty;

            String k = fkey(chF, chosen.dayIndex);
            int usedMap = dc.capUsedMap.getOrDefault(k,0);
            dc.capUsedMap.put(k, usedMap + sr.qty);

            sr.legs.add(new FlightUse(chF, chosen.dayIndex, chosen.depUTC, chosen.arrUTC, sr.qty));

            current = chF.dest;
            tNow = chosen.arrUTC;
            firstLeg = false;
            visited.add(current);

            if (current.equals(o.dest)) {
                // Reservar mínima ventana [arr, arr+120] por subruta (se extenderá al completar el pedido)
                int s = slotOf(tNow, dc.numSlots);
                int e = slotOf(tNow + PICKUP_WINDOW_MIN, dc.numSlots);
                if (!dc.stock.canFit(current, s, e, sr.qty)) {
                    return null;
                }
                dc.stock.addInterval(current, s, e, sr.qty);
                sr.arrivalUTC = tNow;
                return sr;
            }
        }
        return null;
    }

    // NEW: estructura para llevar las reservas de destino de un pedido y poder extenderlas
    static class DestReservation {
        int startSlot;
        int endSlot;
        int qty;
        DestReservation(int s, int e, int q){ startSlot=s; endSlot=e; qty=q; }
    }

    static Solution decode(World W, List<Order> orders, Chromosome chrom, int horizonDays, long seed){
        Solution sol = new Solution();
        sol.capUsed = new HashMap<>();
        StockTracker stock = new StockTracker(W, horizonDays);
        Precomp P = precompute(W, horizonDays);

        // ordenar pedidos por release
        List<Order> ordSorted = new ArrayList<>(orders);
        ordSorted.sort(Comparator.comparingInt(o->o.releaseMinUTC));

        DecodeContext dc = new DecodeContext();
        dc.W=W; dc.horizonDays=horizonDays; dc.capUsedMap=sol.capUsed; dc.P=P; dc.stock=stock; dc.chrom=chrom; dc.rnd=new Random(seed);
        dc.numSlots = (horizonDays*1440)/SLOT_MIN + 5;

        int onTime=0, late=0, viol=0; long slackSum=0; int slackCnt=0;

        for (Order o: ordSorted){
            int remaining = o.qty;
            List<SubRoute> subroutes = new ArrayList<>(4);

            // NEW: reservas de destino para poder extenderlas cuando cambie el “último arribo”
            List<DestReservation> destHolds = new ArrayList<>();
            int lastArrival = -1;

            int guard=0, guardMax=500;
            while (remaining>0 && guard++<guardMax){
                final int[] BLOCK_TRY = {256,128,64,32,16,8,4,2,1};

                SubRoute bestSr = null;
                int bestArr = Integer.MAX_VALUE;

                for (int bt : BLOCK_TRY) {
                    int block = Math.min(remaining, bt);

                    SubRoute candidateBest = null;
                    int candidateBestArr = Integer.MAX_VALUE;

                    for (String hub: hubs(W)) {
                        int due = computeDueForHub(W, hub, o.dest, o.releaseMinUTC);
                        SubRoute sr = buildSubrouteFromHub(dc, o, hub, due, block);
                        if (sr != null && sr.arrivalUTC < candidateBestArr) {
                            candidateBestArr = sr.arrivalUTC;
                            candidateBest = sr;
                        }
                    }
                    if (candidateBest != null) {
                        bestSr = candidateBest;
                        bestArr = candidateBestArr;
                        break;
                    }
                }

                if (bestSr == null) break;

                // NEW: registrar la reserva mínima que ya hizo buildSubroute...
                int start = slotOf(bestSr.arrivalUTC, dc.numSlots);
                int end   = slotOf(bestSr.arrivalUTC + PICKUP_WINDOW_MIN, dc.numSlots);
                destHolds.add(new DestReservation(start, end, bestSr.qty));

                subroutes.add(bestSr);
                remaining -= bestSr.qty;

                // NEW: actualizar “último arribo” y EXTENDER todas las reservas de destino hasta (last+120)
                if (bestSr.arrivalUTC > lastArrival) {
                    int newLast = bestSr.arrivalUTC;
                    int newEnd = slotOf(newLast + PICKUP_WINDOW_MIN, dc.numSlots);
                    for (DestReservation r: destHolds){
                        if (r.endSlot < newEnd){
                            // verificar sólo el tramo adicional [r.endSlot, newEnd)
                            if (stock.canFit(o.dest, r.endSlot, newEnd, r.qty)) {
                                stock.addInterval(o.dest, r.endSlot, newEnd, r.qty);
                                r.endSlot = newEnd;
                            } else {
                                // no cabe la extensión -> contamos violación (penaliza objetivo)
                                viol++;
                            }
                        }
                    }
                    lastArrival = newLast;
                }
            }

            sol.routes.put(o, subroutes);

            int delivered = 0;
            for (int i=0;i<subroutes.size();i++) delivered += subroutes.get(i).qty;

            if (delivered < o.qty) {
                late++;
            } else {
                SubRoute crit = null;
                int maxArr = Integer.MIN_VALUE;
                for (int i=0;i<subroutes.size();i++){
                    SubRoute s = subroutes.get(i);
                    if (s.arrivalUTC > maxArr){ maxArr = s.arrivalUTC; crit = s; }
                }
                if (crit != null){
                    int dueCrit = computeDueForHub(W, crit.originHub, o.dest, o.releaseMinUTC);
                    if (crit.arrivalUTC <= dueCrit) {
                        onTime++;
                        slackSum += (dueCrit - crit.arrivalUTC);
                        slackCnt++;
                    } else late++;
                } else late++;
            }
        }

        sol.servedOnTime = onTime; sol.servedLate = late; sol.capViol = viol;
        sol.avgSlack = (slackCnt==0)?0:(int)(slackSum/slackCnt);
        sol.objective = LAMBDA_ONTIME*onTime - LAMBDA_LATE*late - LAMBDA_CAPVIO*viol + LAMBDA_SLACK*sol.avgSlack;
        return sol;
    }

    // ===================== GA Core ==================================
    static double fitness(World W, List<Order> orders, Chromosome c, int horizonDays){
        return decode(W, orders, c, horizonDays, 12345L).objective;
    }

    static Chromosome crossover(Chromosome a, Chromosome b, Random rnd){
        if (rnd.nextDouble()>PCROSS) return rnd.nextBoolean()?a.copy():b.copy();
        Chromosome c=new Chromosome(a.keys.length);
        for (int i=0;i<a.keys.length;i++) c.keys[i]=(rnd.nextBoolean()?a.keys[i]:b.keys[i]);
        return c;
    }

    static void mutate(Chromosome c, Random rnd){
        for (int i=0;i<c.keys.length;i++){
            if (rnd.nextDouble()<PMUT){
                double v = c.keys[i] + rnd.nextGaussian()*0.1;
                c.keys[i] = (v<0.0)?0.0:((v>1.0)?1.0:v);
            }
        }
    }

    static class Scored {
        Chromosome c;
        double fit;
        Scored(Chromosome c, double fit){ this.c=c; this.fit=fit; }
    }

    static Solution runGA(World W, List<Order> orders, int horizonDays, long seed){
        Random rnd = new Random(seed);
        List<Chromosome> pop = new ArrayList<>(POP_SIZE);
        for (int i=0;i<POP_SIZE;i++) pop.add(randomChromosome(W.flights.size(), rnd));

        Chromosome best=null; double bestFit=-1e18; int stall=0;

        for (int gen=1; gen<=MAX_GEN; gen++){
            List<Scored> scored = new ArrayList<>(POP_SIZE);
            for (int i=0;i<pop.size();i++){
                Chromosome c = pop.get(i);
                scored.add(new Scored(c, fitness(W, orders, c, horizonDays)));
            }

            scored.sort((a,b)->Double.compare(b.fit, a.fit));
            List<Chromosome> next = new ArrayList<>(POP_SIZE);
            for (int i=0;i<ELITE_K;i++) next.add(scored.get(i).c.copy());

            while (next.size()<POP_SIZE){
                Chromosome p1 = scored.get(rnd.nextInt(scored.size())).c;
                Chromosome p2 = scored.get(rnd.nextInt(scored.size())).c;
                Chromosome ch = crossover(p1,p2,rnd);
                mutate(ch,rnd);
                next.add(ch);
            }
            pop = next;

            Scored iterBest = scored.get(0);
            double iterFit = iterBest.fit;

            if (iterFit > bestFit){ bestFit=iterFit; best=iterBest.c.copy(); stall=0; }
            else stall++;

            if (stall>=NO_IMPROV_LIMIT) break;
        }
        return decode(W, orders, best, horizonDays, seed);
    }

    // ===================== Carga de órdenes ==========================
    // OLD (compat): DEST-QTY-RELHH:MM
    static List<Order> loadOrdersLegacy(Path file, World W) throws IOException {
        List<Order> L = new ArrayList<>();
        for (String s: readAllLinesAuto(file)){
            String line = s.trim(); if (line.isEmpty()) continue;
            String[] p = line.split("-");
            if (p.length<3) continue;
            String dest = p[0].trim();
            if (!W.airports.containsKey(dest)) continue;
            int qty = Integer.parseInt(p[1].trim());
            int rel = parseHHMM(p[2].trim());
            L.add(new Order(dest, qty, rel));
        }
        return L;
    }

    // NEW: dd-hh-mm-dest-###-IdClien   (UTC)
    static List<Order> loadOrdersMonthly(Path file, World W) throws IOException {
        List<Order> L = new ArrayList<>();
        int lineNo = 0;
        for (String s: readAllLinesAuto(file)){
            lineNo++;
            String line = s.trim(); if (line.isEmpty()) continue;
            String[] p = line.split("-");
            if (p.length < 6) {
                // formato inválido -> ignorar
                continue;
            }
            try {
                int dd = Integer.parseInt(p[0]);
                int hh = Integer.parseInt(p[1]);
                int mm = Integer.parseInt(p[2]);
                String dest = p[3].trim();
                String qtyStr = p[4].trim();
                String idRaw = p[5].trim();

                if (dd<1 || dd>31) continue;
                if (hh<0 || hh>23) continue;
                if (mm<0 || mm>59) continue;
                if (!dest.matches("[A-Z]{4}")) continue;
                if (!W.airports.containsKey(dest)) continue;

                int qty = Integer.parseInt(qtyStr);
                // normalizar clientId a 7 dígitos
                String clientId = String.format("%07d", Integer.parseInt(idRaw));

                int releaseMinUTC = (dd-1)*1440 + hh*60 + mm;

                L.add(new Order(dest, qty, releaseMinUTC, dd, clientId));
            } catch (Exception ignore) {
                // ignora línea mal formada
            }
        }
        // IMPORTANTE: tu horizonte debe cubrir los días usados.
        return L;
    }

    // ===================== Main (CLI) ================================
    public static void main(String[] args) throws Exception {
        Path airportsFile = Paths.get("src/main/java/com/twoalg/common/aeropuertos.txt");
        Path flightsFile  = Paths.get("src/main/java/com/twoalg/common/planes_vuelos.txt");
        Path ordersFile   = Paths.get("src/main/java/com/twoalg/common/ordenes2.txt"); // mensual

        World W = new World();
        loadAirports(airportsFile, W);
        loadFlights(flightsFile, W);

        // NEW: usa el formato mensual
        List<Order> orders = loadOrdersMonthly(ordersFile, W);
        // Si quieres usar el formato antiguo, usa: loadOrdersLegacy(ordersFile, W);

        // OJO: horizonDays debe ser suficientemente grande para dd usados (p.ej. 7 días o 31).
        int horizonDays = 4; // ajusta según tu dataset mensual
        long seed = 20250917L;

        Solution best = runGA(W, orders, horizonDays, seed);

        System.out.println("Objetivo: " + best.objective);
        System.out.println("On-time: " + best.servedOnTime + " | Late: " + best.servedLate +
                " | CapViol: " + best.capViol + " | AvgSlack(min): " + best.avgSlack);

        for (Order o: orders) {
            List<SubRoute> lst = best.routes.get(o);
            int delivered = (lst==null)?0:lst.stream().mapToInt(s->s.qty).sum();
            System.out.println("\nPedido destino " + o.dest +
                    " qty=" + o.qty + " releaseUTC=" + mmToHHMM(o.releaseMinUTC) +
                    " delivered=" + delivered + " day=" + o.dayOfMonth +
                    " client=" + (o.clientId==null?"":o.clientId));

            if (lst != null) {
                int i=1;
                for (SubRoute sr: lst) {
                    System.out.println("  Subruta #" + (i++) + " hub=" + sr.originHub +
                            " qty=" + sr.qty + " arrivalUTC=" + mmToHHMM(sr.arrivalUTC));
                    for (FlightUse fu: sr.legs) {
                        System.out.println("    " + fu.flight.orig + "->" + fu.flight.dest +
                                " D" + fu.dayIndex + " depUTC=" + mmToHHMM(fu.depUTC) +
                                " arrUTC=" + mmToHHMM(fu.arrUTC) + " qty=" + fu.qtyAssigned);
                    }
                }
            }
        }
    }
}
