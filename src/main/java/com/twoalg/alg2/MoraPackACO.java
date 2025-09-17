import java.io.*;
import java.nio.charset.Charset;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.*;

public class MoraPackACO {

    // =========================
    // PARÁMETROS DE NEGOCIO
    // =========================
    static final int MIN_CONEXION = 30; // minutos conexión mínima
    static final Duration PLAZO_INTRA = Duration.ofDays(2); // plazo intra-continente
    static final Duration PLAZO_INTER = Duration.ofDays(3); // plazo inter-continente

    // =========================
    // PARÁMETROS ACO
    // =========================
    static int N_HORMIGAS = 125;
    static int MAX_ITER = 300;
    static double ALPHA = 1.5;
    static double BETA = 4.5;
    static double RHO = 0.04;    // evaporación
    static double PHI = 0.10;    // actualización local
    static double TAU0 = 0.01;   // feromona inicial
    static double Q = 1.5;       // refuerzo

    // Restricciones
    static final int MAX_ESCALAS = 8;
    static final int MAX_VISITAS_AEROPUERTO = 2;
    static final int STAGNATION_PATIENCE = 80;

    // =========================
    // MODELOS
    // =========================
    static class Aeropuerto {
        String codigo;
        String ciudad, pais, continente;
        int gmtOffset;
        int capacidadAlmacen;
        double lat = Double.NaN, lon = Double.NaN;
    }

    static class Vuelo {
        String origen, destino;
        String horaOrigen, horaDestino;
        int capacidad;
        long salidaUTC, llegadaUTC;

        public String toString(){ return origen+"->"+destino+"("+horaOrigen+"-"+horaDestino+")"; }
    }

    static class Pedido {
        int id;
        String origen, destino;
        int cantidad;
        long releaseUTC, dueUTC;
    }

    static class Ruta {
        List<Vuelo> vuelos = new ArrayList<>();
        long llegadaFinalUTC;
        boolean onTime;
    }

    static class Instancia {
        Map<String, Aeropuerto> aeropuertos = new HashMap<>();
        List<Vuelo> vuelos = new ArrayList<>();
        Map<String,List<Vuelo>> grafo = new HashMap<>();
        List<Pedido> pedidos = new ArrayList<>();
    }

    static class Solucion {
        Map<Integer,Ruta> rutas = new HashMap<>();
        int onTime=0, late=0, violCap=0;
        double obj=0;
    }

    // =========================
    // FEROMONAS
    // =========================
    static String key(Vuelo v){ return v.origen+"|"+v.destino+"|"+v.horaOrigen+"|"+v.horaDestino; }
    static Map<String, Double> tau = new HashMap<>();
    static Random RNG = new Random();

    // =========================
    // UTILIDADES
    // =========================
    static long toUTC(String hhmm, int gmt, LocalDate ancla){
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("HH:mm");
        LocalTime lt = LocalTime.parse(hhmm, fmt);
        LocalDateTime localDT = LocalDateTime.of(ancla, lt);
        int minutos = gmt * 60;
        return localDT.toEpochSecond(ZoneOffset.ofTotalSeconds(minutos*60))/60L;
    }

    static boolean mismoContinente(Aeropuerto a, Aeropuerto b){
        return a!=null && b!=null && Objects.equals(a.continente,b.continente);
    }

    static double haversine(double lat1,double lon1,double lat2,double lon2){
        double R=6371.0, dLat=Math.toRadians(lat2-lat1), dLon=Math.toRadians(lon2-lon1);
        double a=Math.sin(dLat/2)*Math.sin(dLat/2)+
                 Math.cos(Math.toRadians(lat1))*Math.cos(Math.toRadians(lat2))*Math.sin(dLon/2)*Math.sin(dLon/2);
        double c=2*Math.atan2(Math.sqrt(a),Math.sqrt(1-a));
        return R*c;
    }

    static double progreso(String actual,String destino,Instancia inst){
        Aeropuerto a=inst.aeropuertos.get(actual), b=inst.aeropuertos.get(destino);
        if (a==null||b==null) return 0.0;
        if (Double.isNaN(a.lat) || Double.isNaN(a.lon) || Double.isNaN(b.lat) || Double.isNaN(b.lon)) return 0.0;
        double d=haversine(a.lat,a.lon,b.lat,b.lon);
        return Math.max(0.0, Math.min(1.0, 1.0 - d/15000.0));
    }

