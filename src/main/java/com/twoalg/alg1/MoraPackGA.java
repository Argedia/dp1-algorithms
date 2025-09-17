// MoraPackGA.java
// GA con decodificador (órdenes sin origen): elige hub (entre EXPORT_HUBS) por subruta.
// Reglas: conexión mínima, due 2/3 días, ventana de recojo 2h, escalas, split entre subrutas.
// Control de capacidad de vuelo y de almacén por intervalos (difference array por slots).

import java.io.*;
import java.nio.file.*;
import java.nio.charset.*;
import java.time.*;
import java.util.*;
import java.util.stream.*;

public class MoraPackGA {

    // ===================== Parámetros de negocio =====================
    static final int MIN_TURN_MIN = 30;               // conexión mínima
    static final int DUE_SAME_MIN = 2 * 24 * 60;      // 2 días
    static final int DUE_CROSS_MIN = 3 * 24 * 60;     // 3 días
    static final int PICKUP_WINDOW_MIN = 120;         // 2 horas de recojo

    // ===================== Parámetros GA =============================
    static final int POP_SIZE = 80;
    static final int MAX_GEN  = 200;
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

    // Exportadores ()
    static final Set<String> EXPORT_HUBS = Set.of(
        "SPIM",  // Lima
        "EBCI",  // Bruselas
        "UBBB"   // Bakú
    );

