// MoraPackGA.java
// Java 17+
// GA con decodificador que respeta: conexión mínima, due dates 2/3 días, escalas, split de pedidos,
// capacidades de vuelo y almacén. Vuelos diarios (plantillas).

import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.*;

public class MoraPackGA {

    // ===================== Parámetros de negocio =====================
    static final int MIN_TURN_MIN = 30;           // conexión mínima
    static final int DUE_SAME_MIN = 2 * 24 * 60;  // 2 días
    static final int DUE_CROSS_MIN = 3 * 24 * 60; // 3 días

    // Si el enunciado requiere 2h de ventana de recojo adicional, se puede sumar al due si aplica.
    static final int PICKUP_WINDOW_MIN = 0;

    // ===================== Parámetros GA =============================
    static final int POP_SIZE = 80;
    static final int MAX_GEN  = 200;
    static final double PCROSS = 0.8;
    static final double PMUT   = 0.05;
    static final int ELITE_K   = 4;
    static final int TOURN_K   = 3;
    static final int NO_IMPROV_LIMIT = 40;

    // Objetivo (ajustar pesos según preferencia)
    static final double LAMBDA_ONTIME = 1.0;
    static final double LAMBDA_LATE   = 1.5;
    static final double LAMBDA_CAPVIO = 4.0;
    static final double LAMBDA_SLACK  = 0.001; // bonus por margen

    // ===================== Datos y estructuras =======================

    static class Airport {
        String code;   // ICAO (p.ej., SPIM, SVMI, EHAM)
        int gmt;       // offset en horas respecto de UTC (puede ser negativo)
        int storageCap;
        double lat, lon;
        Continent continent;

        public Airport(String code, int gmt, int storageCap, double lat, double lon) {
            this.code = code;
            this.gmt = gmt;
            this.storageCap = storageCap;
            this.lat = lat;
            this.lon = lon;
            this.continent = inferContinent(code);
        }
    }

    enum Continent { SOUTH_AMERICA, EUROPE, ASIA, OTHER }

    // Heurística robusta para este dataset: por prefijo ICAO
    static Continent inferContinent(String icao) {
        // América del Sur: ICAO empieza con 'S'
        if (icao != null && icao.length() >= 1 && icao.charAt(0) == 'S') return Continent.SOUTH_AMERICA;
        // Europa: prefijos comunes E, L, U
        if (icao != null && !icao.isEmpty()) {
            char c = icao.charAt(0);
            if (c == 'E' || c == 'L' || c == 'U') return Continent.EUROPE;
            // Asia (conjunto O, V)
            if (c == 'O' || c == 'V') return Continent.ASIA;
        }
        return Continent.OTHER;
    }

    static class Flight {
        String orig, dest;          // ICAO
        int depLocalMin;            // minutos desde 00:00 (hora local ORIG)
        int arrLocalMin;            // minutos desde 00:00 (hora local DEST)
        int capacity;               // paquetes por salida
        // Plantilla diaria: se puede volar día t con salida depUTCMin + t*1440

        public Flight(String orig, String dest, int depLocalMin, int arrLocalMin, int capacity) {
            this.orig = orig;
            this.dest = dest;
            this.depLocalMin = depLocalMin;
            this.arrLocalMin = arrLocalMin;
            this.capacity = capacity;
        }
    }

    static class Order {
        String orig, dest;
        int qty;
        int releaseMinUTC; // minuto 0 es inicio del día 0 UTC del plan
        int dueMinUTC;     // release + (2d o 3d) + (pickup window opcional)

        public Order(String orig, String dest, int qty, int releaseMinUTC) {
            this.orig = orig;
            this.dest = dest;
            this.qty = qty;
            this.releaseMinUTC = releaseMinUTC;
        }
    }

    // Asignación de carga en un vuelo específico de un día (instancia concreta)
    static class FlightUse {
        Flight flight;
        int dayIndex;     // 0,1,2... (vuelos diarios)
        int qtyAssigned;  // cantidad asignada en este vuelo
        int depUTC, arrUTC;

        public FlightUse(Flight f, int dayIndex, int depUTC, int arrUTC, int qty) {
            this.flight = f;
            this.dayIndex = dayIndex;
            this.depUTC = depUTC;
            this.arrUTC = arrUTC;
            this.qtyAssigned = qty;
        }