    // =========================
    // HEURÍSTICA
    // =========================
    static double heuristic(
            Pedido o, String actual, long tNow,
            Vuelo f, Map<String,Integer> usadoLocal, Map<String,Integer> usadoGlobal,
            Instancia inst, long[] salidaLlegadaAjustadas) {

        if (!f.origen.equals(actual)) return 0;

        long salida = f.salidaUTC;
        long llegada = f.llegadaUTC;
        while (salida < tNow + MIN_CONEXION) { salida += 1440; llegada += 1440; }
        salidaLlegadaAjustadas[0] = salida;
        salidaLlegadaAjustadas[1] = llegada;

        String k = key(f);
        int usado = usadoGlobal.getOrDefault(k,0) + usadoLocal.getOrDefault(k,0);
        int capDisp = f.capacidad - usado;
        if (capDisp < o.cantidad) return 0;

        double hCap   = capDisp / (double) f.capacidad;
        double hProg  = progreso(f.destino, o.destino, inst);
        double wait   = Math.max(0, salida - tNow);
        double hWait  = 1.0 / (1.0 + wait/45.0);
        double slack  = o.dueUTC - llegada;
        double hSlack = slack >= 0 ? 1.0 : Math.exp(slack/60.0);

        double eta = 0.35*hCap + 0.30*hProg + 0.20*hWait + 0.15*hSlack;
        return Math.max(0, eta);
    }

    // =========================
    // CONSTRUCCIÓN DE RUTA
    // =========================
    static Ruta construirRuta(Pedido o, Instancia inst, Map<String,Integer> usadoGlobal){
        String cur = o.origen;
        long tNow = o.releaseUTC;

        Map<String,Integer> usadoLocal = new HashMap<>();
        Map<String,Integer> visitas = new HashMap<>();
        visitas.put(cur, 1);

        Ruta ruta = new Ruta();
        int hops = 0;

        while(!cur.equals(o.destino) && hops < MAX_ESCALAS){
            List<Vuelo> cand = inst.grafo.getOrDefault(cur, List.of());
            if (cand.isEmpty()) return null;

            List<Vuelo> elegibles = new ArrayList<>();
            List<Double> pesos = new ArrayList<>();
            double sum = 0.0;

            for (Vuelo f: cand){
                int visDest = visitas.getOrDefault(f.destino,0);
                if (visDest >= MAX_VISITAS_AEROPUERTO) continue;

                long[] times = new long[2];
                double eta = heuristic(o, cur, tNow, f, usadoLocal, usadoGlobal, inst, times);
                if (eta <= 0) continue;

                String k = key(f);
                double t = tau.getOrDefault(k, TAU0);
                double val = Math.pow(t, ALPHA) * Math.pow(eta, BETA);

                elegibles.add(f);
                pesos.add(val);
                sum += val;
            }

            if (elegibles.isEmpty()) return null;

            double r = RNG.nextDouble() * sum;
            Vuelo elegido = null;
            long salidaAdj = 0, llegadaAdj = 0;

            for (int i=0; i<elegibles.size(); i++){
                r -= pesos.get(i);
                if (r <= 0 || i == elegibles.size()-1){
                    elegido = elegibles.get(i);
                    salidaAdj = elegido.salidaUTC;
                    llegadaAdj = elegido.llegadaUTC;
                    while (salidaAdj < tNow + MIN_CONEXION) { salidaAdj += 1440; llegadaAdj += 1440; }
                    break;
                }
            }

            if (elegido == null) return null;

            String k = key(elegido);
            double tVal = tau.getOrDefault(k, TAU0);
            tau.put(k, (1.0 - PHI) * tVal + PHI * TAU0);

            ruta.vuelos.add(elegido);
            usadoLocal.put(k, usadoLocal.getOrDefault(k,0)+o.cantidad);
            cur = elegido.destino;
            tNow = llegadaAdj;
            visitas.put(cur, visitas.getOrDefault(cur,0)+1);
            hops++;
        }

        if (!cur.equals(o.destino)) return null;

        ruta.llegadaFinalUTC = tNow;
        return ruta;
    }

    // =========================
    // CONSTRUCCIÓN SOLUCIÓN
    // =========================
    static Solucion construirSolucion(Instancia inst){
        Solucion sol = new Solucion();
        Map<String,Integer> usadoGlobal = new HashMap<>();

        List<Pedido> pedidos = new ArrayList<>(inst.pedidos);
        pedidos.sort(Comparator.comparingLong(p -> p.dueUTC));

        for (Pedido o: pedidos){
            Ruta r = construirRuta(o, inst, usadoGlobal);

            if (r == null) {
                sol.late++;
                continue;
            }

            r.onTime = (r.llegadaFinalUTC <= o.dueUTC);
            if (r.onTime) sol.onTime++; else sol.late++;

            sol.rutas.put(o.id, r);

            for (Vuelo f: r.vuelos){
                String k = key(f);
                int nuevo = usadoGlobal.getOrDefault(k,0) + o.cantidad;
                if (nuevo > f.capacidad) sol.violCap++;
                usadoGlobal.put(k, nuevo);
            }
        }

        sol.obj = sol.onTime - 3*sol.late - 5*sol.violCap;
        return sol;
    }