    // Stock/Almacén: granularidad de slots para difference arrays
    static final int SLOT_MIN = 60; // 60 minutos

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
        if (c == 'S') return Continent.SOUTH_AMERICA;  // Heurística común para Sudamérica
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
        public Order(String dest, int qty, int releaseMinUTC) {
            this.dest = dest; this.qty = qty; this.releaseMinUTC = releaseMinUTC;
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

    // Una subruta mueve un BLOQUE fijo de cantidad desde un hub hasta el destino (todas las piernas con esa qty)
    static class SubRoute {
        String originHub;
        List<FlightUse> legs = new ArrayList<>();
        int qty;          // cantidad del bloque
        int arrivalUTC;   // llegada al destino
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
    }

    // ===================== Tiempo / Conversión =======================
    static int parseHHMM(String hhmm) {
        String[] p = hhmm.trim().split(":");
        return Integer.parseInt(p[0])*60 + Integer.parseInt(p[1]);
    }
    static int toUTCFromLocal(Airport a, int localMin) { return localMin - a.gmt*60; }
    static String mmToHHMM(int m) {
        int x = Math.floorMod(m, 1440);
        return String.format("%02d:%02d", x/60, x%60);
    }
    static int slotOf(int utcMin) { return Math.max(0, utcMin / SLOT_MIN); }

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

    // airports: línea con CODE, GMT (-12..14), CAP (600..1000), opcional lat lon
    static void loadAirports(Path file, World W) throws IOException {
        for (String s: readAllLinesAuto(file)) {
            String line = s.trim();
            if (line.isEmpty()) continue;
            String[] tok = line.split("\\s+|,");
            // busca un CODE 4 letras mayúsculas
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

    // ===================== Stock por intervalos (difference arrays) ==
    static class DiffMap {
        // slot -> delta
        TreeMap<Integer,Integer> map = new TreeMap<>();
        void add(int idx, int delta){ map.put(idx, map.getOrDefault(idx,0)+delta); }
        int get(int idx){ return map.getOrDefault(idx,0); }
        NavigableMap<Integer,Integer> sub(int from, int to){ return map.subMap(from,true, to,false); }
    }
    static class StockTracker {
        Map<String,DiffMap> delta = new HashMap<>(); // aeropuerto -> deltas por slot
        int capacityOf(Airport a){
            if (a==null) return Integer.MAX_VALUE;
            return a.isExporter ? Integer.MAX_VALUE : a.storageCap;
        }
        boolean canFit(World W, String ap, int slotStart, int slotEnd, int qty){
            if (slotStart>=slotEnd || qty<=0) return true;
            Airport a=W.airports.get(ap);
            int cap = capacityOf(a);
            if (cap==Integer.MAX_VALUE) return true;
            DiffMap dm = delta.computeIfAbsent(ap,k->new DiffMap());
            // evaluar prefijo en rango: O(rango) (suficiente para tamaños moderados)
            int pref=0;
            // sumar deltas hasta slotStart
            for (var e: dm.map.headMap(slotStart,true).entrySet()) pref+=e.getValue();
            int cur=pref;
            // recorrer slots [slotStart, slotEnd)
            // para performance real, podrías compilar prefijos o usar estructura segmentada
            int lastIdx = slotStart;
            // Comprobación “densa” conservadora (cada slot)
            for (int t=slotStart; t<slotEnd; t++){
                cur += dm.get(t);
                if (cur + qty > cap) return false;
            }
            return true;
        }
        void addInterval(String ap, int slotStart, int slotEnd, int qty){
            if (slotStart>=slotEnd || qty<=0) return;
            DiffMap dm = delta.computeIfAbsent(ap,k->new DiffMap());
            dm.add(slotStart, +qty);
            dm.add(slotEnd,   -qty);
        }
    }

    // ===================== Selección de vuelos (score heurístico) ====
    static class FlightCandidate {
        Flight flight; int dayIndex; int depUTC, arrUTC;
    }

    static class SelectContext {
        World W;
        Map<Flight,Integer> flightIndex;
        Chromosome chrom;
        String destTarget;
        double wKey = 1.0, wEarly = 0.1, wGeo = 0.05; // pesos ajustables
    }

    static FlightCandidate selectByPriority(List<FlightCandidate> cand, SelectContext ctx){
        if (cand.isEmpty()) return null;
        int minArr = cand.stream().mapToInt(c->c.arrUTC).min().orElse(Integer.MAX_VALUE);
        Airport aDest = ctx.W.airports.get(ctx.destTarget);

        double bestScore = -1e18;
        FlightCandidate best = null;
        for (FlightCandidate c: cand){
            int idx = ctx.flightIndex.get(c.flight);
            double key = ctx.chrom.keys[idx];

            // bonus por llegar más temprano (horas respecto al mínimo de candidatos)
            double timeGainHours = -( (c.arrUTC - minArr) / 60.0 );

            // progreso geográfico: dist antes - dist después (km)
            Airport aOrig = ctx.W.airports.get(c.flight.orig);
            Airport aNext = ctx.W.airports.get(c.flight.dest);
            double distBefore = (aOrig!=null && aDest!=null) ? haversineKm(aOrig.lat,aOrig.lon, aDest.lat,aDest.lon) : 0.0;
            double distAfter  = (aNext!=null && aDest!=null) ? haversineKm(aNext.lat,aNext.lon, aDest.lat,aDest.lon) : 0.0;
            double progress = distBefore - distAfter; // mayor es mejor

            double score = ctx.wKey*key + ctx.wEarly*timeGainHours + ctx.wGeo*progress;
            if (score > bestScore){ bestScore=score; best=c; }
        }
        return best;
    }

    // ===================== Decoder (elige hub por subruta) ===========
    static class DecodeContext {
        World W;
        int horizonDays;
        Map<String,Integer> capUsed;
        Map<Flight,Integer> flightIndex;
        StockTracker stock;
        Chromosome chrom;
        Random rnd;
    }

    static String fkey(Flight f, int d){ return f.orig+">"+f.dest+"@D"+d+"#"+f.depLocalMin; }

    static int computeDueForHub(World W, String hub, String dest, int releaseMinUTC){
        Airport ah = W.airports.get(hub);
        Airport ad = W.airports.get(dest);
        boolean same = (ah!=null && ad!=null && ah.continent==ad.continent);
        return releaseMinUTC + (same ? DUE_SAME_MIN : DUE_CROSS_MIN) + PICKUP_WINDOW_MIN;
    }

    static List<String> hubs(World W){
        return EXPORT_HUBS.stream().filter(W.airports::containsKey).toList();
    }

    static List<FlightCandidate> enumerateCandidates(DecodeContext dc, String current, int tNowUTC, int dueLimit, int neededQty){
        List<FlightCandidate> res = new ArrayList<>();
        List<Flight> out = dc.W.outByAirport.getOrDefault(current, Collections.emptyList());
        Airport aOrig = dc.W.airports.get(current);
        if (aOrig==null) return res;

        for (Flight f: out){
            Airport aDest = dc.W.airports.get(f.dest);
            if (aDest==null) continue;
            for (int d=0; d<dc.horizonDays; d++){
                int depUTC = toUTCFromLocal(aOrig, f.depLocalMin) + d*1440;
                int arrUTC = toUTCFromLocal(aDest, f.arrLocalMin) + d*1440;
                if (arrUTC < depUTC) arrUTC += 1440;

                if (depUTC < tNowUTC + MIN_TURN_MIN) continue;
                if (arrUTC > dueLimit) continue;

                String key = fkey(f,d);
                int used = dc.capUsed.getOrDefault(key,0);
                int avail = f.capacity - used;
                if (avail <= 0) continue;

                // el bloque que moveremos (activeQty) debe caber en TODAS las piernas
                if (avail < neededQty) continue;

                FlightCandidate fc = new FlightCandidate();
                fc.flight=f; fc.dayIndex=d; fc.depUTC=depUTC; fc.arrUTC=arrUTC;
                res.add(fc);
            }
        }
        return res;
    }

    // Construye UNA subruta completa desde un hub hasta el destino llevando un BLOQUE fijo (blockQty)
    static SubRoute buildSubrouteFromHub(DecodeContext dc, Order o, String hub, int dueLimit, int blockQty){
        String current = hub;
        int tNow = o.releaseMinUTC;

        SubRoute sr = new SubRoute();
        sr.originHub = hub;
        sr.qty = blockQty;

        SelectContext sctx = new SelectContext();
        sctx.W = dc.W; sctx.flightIndex = dc.flightIndex; sctx.chrom = dc.chrom; sctx.destTarget = o.dest;

        int expansions = 0, maxExp = 2000;

        // Nota: para el primer despegue desde HUB no reservamos almacén (stock infinito).
        boolean firstLeg = true;

        while (!current.equals(o.dest) && expansions++ < maxExp) {
            // Candidatos que puedan cargar el BLOQUE completo
            List<FlightCandidate> cand = enumerateCandidates(dc, current, tNow, dueLimit, sr.qty);
            if (cand.isEmpty()) return null;

            // Si estamos esperando en un aeropuerto que no es hub (o es hub pero quieres modelar), debemos
            // verificar que el almacén pueda sostener el BLOQUE desde tNow hasta el próximo depUTC.
            // Por eso, al evaluar cada candidato, comprobamos primero el intervalo [tNow, depUTC) del aeropuerto actual.
            FlightCandidate chosen = null;
            for (;;) {
                FlightCandidate best = selectByPriority(cand, sctx);
                if (best == null) return null;

                // Chequeo de almacén (espera en current hasta el despegue)
                Airport apCur = dc.W.airports.get(current);
                boolean curIsHub = (apCur!=null && apCur.isExporter);
                if (!firstLeg && !curIsHub) {
                    int s = slotOf(tNow);
                    int e = slotOf(best.depUTC);
                    if (!dc.stock.canFit(dc.W, current, s, e, sr.qty)) {
                        // no cabe: descarta este candidato y prueba otro
                        cand.remove(best);
                        if (cand.isEmpty()) return null;
                        continue;
                    }
                    // reservar intervalo de espera
                    dc.stock.addInterval(current, s, e, sr.qty);
                }
                chosen = best;
                break;
            }

            // reservar capacidad de vuelo
            String k = fkey(chosen.flight, chosen.dayIndex);
            int used = dc.capUsed.getOrDefault(k,0);
            dc.capUsed.put(k, used + sr.qty);

            // registrar pierna
            sr.legs.add(new FlightUse(chosen.flight, chosen.dayIndex, chosen.depUTC, chosen.arrUTC, sr.qty));

            // avanzar
            current = chosen.flight.dest;
            tNow = chosen.arrUTC;
            firstLeg = false;

            // si llegamos al destino, reservar ventana de recojo
            if (current.equals(o.dest)) {
                // reservar [arr, arr+pickup]
                int s = slotOf(tNow);
                int e = slotOf(tNow + PICKUP_WINDOW_MIN);
                if (!dc.stock.canFit(dc.W, current, s, e, sr.qty)) {
                    // si no cabe ni la ventana de recojo, falla la subruta
                    return null;
                }
                dc.stock.addInterval(current, s, e, sr.qty);
                sr.arrivalUTC = tNow;
                return sr;
            }
        }
        return null;
    }

    static Solution decode(World W, List<Order> orders, Chromosome chrom, int horizonDays, long seed){
        Solution sol = new Solution();
        sol.capUsed = new HashMap<>();
        StockTracker stock = new StockTracker();

        // índices de vuelos
        Map<Flight,Integer> flightIndex = new HashMap<>();
        for (int i=0;i<W.flights.size();i++) flightIndex.put(W.flights.get(i), i);

        // ordenar pedidos por release
        List<Order> ordSorted = new ArrayList<>(orders);
        ordSorted.sort(Comparator.comparingInt(o->o.releaseMinUTC));

        DecodeContext dc = new DecodeContext();
        dc.W=W; dc.horizonDays=horizonDays; dc.capUsed=sol.capUsed; dc.flightIndex=flightIndex; dc.stock=stock; dc.chrom=chrom; dc.rnd=new Random(seed);

        int onTime=0, late=0, viol=0; long slackSum=0; int slackCnt=0;

        for (Order o: ordSorted){
            int remaining = o.qty;
            List<SubRoute> subroutes = new ArrayList<>();

            int guard=0, guardMax=500; // para evitar bucles
            while (remaining>0 && guard++<guardMax){
                // escoger hub y bloque a mover: estrategia simple → probar todos los hubs con un bloque “razonable”
                // bloque = min(remaining, bestCapSalidaHub) → aquí aproximamos con min(remaining, 400)
                int tentativeBlock = Math.min(remaining, 400);

                SubRoute bestSr = null;
                int bestDue = Integer.MAX_VALUE;
                int bestArr = Integer.MAX_VALUE;

                for (String hub: hubs(W)) {
                    int due = computeDueForHub(W, hub, o.dest, o.releaseMinUTC);
                    SubRoute sr = buildSubrouteFromHub(dc, o, hub, due, tentativeBlock);
                    if (sr != null) {
                        // prioriza llegada más temprana; desempate por mayor qty (igual aquí fija)
                        if (sr.arrivalUTC < bestArr) {
                            bestArr = sr.arrivalUTC;
                            bestDue = due;
                            bestSr = sr;
                        }
                    }
                }

                if (bestSr == null) break; // no se pudo mover más

                subroutes.add(bestSr);
                remaining -= bestSr.qty;
            }

            sol.routes.put(o, subroutes);

            int delivered = subroutes.stream().mapToInt(s->s.qty).sum();
            if (delivered < o.qty) {
                late++;
            } else {
                // crítico: llegada más tardía
                SubRoute crit = subroutes.stream().max(Comparator.comparingInt(s->s.arrivalUTC)).orElse(null);
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
        // Usa semilla fija para que la evaluación sea determinista
        return decode(W, orders, c, horizonDays, 12345L).objective;
    }

    static Chromosome tournament(List<Chromosome> pop, World W, List<Order> orders, int horizonDays, Random rnd){
        Chromosome best=null; double bestFit=-1e18;
        for (int i=0;i<TOURN_K;i++){
            Chromosome cand = pop.get(rnd.nextInt(pop.size()));
            double fit = fitness(W, orders, cand, horizonDays);
            if (fit>bestFit){ bestFit=fit; best=cand; }
        }
        return best;
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
                c.keys[i] = Math.max(0.0, Math.min(1.0, v));
            }
        }
    }

    static Solution runGA(World W, List<Order> orders, int horizonDays, long seed){
        Random rnd = new Random(seed);
        List<Chromosome> pop = new ArrayList<>();
        for (int i=0;i<POP_SIZE;i++) pop.add(randomChromosome(W.flights.size(), rnd));

        Chromosome best=null; double bestFit=-1e18; int stall=0;

        for (int gen=1; gen<=MAX_GEN; gen++){
            // elitismo
            List<Chromosome> sorted = pop.stream()
                .sorted(Comparator.comparingDouble(c->-fitness(W,orders, (Chromosome)c, horizonDays)))
                .collect(Collectors.toList());
            List<Chromosome> off = new ArrayList<>();
            for (int i=0;i<ELITE_K;i++) off.add(sorted.get(i).copy());

            while (off.size()<POP_SIZE){
                Chromosome p1 = tournament(pop, W, orders, horizonDays, rnd);
                Chromosome p2 = tournament(pop, W, orders, horizonDays, rnd);
                Chromosome ch = crossover(p1,p2,rnd);
                mutate(ch,rnd);
                off.add(ch);
            }
            pop = off;

            Chromosome iterBest = pop.stream().max(Comparator.comparingDouble(c->fitness(W,orders,c,horizonDays))).orElse(pop.get(0));
            double iterFit = fitness(W,orders,iterBest,horizonDays);

            if (iterFit > bestFit){ bestFit=iterFit; best=iterBest.copy(); stall=0; }
            else stall++;

            if (stall>=NO_IMPROV_LIMIT) break;
        }
        return decode(W, orders, best, horizonDays, seed);
    }

    // ===================== Carga de órdenes opcional =================
    // Formato: DEST-QTY-RELHH:MM   (ej.: SVMI-500-08:00)
    static List<Order> loadOrders(Path file, World W) throws IOException {
        List<Order> L = new ArrayList<>();
        for (String s: readAllLinesAuto(file)){
            String line = s.trim(); if (line.isEmpty()) continue;
            String[] p = line.split("-");
            if (p.length<3) continue;
            String dest = p[0].trim();
            int qty = Integer.parseInt(p[1].trim());
            int rel = parseHHMM(p[2].trim());
            // release en UTC (suponemos que release ya viene en UTC HH:MM; si es local habría que convertir)
            L.add(new Order(dest, qty, rel));
        }
        return L;
    }

    // ===================== Main (CLI) ================================
    public static void main(String[] args) throws Exception {
        Path airportsFile = Paths.get("src/main/java/com/twoalg/common/aeropuertos.txt");
        Path flightsFile  = Paths.get("src/main/java/com/twoalg/common/planes_vuelos.txt");

        World W = new World();
        loadAirports(airportsFile, W);
        loadFlights(flightsFile, W);

        List<Order> orders;
        // dataset de prueba
        orders = List.of(
            new Order("SVMI", 50, 8*60),      // llega a Caracas
            new Order("SBBR", 70, 6*60+30),   // llega a Brasilia
            new Order("EBCI", 90, 5*60)       // llega a Bruselas
        );

        int horizonDays = 4;
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
                    " delivered=" + delivered);

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