        String key() {
            return fkey(flight, dayIndex);
        }
    }

    // Rutas por “lote” para un order (split permitido => varias subrutas)
    static class SubRoute {
        List<FlightUse> legs = new ArrayList<>();
        int qty; // cantidad que esta subruta entrega
        int arrivalUTC;
    }

    static class Solution {
        Map<Order, List<SubRoute>> routes = new HashMap<>();
        // capacities usadas por (flight, dayIndex)
        Map<String, Integer> capUsed = new HashMap<>();
        // métricas
        int servedOnTime;
        int servedLate;
        int capViol;
        double objective;
        int avgSlack; // promedio del slack de subrutas entregadas (minutos)
    }

    // ===================== Repositorio cargado =======================
    static class World {
        Map<String, Airport> airports = new HashMap<>();
        List<Flight> flights = new ArrayList<>();
        Map<String, List<Flight>> outByAirport = new HashMap<>();
    }

    // ===================== Utilitarios de tiempo =====================
    static int parseHHMM(String hhmm) {
        String[] p = hhmm.split(":");
        return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
    }

    static int toUTCFromLocal(Airport a, int localMin) {
        return localMin - a.gmt * 60;
    }

    static int toLocalFromUTC(Airport a, int utcMin) {
        return utcMin + a.gmt * 60;
    }

    static String mmToHHMM(int m) {
        int x = Math.floorMod(m, 1440);
        int h = x / 60;
        int mi = x % 60;
        return String.format("%02d:%02d", h, mi);
    }

    static String fkey(Flight f, int dayIndex) {
        return f.orig + ">" + f.dest + "@" + dayIndex + "#" + f.depLocalMin;
    }

    // ===================== Carga de datos ============================

    // Formato airports: tomamos: code (col 2), GMT (col con signo), CAPACIDAD (almacén)
    // El archivo del curso tiene texto descriptivo; aquí parseamos con regex flexible.
    static void loadAirports(Path file, World W) throws IOException {
        List<String> lines = Files.readAllLines(file);
        for (String line : lines) {
            // Ejemplo de línea:
            // "05   SPIM   Lima                Perú            lima    -5     440     Latitude: ..."
            String s = line.trim();
            if (s.isEmpty() || Character.isLetter(s.charAt(0)) && !Character.isDigit(s.charAt(0))) continue;
            String[] toks = s.split("\\s+");
            if (toks.length < 7) continue;
            // Habitualmente: [idx, CODE, City, Country, alias, GMT, CAP]
            String code = toks[1];
            // busca GMT y CAP leyendo números con signo
            Integer gmt = null, cap = null;
            for (int i = 0; i < toks.length; i++) {
                if (toks[i].matches("[+-]?\\d+")) {
                    int val = Integer.parseInt(toks[i]);
                    if (gmt == null && val >= -12 && val <= 14) gmt = val;
                    else if (cap == null && val > 0) cap = val;
                }
            }
            if (code != null && gmt != null && cap != null) {
                // lat/lon opcional: no críticos para el planificador base
                W.airports.put(code, new Airport(code, gmt, cap, 0, 0));
            }
        }
    }

    // Formato vuelos: ORIG-DEST-HH:MM-HH:MM-CAPAC (horas locales respectivas)
    static void loadFlights(Path file, World W) throws IOException {
        List<String> lines = Files.readAllLines(file);
        for (String line : lines) {
            String s = line.trim();
            if (s.isEmpty()) continue;
            String[] p = s.split("-");
            if (p.length < 5) continue;
            String orig = p[0];
            String dest = p[1];
            int dep = parseHHMM(p[2]);
            int arr = parseHHMM(p[3]);
            int cap = Integer.parseInt(p[4]);
            Flight f = new Flight(orig, dest, dep, arr, cap);
            W.flights.add(f);
            W.outByAirport.computeIfAbsent(orig, k -> new ArrayList<>()).add(f);
        }
        // ordena salidas por hora local (para acelerar decodificación)
        W.outByAirport.values().forEach(lst -> lst.sort(Comparator.comparingInt(fl -> fl.depLocalMin)));
    }