    // =========================
    // ACO PRINCIPAL
    // =========================
    static Solucion ACO_MAIN(Instancia inst){
        for (Vuelo f: inst.vuelos) tau.putIfAbsent(key(f), TAU0);

        Solucion best = null;
        double bestObj = -1e18;
        int sinMejora = 0;
        long t0 = System.currentTimeMillis();

        for (int it=0; it<MAX_ITER; it++){
            List<Solucion> sols = new ArrayList<>();
            for (int k=0; k<N_HORMIGAS; k++) sols.add(construirSolucion(inst));
            sols.sort(Comparator.comparingDouble(s->-s.obj));
            Solucion iterBest = sols.get(0);

            for (String k: tau.keySet()){
                tau.put(k, (1.0 - RHO) * tau.get(k));
            }

            for (Map.Entry<Integer, Ruta> e : iterBest.rutas.entrySet()){
                Ruta r = e.getValue();
                double bonus = r.onTime ? Q : Q * 0.1;
                for (Vuelo f: r.vuelos){
                    String k = key(f);
                    tau.put(k, tau.getOrDefault(k, TAU0) + bonus);
                }
            }

            if (best == null || iterBest.obj > bestObj){
                best = iterBest;
                bestObj = iterBest.obj;
                sinMejora = 0;
            } else {
                sinMejora++;
            }

            if (it % 50 == 0 || it == MAX_ITER-1) {
                double tauMax = tau.values().stream().mapToDouble(x->x).max().orElse(0);
                double tauMin = tau.values().stream().mapToDouble(x->x).min().orElse(0);
                long elapsed = System.currentTimeMillis() - t0;
                System.out.printf(
                    "Iter %4d | iterBest=%.2f | best=%.2f | onTime=%d | late=%d | viol=%d | tau[%.4f–%.4f] | t=%ds%n",
                    it, iterBest.obj, bestObj, iterBest.onTime, iterBest.late, iterBest.violCap,
                    tauMin, tauMax, elapsed/1000
                );
            }

            if (sinMejora >= STAGNATION_PATIENCE){
                for (String k: tau.keySet()) tau.put(k, TAU0);
                sinMejora = 0;
            }
        }

        long t1 = System.currentTimeMillis();
        System.out.println("⏱ Tiempo total ACO_MAIN = " + (t1 - t0) + " ms");

        return best;
    }

    // =========================
    // LECTURA INPUTS
    // =========================
    static Map<String,Aeropuerto> cargarAeropuertos(Path path) throws IOException {
        List<String> lineas = Files.readAllLines(path, Charset.forName("UTF-16"));
        Map<String,Aeropuerto> map = new HashMap<>();
        String continente = "SA";

        Pattern fila = Pattern.compile("^\\s*\\d+\\s+([A-Z0-9]{3,4})\\s+(.+?)\\s+(.+?)\\s+[A-Za-z]{4}\\s+([+-]?\\d+)\\s+(\\d+).*$");

        for (String ln : lineas) {
            String l = ln.trim();
            if (l.isEmpty()) continue;

            if (l.contains("America del Sur")) { continente = "SA"; continue; }
            if (l.contains("Europa"))          { continente = "EU"; continue; }
            if (l.contains("Asia"))            { continente = "AS"; continue; }

            Matcher m = fila.matcher(ln);
            if (m.matches()) {
                Aeropuerto a = new Aeropuerto();
                a.codigo = m.group(1).trim();
                a.ciudad = m.group(2).trim().replaceAll("\\s+", " ");
                a.pais   = m.group(3).trim().replaceAll("\\s+", " ");
                a.gmtOffset = Integer.parseInt(m.group(4).trim());
                a.capacidadAlmacen = Integer.parseInt(m.group(5).trim());
                a.continente = continente;
                map.put(a.codigo, a);
            }
        }
        return map;
    }

    static List<Vuelo> cargarVuelos(Path path, Map<String,Aeropuerto> aeropuertos, LocalDate ancla) throws IOException {
        List<String> lineas = Files.readAllLines(path);
        List<Vuelo> lista = new ArrayList<>();
        for (String ln: lineas){
            String[] t = ln.split("-");
            if (t.length < 5) continue;
            Vuelo v = new Vuelo();
            v.origen = t[0].trim(); v.destino = t[1].trim();
            v.horaOrigen = t[2].trim(); v.horaDestino = t[3].trim();
            try { v.capacidad = Integer.parseInt(t[4].trim()); } catch(Exception e){ v.capacidad = 300; }

            Aeropuerto ao = aeropuertos.get(v.origen);
            Aeropuerto ad = aeropuertos.get(v.destino);
            if (ao == null || ad == null) {
                System.out.println("[WARN] Vuelo ignorado: " + v.origen + "->" + v.destino);
                continue;
            }

            v.salidaUTC = toUTC(v.horaOrigen, ao.gmtOffset, ancla);
            v.llegadaUTC = toUTC(v.horaDestino, ad.gmtOffset, ancla);
            if (v.llegadaUTC < v.salidaUTC) v.llegadaUTC += 24*60;
            lista.add(v);
        }
        return lista;
    }

