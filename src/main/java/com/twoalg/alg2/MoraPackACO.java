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
    static final int MINUTOS_CONEXION_MINIMA = 60;
    static final Duration SLA_MISMO_CONTINENTE = Duration.ofDays(2);
    static final Duration SLA_CONTINENTES_DISTINTOS = Duration.ofDays(3);
    static final Set<String> CODIGOS_HUBS = Set.of("SPIM","EBCI","UBBB"); // Lima, Bruselas, Bakú

    // =========================
    // ACO (ACS: Ant Colony System)
    // =========================
    static int NUM_HORMIGAS = 90;
    static int MAX_ITERACIONES = 240;

    static double PESO_FEROMONA = 1.2;              // (α) peso de feromona
    static double PESO_HEURISTICA = 5.0;            // (β) peso de la heurística
    static double TASA_EVAPORACION_GLOBAL = 0.06;   // (ρ) evaporación global
    static double TASA_ACTUALIZACION_LOCAL = 0.10;  // (φ) actualización local
    static double FEROMONA_INICIAL = 0.01;          // τ0 feromona inicial
    static double INTENSIDAD_REFUERZO = 1.5;        // Q refuerzo global
    static double FRACCION_REFUERZO_ELITE = 0.35;   // porción del refuerzo para el mejor de iteración
    static double PROBABILIDAD_EXPLOTAR = 0.20;     // q0 (ACS): prob. de explotar (elegir el mejor candidato)

    // Límites tipo MMAS (suaves)
    static double FEROMONA_MIN = 1e-6;
    static double FEROMONA_MAX = 10.0;

    // =========================
    // RESTRICCIONES DE RUTA
    // =========================
    static final int MAX_ESCALAS = 4;
    static final int MAX_VISITAS_POR_AEROPUERTO = 1;
    static final int PACIENCIA_ESTANCAMIENTO = 50;
    static final boolean EVITAR_RETROCESO = true;

    // Activar/desactivar reglas de continentes
    static final boolean APLICAR_REGLAS_CONTINENTE = true;

    // =========================
    // SPLIT DE PEDIDOS / CORTE SLA
    // =========================
    static final int TAM_MAX_SUBPEDIDO = 150;
    static final int TOLERANCIA_RETRASO_MINUTOS = 90;

    // =========================
    // LISTA CANDIDATA / BÚSQUEDA
    // =========================
    static final int TAMANIO_LISTA_CANDIDATOS = 8;

    // =========================
    // ANALÍTICA / ROBUSTEZ
    // =========================
    static final int SLACK_CRITICO_MINUTOS = 120;
    static final int COLCHON_SEGURIDAD_MINUTOS = 0;

    // =========================
    // CLASES
    // =========================
    static class Aeropuerto {
        String codigo, ciudad, pais, continente;
        int desfaseGMT, capacidadAlmacen;
        double lat = Double.NaN, lon = Double.NaN;
    }
    static class Vuelo {
        String origen, destino, horaOrigen, horaDestino;
        int capacidad;
        long salidaUTC, llegadaUTC;
        public String toString(){ return origen+"->"+destino+"("+horaOrigen+"-"+horaDestino+")"; }
    }
    static class Pedido {
        int id;
        String origen, destino;
        int cantidad;
        long liberacionUTC, vencimientoUTC; 
        int idPedidoOriginal = -1;
        int indiceSubpedido = 1;    
        int totalSubpedidos = 1;  
    }
    static class Segmento {
        Vuelo vuelo;
        long salidaAjustadaUTC;  
        long llegadaAjustadaUTC;
        Segmento(Vuelo v, long s, long l){ this.vuelo=v; this.salidaAjustadaUTC=s; this.llegadaAjustadaUTC=l; }
    }
    static class Ruta {
        List<Segmento> segmentos = new ArrayList<>();
        long llegadaFinalUTC;
        boolean aTiempo;
    }
    static class Instancia {
        Map<String, Aeropuerto> aeropuertos = new HashMap<>();
        List<Vuelo> vuelos = new ArrayList<>();
        Map<String,List<Vuelo>> vuelosPorOrigen = new HashMap<>();
        List<Pedido> pedidos = new ArrayList<>();
        LocalDate fechaAncla;
        int diasMes;

        Map<String,Integer> indiceAeropuerto = new HashMap<>();
        String[] indiceAAeropuerto;
        int[][] distanciaSaltos;  
        int normalizadorSaltos = 1;
    }
    static class Solucion {
        Map<Integer,Ruta> rutas = new HashMap<>();
        int subpedidosATiempo=0, subpedidosTarde=0, violacionesCapacidad=0;
        double valorObjetivo=0;
    }
    static class ResumenPedidoOriginal {
        int idPedidoOriginal, totalCantidad, subpedidos, subpedidosATiempo;
        boolean pedidoCompletoATiempo;
    }

    // =========================
    // FEROMONA
    // =========================
    static String claveVuelo(Vuelo v){ return v.origen+"|"+v.destino+"|"+v.horaOrigen+"|"+v.horaDestino; }
    static Map<String, Double> feromona = new HashMap<>();
    static Random azar = new Random();

    // =========================
    // UTILIDADES DE TIEMPO
    // =========================
    static long aUTC(String hhmm, int gmt, LocalDate ancla){
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("HH:mm");
        LocalTime lt = LocalTime.parse(hhmm, fmt);
        LocalDateTime localDT = LocalDateTime.of(ancla, lt);
        int minutos = gmt * 60;
        return localDT.toEpochSecond(ZoneOffset.ofTotalSeconds(minutos*60))/60L;
    }
    static boolean mismoContinente(Aeropuerto a, Aeropuerto b){
        return a!=null && b!=null && Objects.equals(a.continente,b.continente);
    }
    static double distanciaHaversine(double lat1,double lon1,double lat2,double lon2){
        double R=6371.0, dLat=Math.toRadians(lat2-lat1), dLon=Math.toRadians(lon2-lon1);
        double a=Math.sin(dLat/2)*Math.sin(dLat/2)+
                Math.cos(Math.toRadians(lat1))*Math.cos(Math.toRadians(lat2))*Math.sin(dLon/2)*Math.sin(dLon/2);
        double c=2*Math.atan2(Math.sqrt(a),Math.sqrt(1-a));
        return R*c;
    }
    static String hubParaContinente(String continente){
        switch (continente){
            case "SA": return "SPIM";
            case "EU": return "EBCI";
            case "AS": return "UBBB";
            default:   return "SPIM";
        }
    }
    static String continente(String apt, Instancia inst){
        Aeropuerto a = inst.aeropuertos.get(apt);
        return a==null? null : a.continente;
    }
    // Progreso geográfico si hay lat/lon; si no, usa fallback por saltos
    static double progresoHaciaDestino(String actual, String destino, Instancia inst){
        Aeropuerto a=inst.aeropuertos.get(actual), b=inst.aeropuertos.get(destino);
        if (a==null||b==null) return 0.0;

        boolean geoOK = !Double.isNaN(a.lat) && !Double.isNaN(a.lon) && !Double.isNaN(b.lat) && !Double.isNaN(b.lon);
        if (geoOK){
            double d=distanciaHaversine(a.lat,a.lon,b.lat,b.lon);
            return Math.max(0.0, Math.min(1.0, 1.0 - d/15000.0));
        } else {
            Integer ia = inst.indiceAeropuerto.get(actual), ib = inst.indiceAeropuerto.get(destino);
            if (ia==null || ib==null) return 0.0;
            int d = inst.distanciaSaltos[ia][ib];
            if (d >= 1_000_000) return 0.0; // inalcanzable
            double norm = Math.max(1.0, inst.normalizadorSaltos);
            return Math.max(0.0, Math.min(1.0, 1.0 - d / norm));
        }
    }
    static int dayIndexLocal(long utcMin, int gmt, LocalDate ancla){
        long base = ancla.atStartOfDay().toEpochSecond(ZoneOffset.ofHours(gmt))/60L;
        long diff = utcMin - base;
        return (int)Math.floorDiv(diff, 1440L);
    }
    static String fmtLocalDHHMM(long utcMin, Aeropuerto apt, LocalDate ancla){
        int d = dayIndexLocal(utcMin, apt.desfaseGMT, ancla);
        OffsetDateTime odt = Instant.ofEpochSecond(utcMin*60L).atOffset(ZoneOffset.ofHours(apt.desfaseGMT));
        return String.format("D-%d %s", d, odt.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm")));
    }

    static String claveCapacidadVueloDia(Vuelo v, long salidaAjustada){
        long base = v.salidaUTC;
        int indiceDia = (int)Math.floorDiv(salidaAjustada - base, 1440L);
        return claveVuelo(v) + "|" + indiceDia;
    }

    // =========================
    // HEURÍSTICA
    // =========================
    static double evaluarHeuristica(
            Pedido pedido, String aeropuertoActual, long tiempoActualUTC,
            Vuelo vuelo, Map<String,Integer> capacidadUsadaLocalVueloDia, Map<String,Integer> capacidadUsadaGlobalVueloDia,
            Instancia inst, long[] salidaLlegadaAjustadas) {

        if (!vuelo.origen.equals(aeropuertoActual)) return 0;

        long salida = vuelo.salidaUTC, llegada = vuelo.llegadaUTC;
        while (salida < tiempoActualUTC + MINUTOS_CONEXION_MINIMA) { salida += 1440; llegada += 1440; }

        salidaLlegadaAjustadas[0] = salida;
        salidaLlegadaAjustadas[1] = llegada;

        if (llegada > pedido.vencimientoUTC + TOLERANCIA_RETRASO_MINUTOS) return 0;

        String claveDia = claveCapacidadVueloDia(vuelo, salida);
        int usado = capacidadUsadaGlobalVueloDia.getOrDefault(claveDia,0) + capacidadUsadaLocalVueloDia.getOrDefault(claveDia,0);
        int capacidadDisponible = vuelo.capacidad - usado;
        if (capacidadDisponible < pedido.cantidad) return 0;

        double hCap   = capacidadDisponible / (double) vuelo.capacidad;
        double hProg  = progresoHaciaDestino(vuelo.destino, pedido.destino, inst);
        double espera = Math.max(0, salida - tiempoActualUTC);
        double hWait  = 1.0 / (1.0 + espera/45.0);

        double slackBruto = pedido.vencimientoUTC - llegada;
        double slackAjustado = slackBruto - COLCHON_SEGURIDAD_MINUTOS;
        double hSlack = slackAjustado >= 0 ? 1.0 : Math.exp(slackAjustado/60.0);

        double eta = 0.20*hCap + 0.45*hProg + 0.20*hWait + 0.15*hSlack;
        return Math.max(0, eta);
    }

    // =========================
    // CONSTRUCCIÓN DE RUTA
    // =========================
    static Ruta construirRutaParaPedido(Pedido pedido, Instancia inst, Map<String,Integer> capacidadUsadaGlobalVueloDia){
        String actual = pedido.origen, previo = null;
        long tiempoActual = pedido.liberacionUTC;
        Map<String,Integer> capacidadUsadaLocalVueloDia = new HashMap<>();
        Map<String,Integer> visitasPorAeropuerto = new HashMap<>();
        visitasPorAeropuerto.put(actual, 1);
        Ruta ruta = new Ruta();
        int hops = 0;

        String contOrig = continente(pedido.origen, inst);
        String contDest = continente(pedido.destino, inst);
        boolean mismoCont = Objects.equals(contOrig, contDest);

        while(!actual.equals(pedido.destino) && hops < MAX_ESCALAS){
            List<Vuelo> candidatosDesdeActual = inst.vuelosPorOrigen.getOrDefault(actual, List.of());
            if (candidatosDesdeActual.isEmpty()) return null;

            class CandidatoVuelo { Vuelo v; double valor; long salida; long llegada; }
            PriorityQueue<CandidatoVuelo> pq = new PriorityQueue<>(Comparator.comparingDouble(c -> -c.valor));

            String contActual = continente(actual, inst);

            for (Vuelo v: candidatosDesdeActual){
                int visitasDestino = visitasPorAeropuerto.getOrDefault(v.destino,0);
                if (visitasDestino >= MAX_VISITAS_POR_AEROPUERTO) continue;
                if (EVITAR_RETROCESO && previo != null && v.destino.equals(previo)) continue;

                if (APLICAR_REGLAS_CONTINENTE){
                    String contSiguiente = continente(v.destino, inst);
                    if (contSiguiente == null) continue;

                    if (mismoCont) {
                        if (!Objects.equals(contSiguiente, contDest)) continue;
                    } else {
                        if (Objects.equals(contActual, contDest)) {
                            if (!Objects.equals(contSiguiente, contDest)) continue;
                        } else if (Objects.equals(contActual, contOrig)) {
                            if (!(Objects.equals(contSiguiente, contOrig) || Objects.equals(contSiguiente, contDest))) continue;
                        } else {
                            if (!(Objects.equals(contSiguiente, contActual) || Objects.equals(contSiguiente, contDest))) continue;
                        }
                    }
                }

                long[] tiempos = new long[2];
                double eta = evaluarHeuristica(pedido, actual, tiempoActual, v, capacidadUsadaLocalVueloDia, capacidadUsadaGlobalVueloDia, inst, tiempos);
                if (eta <= 0) continue;

                double t = feromona.getOrDefault(claveVuelo(v), FEROMONA_INICIAL);
                double valor = Math.pow(Math.max(FEROMONA_MIN, Math.min(FEROMONA_MAX, t)), PESO_FEROMONA) * Math.pow(eta, PESO_HEURISTICA);

                CandidatoVuelo c = new CandidatoVuelo(); c.v = v; c.valor = valor; c.salida=tiempos[0]; c.llegada=tiempos[1];
                pq.offer(c);
            }
            if (pq.isEmpty()) return null;

            List<CandidatoVuelo> listaCandidatos = new ArrayList<>();
            for (int i=0; i<TAMANIO_LISTA_CANDIDATOS && !pq.isEmpty(); i++) listaCandidatos.add(pq.poll());

            CandidatoVuelo elegido = null;
            if (!listaCandidatos.isEmpty()){
                if (azar.nextDouble() < PROBABILIDAD_EXPLOTAR){
                    elegido = listaCandidatos.get(0);
                } else {
                    double sum = 0.0;
                    for (CandidatoVuelo c: listaCandidatos) sum += c.valor;
                    double r = azar.nextDouble() * sum;
                    for (int i=0;i<listaCandidatos.size();i++){
                        r -= listaCandidatos.get(i).valor;
                        if (r <= 0 || i==listaCandidatos.size()-1){ elegido = listaCandidatos.get(i); break; }
                    }
                }
            }
            if (elegido == null) return null;

            String k = claveVuelo(elegido.v);
            double tVal = feromona.getOrDefault(k, FEROMONA_INICIAL);
            double nuevo = (1.0 - TASA_ACTUALIZACION_LOCAL) * tVal + TASA_ACTUALIZACION_LOCAL * FEROMONA_INICIAL;
            feromona.put(k, limitarEntre(nuevo, FEROMONA_MIN, FEROMONA_MAX));
            String claveDia = claveCapacidadVueloDia(elegido.v, elegido.salida);
            capacidadUsadaLocalVueloDia.put(claveDia, capacidadUsadaLocalVueloDia.getOrDefault(claveDia,0) + pedido.cantidad);

            ruta.segmentos.add(new Segmento(elegido.v, elegido.salida, elegido.llegada));

            String anterior = actual;
            actual = elegido.v.destino;
            previo = anterior;
            tiempoActual = elegido.llegada;
            visitasPorAeropuerto.put(actual, visitasPorAeropuerto.getOrDefault(actual,0)+1);
            hops++;
        }

        if (!actual.equals(pedido.destino)) return null;
        ruta.llegadaFinalUTC = tiempoActual;
        return ruta;
    }

    // =========================
    // CONSTRUCCIÓN DE SOLUCIÓN 
    // =========================
    static Solucion construirSolucionGlobal(Instancia inst){
        Solucion sol = new Solucion();

        Map<String,Integer> capacidadUsadaGlobalVueloDia = new HashMap<>();

        int capacidadTotalDiaria = capacidadTotalDiaria(inst);
        Map<Integer,Integer> capacidadUsadaGlobalDia = new HashMap<>();

        List<Pedido> pedidos = new ArrayList<>(inst.pedidos);
        pedidos.sort(Comparator.<Pedido>comparingLong(p -> p.vencimientoUTC).thenComparingInt(p -> -p.cantidad));

        for (Pedido p: pedidos){
            Ruta r = construirRutaParaPedido(p, inst, capacidadUsadaGlobalVueloDia);
            if (r == null) { sol.subpedidosTarde++; continue; }

            Map<Integer,Integer> aAgregarPorDia = new HashMap<>();
            boolean okDia = true;
            for (Segmento s: r.segmentos){
                int indiceDia = indiceDiaDeSalida(s);
                int add = aAgregarPorDia.getOrDefault(indiceDia, 0) + p.cantidad; 
                if (capacidadUsadaGlobalDia.getOrDefault(indiceDia, 0) + add > capacidadTotalDiaria) {
                    okDia = false; break;
                }
                aAgregarPorDia.put(indiceDia, add);
            }
            if (!okDia){ sol.subpedidosTarde++; continue; }

            r.aTiempo = (r.llegadaFinalUTC <= p.vencimientoUTC);
            if (r.aTiempo) sol.subpedidosATiempo++; else sol.subpedidosTarde++;
            sol.rutas.put(p.id, r);

            for (Map.Entry<Integer,Integer> e : aAgregarPorDia.entrySet()){
                int d = e.getKey();
                capacidadUsadaGlobalDia.put(d, capacidadUsadaGlobalDia.getOrDefault(d,0) + e.getValue());
            }
            for (Segmento s: r.segmentos){
                String claveDia = claveCapacidadVueloDia(s.vuelo, s.salidaAjustadaUTC);
                int nuevo = capacidadUsadaGlobalVueloDia.getOrDefault(claveDia,0) + p.cantidad;
                if (nuevo > s.vuelo.capacidad) sol.violacionesCapacidad++;
                capacidadUsadaGlobalVueloDia.put(claveDia, nuevo);
            }
        }

        long productoATiempo = inst.pedidos.stream()
                .filter(p -> sol.rutas.get(p.id)!=null && sol.rutas.get(p.id).aTiempo)
                .mapToLong(p -> p.cantidad).sum();
        long productoTarde = inst.pedidos.stream()
                .filter(p -> sol.rutas.get(p.id)==null || !sol.rutas.get(p.id).aTiempo)
                .mapToLong(p -> p.cantidad).sum();

        sol.valorObjetivo = productoATiempo - 3*productoTarde - 5*sol.violacionesCapacidad;
        return sol;
    }

    static int indiceDiaDeSalida(Segmento s){
        long base = s.vuelo.salidaUTC;
        return (int)Math.floorDiv(s.salidaAjustadaUTC - base, 1440L);
    }

    static int capacidadTotalDiaria(Instancia inst){
        int sum = 0;
        for (Vuelo v : inst.vuelos) sum += v.capacidad;
        return sum;
    }

    // =========================
    // ACO PRINCIPAL
    // =========================
    static Solucion ejecutarACO(Instancia inst){
        feromona.clear();
        for (Vuelo f: inst.vuelos) feromona.putIfAbsent(claveVuelo(f), FEROMONA_INICIAL);

        Solucion mejorGlobal = null;
        double mejorValorObj = -1e18;
        int iterSinMejora = 0;

        for (int it=0; it<MAX_ITERACIONES; it++){
            List<Solucion> soluciones = new ArrayList<>(NUM_HORMIGAS);
            for (int k=0; k<NUM_HORMIGAS; k++) soluciones.add(construirSolucionGlobal(inst));
            soluciones.sort(Comparator.comparingDouble(s->-s.valorObjetivo));
            Solucion mejorIteracion = soluciones.get(0);

            for (String k: feromona.keySet()){
                double nv = (1.0 - TASA_EVAPORACION_GLOBAL) * feromona.get(k);
                feromona.put(k, limitarEntre(nv, FEROMONA_MIN, FEROMONA_MAX));
            }

            aplicarRefuerzoFeromonas(mejorIteracion, INTENSIDAD_REFUERZO * FRACCION_REFUERZO_ELITE, inst);
            if (mejorGlobal != null) aplicarRefuerzoFeromonas(mejorGlobal, INTENSIDAD_REFUERZO, inst);

            if (mejorGlobal == null || mejorIteracion.valorObjetivo > mejorValorObj){
                mejorGlobal = mejorIteracion; mejorValorObj = mejorIteracion.valorObjetivo; iterSinMejora = 0;
            } else iterSinMejora++;

            if (iterSinMejora >= PACIENCIA_ESTANCAMIENTO){
                for (String k: feromona.keySet()) feromona.put(k, FEROMONA_INICIAL);
                iterSinMejora = 0;
            }
        }
        return mejorGlobal;
    }

    static void aplicarRefuerzoFeromonas(Solucion sol, double q, Instancia inst){
        if (sol == null) return;

        Map<Integer,Integer> cantidadPorId = new HashMap<>();
        for (Pedido p : inst.pedidos) cantidadPorId.put(p.id, p.cantidad);

        for (Map.Entry<Integer, Ruta> eR : sol.rutas.entrySet()){
            int chunkId = eR.getKey();
            Ruta r = eR.getValue();
            int cantidad = cantidadPorId.getOrDefault(chunkId, 1);
            double bonusBase = r.aTiempo ? q : q * 0.1;
            double bonus = bonusBase * Math.max(1, cantidad); // ponderar por producto

            for (Segmento s: r.segmentos){
                String kk = claveVuelo(s.vuelo);
                double nv = feromona.getOrDefault(kk, FEROMONA_INICIAL) + bonus;
                feromona.put(kk, limitarEntre(nv, FEROMONA_MIN, FEROMONA_MAX));
            }
        }
    }

    static double limitarEntre(double v, double lo, double hi){ return Math.max(lo, Math.min(hi, v)); }

    // =========================
    // CARGA DE INSUMOS
    // =========================
    static Map<String,Aeropuerto> cargarTablaAeropuertos(Path path) throws IOException {
        List<String> lineas = Files.readAllLines(path, Charset.forName("UTF-16"));
        Map<String,Aeropuerto> map = new HashMap<>();
        String continente = "SA";
        Pattern fila = Pattern.compile("^\\s*\\d+\\s+([A-Z0-9]{3,4})\\s+(.+?)\\s+(.+?)\\s+[A-Za-z]{4}\\s+([+-]?\\d+)\\s+(\\d+).*$");

        for (String ln : lineas) {
            String l = ln.trim(); if (l.isEmpty()) continue;
            String ll = l.toLowerCase(Locale.ROOT);
            if (ll.contains("américa del sur") || ll.contains("america del sur")) { continente = "SA"; continue; }
            if (ll.contains("europa")) { continente = "EU"; continue; }
            if (ll.contains("asia"))   { continente = "AS"; continue; }

            Matcher m = fila.matcher(ln);
            if (m.matches()) {
                Aeropuerto a = new Aeropuerto();
                a.codigo = m.group(1).trim();
                a.ciudad = m.group(2).trim().replaceAll("\\s+", " ");
                a.pais   = m.group(3).trim().replaceAll("\\s+", " ");
                a.desfaseGMT = Integer.parseInt(m.group(4).trim());
                a.capacidadAlmacen = 100_000_000; // muy grande para este análisis
                a.continente = continente;
                map.put(a.codigo, a);
            }
        }
        return map;
    }

    static List<Vuelo> cargarTablaVuelos(Path path, Map<String,Aeropuerto> aeropuertos, LocalDate ancla) throws IOException {
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
            if (ao == null || ad == null) { continue; }

            v.salidaUTC = aUTC(v.horaOrigen, ao.desfaseGMT, ancla);
            v.llegadaUTC = aUTC(v.horaDestino, ad.desfaseGMT, ancla);
            long duracion = v.llegadaUTC - v.salidaUTC;
            while (duracion <= 0) {
                v.llegadaUTC += 24*60;
                duracion = v.llegadaUTC - v.salidaUTC;
            }
            lista.add(v);
        }
        return lista;
    }

    // =========================
    // CARGA DE PEDIDOS TXT
    // =========================
    static List<Pedido> cargarPedidosDesdeArchivo(Path path, Instancia inst, LocalDate ancla) throws IOException {
        List<String> lineas = Files.readAllLines(path);
        List<Pedido> pedidos = new ArrayList<>();
        int idAuto = 1;

        Pattern pat = Pattern.compile("^(\\d{2})-(\\d{2})-(\\d{2})-([A-Z0-9]{3,4})-(\\d{3})-(\\d{7})$");

        for (String ln : lineas) {
            String l = ln.trim();
            if (l.isEmpty() || l.startsWith("#")) continue;

            Matcher m = pat.matcher(l);
            if (!m.matches()) { continue; }

            int dd = Integer.parseInt(m.group(1));
            int hh = Integer.parseInt(m.group(2));
            int mm = Integer.parseInt(m.group(3));
            String dest = m.group(4);
            int qty = Integer.parseInt(m.group(5));

            if (dd < 1 || dd > 24) { continue; }
            if (hh < 1 || hh > 23) { continue; }
            if (mm < 1 || mm > 59) { continue; }
            if (qty < 1 || qty > 999) { continue; }

            Aeropuerto aDest = inst.aeropuertos.get(dest);
            if (aDest == null) { continue; }

            String origen;
            if (azar.nextDouble() < 0.3) {
                List<String> hubs = new ArrayList<>(CODIGOS_HUBS);
                hubs.remove(hubParaContinente(aDest.continente));
                origen = hubs.get(azar.nextInt(hubs.size()));
            } else {
                origen = hubParaContinente(aDest.continente);
            }

            int offsetSecDest = aDest.desfaseGMT * 3600;
            long liberacionUTC = ancla.atStartOfDay()
                    .plusDays(dd)
                    .withHour(hh).withMinute(mm)
                    .toEpochSecond(ZoneOffset.ofTotalSeconds(offsetSecDest)) / 60;

            boolean same = mismoContinente(inst.aeropuertos.get(origen), aDest);
            long vencimientoUTC = liberacionUTC + (same ? SLA_MISMO_CONTINENTE.toMinutes() : SLA_CONTINENTES_DISTINTOS.toMinutes());

            Pedido p = new Pedido();
            p.id = idAuto++;
            p.origen = origen;
            p.destino = dest;
            p.cantidad = qty;
            p.liberacionUTC = liberacionUTC;
            p.vencimientoUTC = vencimientoUTC;

            pedidos.add(p);
        }
        return pedidos;
    }

    // =========================
    // SPLIT DE PEDIDOS EN SUBPEDIDOS
    // =========================
   static List<Pedido> dividirPedidosEnSubpedidos(List<Pedido> pedidos, Instancia inst, int siguienteIdInicio) {
        List<Pedido> out = new ArrayList<>();
        int nextId = siguienteIdInicio;

        for (Pedido p : pedidos) {
            int capMin = capacidadVueloMinimoDesde(inst, p.origen, p.destino);
            int tamanioSubpedido = Math.max(1, capMin);  // nunca menor que 1
            int K = (int) Math.ceil(p.cantidad / (double) tamanioSubpedido);

            int rem = p.cantidad;

            if (K <= 1) {
                out.add(p);
                continue;
            }
            for (int i = 1; i <= K; i++) {
                Pedido c = new Pedido();
                c.id = nextId++;
                c.origen = p.origen;
                c.destino = p.destino;
                c.cantidad = Math.min(rem, tamanioSubpedido);
                c.liberacionUTC = p.liberacionUTC;
                c.vencimientoUTC = p.vencimientoUTC;
                c.idPedidoOriginal = (p.idPedidoOriginal == -1 ? p.id : p.idPedidoOriginal);
                c.indiceSubpedido = i;
                c.totalSubpedidos = K;
                out.add(c);
                rem -= c.cantidad;
            }
        }
        return out;
    }

    static Map<Integer, ResumenPedidoOriginal> agruparEstadisticasPorPedidoOriginal(List<Pedido> todos, Solucion sol) {
        Map<Integer, ResumenPedidoOriginal> agg = new HashMap<>();
        for (Pedido c : todos) {
            int pid = (c.idPedidoOriginal == -1 ? c.id : c.idPedidoOriginal);
            ResumenPedidoOriginal a = agg.computeIfAbsent(pid, k -> {
                ResumenPedidoOriginal x = new ResumenPedidoOriginal();
                x.idPedidoOriginal = pid; return x;
            });
            a.totalCantidad += c.cantidad;
            a.subpedidos += 1;
            Ruta r = sol.rutas.get(c.id);
            if (r != null && r.aTiempo) a.subpedidosATiempo += 1;
        }
        for (ResumenPedidoOriginal a : agg.values()) a.pedidoCompletoATiempo = (a.subpedidosATiempo == a.subpedidos);
        return agg;
    }
    // =========================
    // PRECÁLCULO DE DISTANCIAS EN SALTOS
    // =========================
    static void precomputarDistanciasPorSaltos(Instancia inst){
        int n = inst.aeropuertos.size();
        inst.indiceAeropuerto.clear();
        inst.indiceAAeropuerto = new String[n];
        int idx = 0;
        for (String code : inst.aeropuertos.keySet()){
            inst.indiceAeropuerto.put(code, idx);
            inst.indiceAAeropuerto[idx] = code;
            idx++;
        }
        inst.distanciaSaltos = new int[n][n];
        for (int i=0;i<n;i++) Arrays.fill(inst.distanciaSaltos[i], 1_000_000);

        Map<Integer, List<Integer>> adj = new HashMap<>();
        for (Map.Entry<String,List<Vuelo>> e : inst.vuelosPorOrigen.entrySet()){
            Integer u = inst.indiceAeropuerto.get(e.getKey());
            if (u==null) continue;
            List<Integer> lst = adj.computeIfAbsent(u, k-> new ArrayList<>());
            for (Vuelo v : e.getValue()){
                Integer w = inst.indiceAeropuerto.get(v.destino);
                if (w!=null) lst.add(w);
            }
        }

        int hopNorm = 1;
        for (int s=0;s<n;s++){
            int[] dist = inst.distanciaSaltos[s];
            dist[s] = 0;
            ArrayDeque<Integer> q = new ArrayDeque<>();
            q.add(s);
            while(!q.isEmpty()){
                int u = q.poll();
                for (int v : adj.getOrDefault(u, List.of())){
                    if (dist[v] > dist[u] + 1){
                        dist[v] = dist[u] + 1;
                        hopNorm = Math.max(hopNorm, dist[v]);
                        q.add(v);
                    }
                }
            }
        }
        inst.normalizadorSaltos = hopNorm;
    }


    // =========================
    // REPORTES
    // =========================
    static class RouteStats {
        int hops;
        long esperaTotal;
        long vueloTotal;
        long slackFinal;
        boolean onTime;
    }

    static RouteStats calcularStatsRuta(Pedido p, Ruta r){
        RouteStats st = new RouteStats();
        if (r == null) { st.onTime=false; st.slackFinal = Long.MIN_VALUE; return st; }
        long t = p.liberacionUTC;
        for (Segmento s : r.segmentos){
            long espera = Math.max(0, s.salidaAjustadaUTC - t);
            long vuelo = Math.max(0, s.llegadaAjustadaUTC - s.salidaAjustadaUTC);
            st.esperaTotal += espera;
            st.vueloTotal += vuelo;
            st.hops++;
            t = s.llegadaAjustadaUTC;
        }
        st.slackFinal = p.vencimientoUTC - r.llegadaFinalUTC;
        st.onTime = r.aTiempo;
        return st;
    }

    static int capacidadVueloMinimoDesde(Instancia inst, String origen, String destino) {
        int capMin = Integer.MAX_VALUE;
        for (Vuelo v : inst.vuelos) {
            if (v.origen.equals(origen) && v.destino.equals(destino)) {
                capMin = Math.min(capMin, v.capacidad);
            }
        }
        if (capMin == Integer.MAX_VALUE) {
            for (Vuelo v : inst.vuelos) {
                if (v.origen.equals(origen)) {
                    capMin = Math.min(capMin, v.capacidad);
                }
            }
        }
        if (capMin == Integer.MAX_VALUE) {
            for (Vuelo v : inst.vuelos) {
                if (v.destino.equals(destino)) {
                    capMin = Math.min(capMin, v.capacidad);
                }
            }
        }
        if (capMin == Integer.MAX_VALUE) capMin = 150;

        return capMin;
    }


    static void mostrarPlanificacionPorPedido(Instancia inst, Solucion sol){
        System.out.println();
        System.out.println("=============== REPORTE DE PLANIFICACION POR PEDIDO ===============");

        Map<Integer, List<Pedido>> pedidosPorOriginal = new HashMap<>();
        for (Pedido p : inst.pedidos) {
            int pid = (p.idPedidoOriginal == -1 ? p.id : p.idPedidoOriginal);
            pedidosPorOriginal.computeIfAbsent(pid, k -> new ArrayList<>()).add(p);
        }

        for (Map.Entry<Integer, List<Pedido>> e : pedidosPorOriginal.entrySet()) {
            List<Pedido> lista = e.getValue();
            lista.sort(Comparator.comparingInt(p -> p.indiceSubpedido));
            Pedido base = lista.get(0);

            Aeropuerto apOri = inst.aeropuertos.get(base.origen);
            Aeropuerto apDes = inst.aeropuertos.get(base.destino);
            int totalCantidad = lista.stream().mapToInt(p -> p.cantidad).sum();

            System.out.println("-------------------------------------------------------------------");
            System.out.printf("PEDIDO %d%n", e.getKey());
            System.out.printf("   Origen: %s   ->   Destino: %s%n", base.origen, base.destino);
            System.out.printf("   Cantidad total: %d unidades   |   Subpedidos: %d%n", totalCantidad, lista.size());
            System.out.printf("   Hora de liberacion: %s%n", fmtLocalDHHMM(base.liberacionUTC, apOri, inst.fechaAncla));
            System.out.printf("   Plazo maximo de entrega: %s%n", fmtLocalDHHMM(base.vencimientoUTC, apDes, inst.fechaAncla));
            System.out.println("-------------------------------------------------------------------");

            int cumplidos = 0;
            List<Long> holguras = new ArrayList<>();

            for (Pedido p : lista) {
                Ruta r = sol.rutas.get(p.id);
                System.out.printf("   Subpedido %d/%d  |  Cantidad: %d%n",
                        p.indiceSubpedido, p.totalSubpedidos, p.cantidad);

                if (r == null) {
                    System.out.println("      SIN RUTA (no se pudo cumplir restricciones, capacidad o plazo)");
                    System.out.println("-------------------------------------------------------------------");
                    continue;
                }

                long t = p.liberacionUTC;
                for (int i = 0; i < r.segmentos.size(); i++) {
                    Segmento s = r.segmentos.get(i);
                    Aeropuerto aO = inst.aeropuertos.get(s.vuelo.origen);
                    Aeropuerto aD = inst.aeropuertos.get(s.vuelo.destino);
                    long espera = Math.max(0, s.salidaAjustadaUTC - t);
                    long dur = Math.max(0, s.llegadaAjustadaUTC - s.salidaAjustadaUTC);
                    long holguraRestante = p.vencimientoUTC - s.llegadaAjustadaUTC;

                    System.out.printf("      Tramo %d: %s -> %s%n", (i+1), s.vuelo.origen, s.vuelo.destino);
                    System.out.printf("         Sale: %s   |   Llega: %s%n",
                            fmtLocalDHHMM(s.salidaAjustadaUTC, aO, inst.fechaAncla),
                            fmtLocalDHHMM(s.llegadaAjustadaUTC, aD, inst.fechaAncla));
                    System.out.printf("         Espera: %d min   |   Vuelo: %d min   |   Tiempo extra: %+d min%n",
                            espera, dur, holguraRestante);

                    t = s.llegadaAjustadaUTC;
                }

                RouteStats st = calcularStatsRuta(p, r);
                holguras.add(st.slackFinal);
                if (st.onTime) cumplidos++;
            }

            System.out.println("===================================================================");
        }

        System.out.println("======================= FIN DEL REPORTE DE PLANIFICACION =======================");
    }




    static String formatoPorcentaje(long num, long den){
        if (den <= 0) return "0.0%";
        return String.format(Locale.ROOT, "%.1f%%", 100.0 * num / (double)den);
    }

    // =========================
    // REPORTE PARA EXPERIMENTACIÓN NUMÉRICA
    // =========================
    static void reporteExperimentacion(Instancia inst, Solucion mejor, long elapsedMs){
        long totalPedidos = (int) inst.pedidos.stream()
                .map(p -> (p.idPedidoOriginal == -1 ? p.id : p.idPedidoOriginal))
                .distinct().count();

        long totalSubpedidos = inst.pedidos.size();
        long productoTotal = inst.pedidos.stream().mapToLong(p -> p.cantidad).sum();

        long onTimeSub = mejor.subpedidosATiempo;
        long productoATiempo = inst.pedidos.stream()
                .filter(p -> mejor.rutas.get(p.id) != null && mejor.rutas.get(p.id).aTiempo)
                .mapToLong(p -> p.cantidad).sum();

        long pedidosATiempo = agruparEstadisticasPorPedidoOriginal(inst.pedidos, mejor)
                .values().stream().filter(r -> r.pedidoCompletoATiempo).count();

        System.out.println();
        System.out.println("══════════════════════════════════════════════════════════════════════════════════════════════");
        System.out.println("                     REPORTE DE EXPERIMENTACIÓN NUMÉRICA");
        System.out.println("══════════════════════════════════════════════════════════════════════════════════════════════");

        System.out.println("+ Información del experimento");
        System.out.printf("   Algoritmo: ACO (Colonia de Hormigas)%n");
        System.out.printf("   Iteraciones: %d | Hormigas: %d%n", MAX_ITERACIONES, NUM_HORMIGAS);
        System.out.printf("   Parámetros:%n");
        System.out.printf("      Peso feromona (alpha): %.2f%n", PESO_FEROMONA);
        System.out.printf("      Peso heurística (beta): %.2f%n", PESO_HEURISTICA);
        System.out.printf("      Tasa evaporación global (rho): %.2f%n", TASA_EVAPORACION_GLOBAL);
        System.out.printf("      Tasa actualización local (phi): %.2f%n", TASA_ACTUALIZACION_LOCAL);
        System.out.printf("      Probabilidad de explotación (q0): %.2f%n", PROBABILIDAD_EXPLOTAR);
        System.out.printf("   Pedidos=%d | Subpedidos=%d | Producto total=%d%n",
                totalPedidos, totalSubpedidos, productoTotal);

        System.out.println("----------------------------------------------------------------------------------------------");

        System.out.println("+ Métricas principales");
        System.out.printf("   - Fitness (calidad global de la solución): %.2f%n", mejor.valorObjetivo);
        System.out.printf("   - Tiempo de ejecución: %d ms%n", elapsedMs);
        System.out.printf("   - %% de entregas a tiempo (subpedidos): %s%n",
                formatoPorcentaje(onTimeSub, totalSubpedidos));
        System.out.printf("   - %% de entregas a tiempo (productos): %s%n",
                formatoPorcentaje(productoATiempo, productoTotal));
        System.out.printf("   - %% de pedidos completos a tiempo: %s%n",
                formatoPorcentaje(pedidosATiempo, totalPedidos));

        System.out.println("══════════════════════════════════════════════════════════════════════════════════════════════");
    }


    // =========================
    // MAIN
    // =========================
    public static void main(String[] args) throws Exception {
       
        Path archivoAeropuertos = Paths.get("c.1inf54.25.2.Aeropuerto.husos.v1.20250818__estudiantes.txt");
        Path archivoVuelos = Paths.get("c.1inf54.25.2.planes_vuelo.v4.20250818.txt");

        Path archivoPedidos = Paths.get(args.length >= 1 ? args[0] : "pedidos.txt");
        Long seedUsada = null;
        if (args.length >= 2) {
            seedUsada = Long.parseLong(args[1]);
            azar.setSeed(seedUsada);
        }

        LocalDate ancla = LocalDate.now();
        Instancia inst = new Instancia();
        inst.fechaAncla = ancla;
        inst.diasMes = ancla.lengthOfMonth();

        inst.aeropuertos = cargarTablaAeropuertos(archivoAeropuertos);
        inst.vuelos = cargarTablaVuelos(archivoVuelos, inst.aeropuertos, ancla);

        for (Vuelo f : inst.vuelos)
            inst.vuelosPorOrigen.computeIfAbsent(f.origen, k -> new ArrayList<>()).add(f);
        for (List<Vuelo> lst : inst.vuelosPorOrigen.values())
            lst.sort(Comparator.comparingLong(v -> v.salidaUTC));

        precomputarDistanciasPorSaltos(inst);

        List<Pedido> pedidosOriginales = cargarPedidosDesdeArchivo(archivoPedidos, inst, ancla);

        inst.pedidos = dividirPedidosEnSubpedidos(pedidosOriginales, inst, 100000);

        long t0 = System.currentTimeMillis();
        Solucion mejor = ejecutarACO(inst);
        long t1 = System.currentTimeMillis();
        long elapsedMs = (t1 - t0);

        mostrarPlanificacionPorPedido(inst, mejor);
        reporteExperimentacion(inst, mejor, elapsedMs);
    }
}