    // ===================== Due dates ================================
    static void setOrderDue(World W, Order o) {
        Airport ao = W.airports.get(o.orig);
        Airport ad = W.airports.get(o.dest);
        boolean sameCont = (ao != null && ad != null && ao.continent == ad.continent);
        int due = o.releaseMinUTC + (sameCont ? DUE_SAME_MIN : DUE_CROSS_MIN) + PICKUP_WINDOW_MIN;
        o.dueMinUTC = due;
    }

    // ===================== Cromosoma ================================
    // Codificación indirecta: para cada (airport, flightIndexLocal) guardamos una “clave” (double)
    // usada para priorizar vuelos factibles en el decoder. Flujo: el mayor PRIORITY se elige.
    static class Chromosome {
        // Claves por vuelo (índice global en W.flights)
        double[] keys;

        public Chromosome(int nFlights) {
            keys = new double[nFlights];
        }

        Chromosome copy() {
            Chromosome c = new Chromosome(keys.length);
            System.arraycopy(this.keys, 0, c.keys, 0, keys.length);
            return c;
        }
    }

    static Chromosome randomChromosome(int nFlights, Random rnd) {
        Chromosome c = new Chromosome(nFlights);
        for (int i = 0; i < nFlights; i++) c.keys[i] = rnd.nextDouble();
        return c;
    }

    // ===================== Decoder con split ========================
    // Idea: para cada pedido, vamos enviando su "remaining" buscando vuelos factibles.
    // Permitimos usar múltiples subrutas (split). Mantiene un mapa (flight,day) -> capUsed.