    static List<Pedido> construirGrupoPedidos(Instancia inst, LocalDate ancla, String[][] pares, int startId) {
        List<Pedido> pedidos = new ArrayList<>();
        int id = startId;

        for (String[] par : pares) {
            String o = par[0], d = par[1];

            if (!inst.aeropuertos.containsKey(o) || !inst.aeropuertos.containsKey(d)) {
                System.out.println("[WARN] Pedido ignorado: " + o + " -> " + d);
                continue;
            }

            Pedido p = new Pedido();
            p.id = id++;
            p.origen = o;
            p.destino = d;
            p.cantidad = 80 + RNG.nextInt(100);
            p.releaseUTC = ancla.atTime(6 + RNG.nextInt(6), RNG.nextInt(60))
                                .toEpochSecond(ZoneOffset.UTC) / 60;

            boolean same = mismoContinente(inst.aeropuertos.get(o), inst.aeropuertos.get(d));
            p.dueUTC = p.releaseUTC + (same ? PLAZO_INTRA.toMinutes() : PLAZO_INTER.toMinutes());

            pedidos.add(p);
        }
        return pedidos;
    }

    // =========================
    // MAIN
    // =========================
    public static void main(String[] args) throws Exception {
        Path aTxt = Paths.get("c.1inf54.25.2.Aeropuerto.husos.v1.20250818__estudiantes.txt");
        Path vTxt = Paths.get("c.1inf54.25.2.planes_vuelo.v4.20250818.txt");
        LocalDate ancla = LocalDate.now();

        Instancia inst = new Instancia();
        inst.aeropuertos = cargarAeropuertos(aTxt);
        inst.vuelos = cargarVuelos(vTxt, inst.aeropuertos, ancla);

        for (Vuelo f : inst.vuelos)
            inst.grafo.computeIfAbsent(f.origen, k -> new ArrayList<>()).add(f);
        for (List<Vuelo> lst : inst.grafo.values())
            lst.sort(Comparator.comparingLong(v -> v.salidaUTC));

        String[][] paresGrupo = {
            {"SGAS", "LOWW"},  // Asunción (SA) -> Viena (EU)
            {"SABE", "EHAM"},  // Buenos Aires (SA) -> Ámsterdam (EU)
            {"SKBO", "OMDB"},  // Bogotá (SA) -> Dubái (AS)
            {"SCEL", "LATI"},  // Santiago (SA) -> Tirana (EU)
            {"SEQM", "UBBB"},  // Quito (SA) -> Bakú (AS)
            {"SBBR", "EHAM"},   // Brasilia (SA) -> Ámsterdam (EU)
            {"SEQM", "SPIM"},  // Quito (SA) -> Lima (SA)
            {"SABE", "SGAS"},  // Buenos Aires (SA) -> Asunción (SA)
            {"EHAM", "LOWW"},  // Ámsterdam (EU) -> Viena (EU)
            {"OMDB", "UBBB"}   // Dubái (AS) -> Bakú (AS)
        };

        List<Pedido> pedidosGrupo = construirGrupoPedidos(inst, ancla, paresGrupo,1);
        inst.pedidos = pedidosGrupo; 
        Solucion best = ACO_MAIN(inst);

        // === Reporte ===
        System.out.println("=== Insumos cargados ===");
        System.out.println("Aeropuertos: " + inst.aeropuertos.size());
        System.out.println("Vuelos: " + inst.vuelos.size());

        System.out.println();
        System.out.println("=== RESULTADO ACO ===");
        System.out.println("on_time=" + best.onTime +
                        " late=" + best.late +
                        " viol=" + best.violCap +
                        " obj=" + best.obj);

        for (Pedido o : inst.pedidos) {
            Ruta r = best.rutas.get(o.id);
            if (r == null) {
                System.out.println("Pedido " + o.id + " (" + o.origen + "->" + o.destino +
                                ", q=" + o.cantidad + "): [sin ruta]");
            } else {
                StringBuilder sb = new StringBuilder("[ruta ");
                for (int i=0; i<r.vuelos.size(); i++) {
                    Vuelo v = r.vuelos.get(i);
                    sb.append(v.origen).append("->").append(v.destino);
                    if (i < r.vuelos.size()-1) sb.append("||");
                }
                sb.append("] ");
                sb.append(r.onTime ? "(on-time)" : "(late)");
                System.out.println("Pedido " + o.id + " (" + o.origen + "->" + o.destino +
                                ", q=" + o.cantidad + "): " + sb.toString());
            }
        }
    }

}