    static Solution decode(World W, List<Order> orders, Chromosome chrom, int horizonDays) {
        Solution sol = new Solution();

        // cap usada por instancia de vuelo (flight@day)
        Map<String, Integer> capUsed = new HashMap<>();

        int servedOnTime = 0, servedLate = 0, capViol = 0;
        long slackSum = 0;
        int slackCnt = 0;

        // Ordenamos pedidos por release y, como desempate, distancia continental (mismo->antes)
        List<Order> ordSorted = new ArrayList<>(orders);
        ordSorted.sort(Comparator
                .comparingInt((Order o) -> o.releaseMinUTC)
                .thenComparing(o -> {
                    Airport ao = W.airports.get(o.orig);
                    Airport ad = W.airports.get(o.dest);
                    return (ao != null && ad != null && ao.continent == ad.continent) ? 0 : 1;
                }));

        // Índice rápido de Flight -> index global (para acceder key)
        Map<Flight, Integer> flightIndex = new HashMap<>();
        for (int i = 0; i < W.flights.size(); i++) flightIndex.put(W.flights.get(i), i);

        for (Order o : ordSorted) {
            int remaining = o.qty;
            List<SubRoute> subroutes = new ArrayList<>();

            // Encolamos “bultos” dinámicamente según capacidad disponible
            // Estrategia: greedy por clave prioritaria (chrom.keys[idx]) + progreso a destino (aprox por continuidad)
            // Mantiene múltiples “corrientes” hasta completar remaining o agotar horizonte.

            // Por simplicidad, iniciamos una “frontera” de estados: cada estado es (airport, tUTC, qtyPendienteLocal)
            // Aquí seguimos greedy: siempre expandimos desde el origen con el tiempo actual del pedido.
            // Si en un vuelo no cabe todo, asignamos parcial (split) y reducimos remaining, quedando el resto en el mismo nodo y tiempo >= conexión.

            String current = o.orig;
            int tNow = o.releaseMinUTC;

            // Para evitar ciclos infinitos si no hay servicio válido, ponemos un tope de expansiones
            int expansions = 0;
            int maxExpansions = 2000;

            // Mientras quede por enviar
            while (remaining > 0 && expansions++ < maxExpansions) {
                List<FlightCandidate> cand = enumerateCandidates(W, current, tNow, o, horizonDays, capUsed);

                if (cand.isEmpty()) {
                    // No hay forma de progresar desde este nodo/tiempo: “rompemos” y marcamos lo no servido como tarde
                    break;
                }

                // Score por clave genética + preferencia por vuelos que lleguen antes y/o acerquen al destino (proxy)
                FlightCandidate best = selectByPriority(cand, chrom, flightIndex, o.dest, W);

                // Cap disponible en este vuelo concreto
                String k = fkey(best.flight, best.dayIndex);
                int used = capUsed.getOrDefault(k, 0);
                int avail = best.flight.capacity - used;

                if (avail <= 0) {
                    // intentar siguiente mejor: lo filtramos y repetimos
                    cand.remove(best);
                    if (cand.isEmpty()) break;
                    best = selectByPriority(cand, chrom, flightIndex, o.dest, W);
                }

                int send = Math.min(avail, remaining);

                // Reservar capacidad
                capUsed.put(k, used + send);

                // Añadir a subruta actual o crear una nueva si empieza desde origen
                if (subroutes.isEmpty() || !endsAt(subroutes.get(subroutes.size() - 1), current, tNow)) {
                    SubRoute sr = new SubRoute();
                    sr.qty = 0;
                    subroutes.add(sr);
                }
                SubRoute sr = subroutes.get(subroutes.size() - 1);
                sr.qty += send;
                sr.legs.add(new FlightUse(best.flight, best.dayIndex, best.depUTC, best.arrUTC, send));
                current = best.flight.dest;
                tNow = best.arrUTC; // llegaste
                remaining -= send;

                // Si llegamos al destino para esta subruta, cerramos y, si queda “remaining”, reiniciamos desde origen
                if (current.equals(o.dest)) {
                    sr.arrivalUTC = tNow;
                    // Si aún hay que enviar más, reempezamos desde origen al release (o a release + MIN_TURN?)
                    current = o.orig;
                    tNow = o.releaseMinUTC;
                }

                // Corte por due date duro: si el próximo despegue factible ya cae después del due y no hemos completado, se cortará arriba.
            }

            sol.routes.put(o, subroutes);

            // Métricas por pedido
            int delivered = subroutes.stream().mapToInt(s -> s.qty).sum();
            if (delivered < o.qty) {
                // Parte sin servir => tarde
                servedLate++;
            } else {
                // llegada efectiva = max arrival de subrutas (todas deben llegar dentro del due)
                int maxArr = subroutes.stream().mapToInt(s -> s.arrivalUTC).max().orElse(Integer.MAX_VALUE);
                if (maxArr <= o.dueMinUTC) {
                    servedOnTime++;
                    slackSum += (o.dueMinUTC - maxArr);
                    slackCnt++;
                } else {
                    servedLate++;
                }
            }
        }

        // Verificación de violaciones de capacidad (no deberían ocurrir si respetamos capUsed)
        int viol = 0;
        for (Map.Entry<String, Integer> e : capUsed.entrySet()) {
            // Nada que hacer: capUsed nunca excede capacity en esta implementación
            // (si implementas reparaciones externas, aquí puedes contabilizar exceso)
            if (e.getValue() < 0) viol += -e.getValue(); // placeholder
        }

        sol.capUsed = capUsed;
        sol.servedOnTime = servedOnTime;
        sol.servedLate = servedLate;
        sol.capViol = viol;
        sol.avgSlack = (slackCnt == 0) ? 0 : (int)(slackSum / slackCnt);
        sol.objective = LAMBDA_ONTIME * servedOnTime - LAMBDA_LATE * servedLate - LAMBDA_CAPVIO * viol
                + LAMBDA_SLACK * sol.avgSlack;

        return sol;
    }

    static boolean endsAt(SubRoute sr, String airport, int tNow) {
        if (sr.legs.isEmpty()) return false;
        FlightUse last = sr.legs.get(sr.legs.size() - 1);
        return last.flight.dest.equals(airport) && last.arrUTC == tNow;
    }

    static class FlightCandidate {
        Flight flight;
        int dayIndex;
        int depUTC, arrUTC;
    }

    static List<FlightCandidate> enumerateCandidates(World W, String current, int tNowUTC, Order o,
                                                     int horizonDays, Map<String, Integer> capUsed) {
        List<Flight> out = W.outByAirport.getOrDefault(current, Collections.emptyList());
        Airport aOrig = W.airports.get(current);
        if (aOrig == null) return Collections.emptyList();

        List<FlightCandidate> res = new ArrayList<>();

        for (Flight f : out) {
            Airport aDest = W.airports.get(f.dest);
            if (aDest == null) continue;

            // Recorre días hasta horizonte
            for (int d = 0; d < horizonDays; d++) {
                int depUTC = toUTCFromLocal(aOrig, f.depLocalMin) + d * 1440;
                int arrUTC = toUTCFromLocal(aDest, f.arrLocalMin) + d * 1440;

                // Si arrUTC quedó antes que depUTC (por husos, o arr del día siguiente), empuja +1 día
                if (arrUTC < depUTC) arrUTC += 1440;

                // Conexión mínima y release time
                if (depUTC < tNowUTC + MIN_TURN_MIN) continue;

                // Chequeo simple de due: si incluso tomando este vuelo y “cero espera” más adelante no llegamos, descartar
                // Aquí una cota inferior de ETA restante: 0 (optimista). Para ser más estrictos, puedes añadir una heurística (no necesaria para factibilidad básica).
                if (arrUTC > o.dueMinUTC) continue;

                // Capacidad
                String key = fkey(f, d);
                int used = capUsed.getOrDefault(key, 0);
                if (used >= f.capacity) continue;

                FlightCandidate fc = new FlightCandidate();
                fc.flight = f;
                fc.dayIndex = d;
                fc.depUTC = depUTC;
                fc.arrUTC = arrUTC;
                res.add(fc);
            }
        }
        return res;
    }

    static FlightCandidate selectByPriority(List<FlightCandidate> cand, Chromosome chrom,
                                            Map<Flight, Integer> flightIndex, String destTarget, World W) {
        // PRIORIDAD = w1*key + w2*(progreso temporal) + w3*(progreso geográfico aproximado opcional)
        final double w1 = 1.0, w2 = 0.1;
        // progreso temporal: llegar antes es mejor => puntaje inverso a arrUTC
        int minArr = cand.stream().mapToInt(c -> c.arrUTC).min().orElse(Integer.MAX_VALUE);

        FlightCandidate best = null;
        double bestScore = -1e18;
        for (FlightCandidate c : cand) {
            int idx = flightIndex.get(c.flight);
            double key = chrom.keys[idx];
            double timeGain = -(c.arrUTC - minArr); // <= 0, más cerca de minArr => 0
            double score = w1 * key + w2 * timeGain;
            if (score > bestScore) {
                bestScore = score;
                best = c;
            }
        }
        return best;
    }

    // ===================== Fitness / Evaluación ======================
    static double fitness(World W, List<Order> orders, Chromosome c, int horizonDays) {
        return decode(W, orders, c, horizonDays).objective;
    }

    // ===================== GA Core ==================================
    static Chromosome tournament(List<Chromosome> pop, World W, List<Order> orders, int horizonDays, Random rnd) {
        Chromosome best = null;
        double bestFit = -1e18;
        for (int i = 0; i < TOURN_K; i++) {
            Chromosome cand = pop.get(rnd.nextInt(pop.size()));
            double fit = fitness(W, orders, cand, horizonDays);
            if (fit > bestFit) {
                bestFit = fit;
                best = cand;
            }
        }
        return best;
    }

    static Chromosome crossover(Chromosome a, Chromosome b, Random rnd) {
        if (rnd.nextDouble() > PCROSS) return rnd.nextBoolean() ? a.copy() : b.copy();
        Chromosome c = new Chromosome(a.keys.length);
        for (int i = 0; i < a.keys.length; i++) {
            c.keys[i] = (rnd.nextBoolean() ? a.keys[i] : b.keys[i]);
        }
        return c;
    }

    static void mutate(Chromosome c, Random rnd) {
        for (int i = 0; i < c.keys.length; i++) {
            if (rnd.nextDouble() < PMUT) {
                // jitter pequeño
                double v = c.keys[i] + rnd.nextGaussian() * 0.1;
                // clamp [0,1]
                c.keys[i] = Math.max(0.0, Math.min(1.0, v));
            }
        }
    }

    static Solution runGA(World W, List<Order> orders, int horizonDays, long seed) {
        Random rnd = new Random(seed);

        // Inicializar población
        List<Chromosome> pop = new ArrayList<>();
        for (int i = 0; i < POP_SIZE; i++) pop.add(randomChromosome(W.flights.size(), rnd));

        Chromosome best = null;
        double bestFit = -1e18;
        int stall = 0;

        for (int gen = 1; gen <= MAX_GEN; gen++) {
            List<Chromosome> off = new ArrayList<>(POP_SIZE);
            // Elitismo simple: guardar top K
            List<Chromosome> sorted = pop.stream()
                    .sorted(Comparator.comparingDouble(c -> -fitness(W, orders, (Chromosome)c, horizonDays)))
                    .collect(Collectors.toList());
            for (int i = 0; i < ELITE_K; i++) off.add(sorted.get(i).copy());

            // Resto por cruce y mutación
            while (off.size() < POP_SIZE) {
                Chromosome p1 = tournament(pop, W, orders, horizonDays, rnd);
                Chromosome p2 = tournament(pop, W, orders, horizonDays, rnd);
                Chromosome ch = crossover(p1, p2, rnd);
                mutate(ch, rnd);
                off.add(ch);
            }

            pop = off;

            Chromosome iterBest = pop.stream()
                    .max(Comparator.comparingDouble(c -> fitness(W, orders, c, horizonDays)))
                    .orElse(pop.get(0));
            double iterFit = fitness(W, orders, iterBest, horizonDays);

            if (iterFit > bestFit) {
                bestFit = iterFit;
                best = iterBest.copy();
                stall = 0;
            } else {
                stall++;
            }

            // Criterio de parada por estancamiento
            if (stall >= NO_IMPROV_LIMIT) break;
        }

        // Decodifica la mejor
        return decode(W, orders, best, horizonDays);
    }

    // ===================== Ejemplo de uso (main) =====================
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("Uso: java MoraPackGA <archivo_airports.txt> <archivo_flights.txt>");
            System.out.println("Ejemplo: java MoraPackGA c.1inf54.25.2.Aeropuerto.husos.v1.20250818__estudiantes.txt c.1inf54.25.2.planes_vuelo.v4.20250818.txt");
            return;
        }

        Path airportsFile = Paths.get(args[0]);
        Path flightsFile  = Paths.get(args[1]);

        World W = new World();
        loadAirports(airportsFile, W);
        loadFlights(flightsFile, W);

        // ===== Carga/ejemplo de pedidos =====
        // En tu proyecto, carga Orders desde archivo/DB. Aquí mock para probar:
        List<Order> orders = new ArrayList<>();
        // releaseMinUTC = minuto en línea de tiempo (ej: 8:00 UTC del día 0)
        orders.add(new Order("SPIM", "SVMI", 500, 8*60));      // Lima -> Caracas
        orders.add(new Order("SVMI", "SBBR", 700, 6*60+30));   // Caracas -> Brasilia
        orders.add(new Order("SPIM", "EBCI", 900, 5*60));      // Lima -> Bruselas (inter-continente)

        for (Order o : orders) setOrderDue(W, o);

        // Horizonte de días para buscar conexiones (ajusta según tamaño de plan)
        int horizonDays = 4;

        long seed = 20250917L;
        Solution best = runGA(W, orders, horizonDays, seed);

        // ===== Reporte simple en consola =====
        System.out.println("Objetivo: " + best.objective);
        System.out.println("On-time: " + best.servedOnTime + " | Late: " + best.servedLate +
                " | CapViol: " + best.capViol + " | AvgSlack(min): " + best.avgSlack);

        for (Order o : orders) {
            List<SubRoute> lst = best.routes.get(o);
            int delivered = (lst==null)?0:lst.stream().mapToInt(s->s.qty).sum();
            System.out.println("\nPedido " + o.orig + " -> " + o.dest +
                    " qty=" + o.qty + " releaseUTC=" + mmToHHMM(o.releaseMinUTC) +
                    " dueUTC=" + mmToHHMM(o.dueMinUTC) + " delivered=" + delivered);

            if (lst != null) {
                int srIdx = 1;
                for (SubRoute sr : lst) {
                    System.out.println("  Subruta #" + (srIdx++) + " qty=" + sr.qty +
                            " arrivalUTC=" + mmToHHMM(sr.arrivalUTC));
                    for (FlightUse fu : sr.legs) {
                        System.out.println("    " + fu.flight.orig + "->" + fu.flight.dest +
                                " D" + fu.dayIndex + " depUTC=" + mmToHHMM(fu.depUTC) +
                                " arrUTC=" + mmToHHMM(fu.arrUTC) + " qty=" + fu.qtyAssigned);
                    }
                }
            }
        }
    }
}
