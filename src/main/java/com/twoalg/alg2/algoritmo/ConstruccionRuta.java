package alg2.algoritmo;


import alg2.model.*;
import alg2.funcaux.Geo;
import alg2.funcaux.Claves;
import alg2.config.Parametros;

import java.util.*;

import static alg2.config.Parametros.*;

public class ConstruccionRuta {

  public static final Map<String, Double> feromona = new HashMap<>();
  public static final Random azar = new Random();

  public static Ruta construirRutaParaPedido(Pedido pedido, Instancia inst, Map<String,Integer> capGlobal) {
    String actual = pedido.origen, previo = null;
    long tiempoActual = pedido.liberacionUTC;
    Map<String,Integer> capLocal = new HashMap<>();
    Map<String,Integer> visitasPorAeropuerto = new HashMap<>();
    visitasPorAeropuerto.put(actual, 1);
    Ruta ruta = new Ruta();
    int hops = 0;

    String contOrig = Geo.continente(pedido.origen, inst);
    String contDest = Geo.continente(pedido.destino, inst);
    boolean mismoCont = Objects.equals(contOrig, contDest);

    double minCapDisp = Double.POSITIVE_INFINITY;

    while(!actual.equals(pedido.destino) && hops < MAX_ESCALAS){
      List<Vuelo> candidatos = inst.vuelosPorOrigen.getOrDefault(actual, List.of());
      if (candidatos.isEmpty()) return null;

      class C { Vuelo v; double valor; long s; long l; }
      PriorityQueue<C> pq = new PriorityQueue<>(Comparator.comparingDouble(c -> -c.valor));

      String contActual = Geo.continente(actual, inst);

      for (Vuelo v: candidatos){
        int visitasDestino = visitasPorAeropuerto.getOrDefault(v.destino,0);
        if (visitasDestino >= MAX_VISITAS_POR_AEROPUERTO) continue;
        if (EVITAR_RETROCESO && previo != null && v.destino.equals(previo)) continue;

        if (APLICAR_REGLAS_CONTINENTE){
          String contSiguiente = Geo.continente(v.destino, inst);
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

        long[] t = new long[2];
        double eta = Heuristica.evaluarHeuristica(pedido, actual, tiempoActual, v, capLocal, capGlobal, inst, t);
        if (eta <= 0) continue;

        double tau = feromona.getOrDefault(Claves.claveVuelo(v), Parametros.FEROMONA_INICIAL);
        double valor = Math.pow(Math.max(Parametros.FEROMONA_MIN, Math.min(Parametros.FEROMONA_MAX, tau)), Parametros.PESO_FEROMONA)
                     * Math.pow(eta, Parametros.PESO_HEURISTICA);

        C c = new C(); c.v=v; c.valor=valor; c.s=t[0]; c.l=t[1];
        pq.offer(c);
      }
      if (pq.isEmpty()) return null;

      List<C> lista = new ArrayList<>();
      for (int i=0; i<Parametros.TAMANIO_LISTA_CANDIDATOS && !pq.isEmpty(); i++) lista.add(pq.poll());

      C elegido = null;
      if (!lista.isEmpty()){
        if (azar.nextDouble() < Parametros.PROBABILIDAD_EXPLOTAR){
          elegido = lista.get(0);
        } else {
          double sum = 0.0; for (C c: lista) sum += c.valor;
          double r = azar.nextDouble() * sum;
          for (int i=0;i<lista.size();i++){ r -= lista.get(i).valor; if (r<=0 || i==lista.size()-1){ elegido = lista.get(i); break; } }
        }
      }
      if (elegido == null) return null;

      // actualización local
      String k = Claves.claveVuelo(elegido.v);
      double tVal = feromona.getOrDefault(k, Parametros.FEROMONA_INICIAL);
      double nuevo = (1.0 - Parametros.TASA_ACTUALIZACION_LOCAL) * tVal + Parametros.TASA_ACTUALIZACION_LOCAL * Parametros.FEROMONA_INICIAL;
      feromona.put(k, limitarEntre(nuevo, Parametros.FEROMONA_MIN, Parametros.FEROMONA_MAX));

      // capacidad local (día)
      String claveDia = Claves.claveCapacidadVueloDia(elegido.v, elegido.s);
      capLocal.put(claveDia, capLocal.getOrDefault(claveDia,0) + pedido.cantidad);

      // subruta
      SubRuta sr = new SubRuta(elegido.v, elegido.s, elegido.l, pedido.cantidad);
      ruta.subrutas.add(sr);

      int usadoEstimado = capLocal.get(claveDia);
      double disp = Math.max(0, elegido.v.capacidadMaxima - usadoEstimado);
      minCapDisp = Math.min(minCapDisp, disp);

      String anterior = actual;
      actual = elegido.v.destino;
      previo = anterior;
      tiempoActual = elegido.l;
      visitasPorAeropuerto.put(actual, visitasPorAeropuerto.getOrDefault(actual,0)+1);
      hops++;
    }

    if (!actual.equals(pedido.destino)) return null;
    ruta.llegadaFinalUTC = tiempoActual;
    ruta.tiempoTotal = Math.max(0, (ruta.llegadaFinalUTC - pedido.liberacionUTC)/60.0);
    ruta.capacidadMinimaDisponible = (Double.isInfinite(minCapDisp) ? 0.0 : minCapDisp);
    return ruta;
  }

  public static Ruta construirRutaParaProducto(Producto producto, Instancia inst, Map<String,Integer> capGlobal){
    String destino = producto.ciudadDestino;
    long tiempoPedido = producto.fechaPedido / 60000L; // minutos desde epoch
    int cantidad = 1; // cada producto es una unidad
    Ruta mejorRuta = null;
    long mejorLlegada = Long.MAX_VALUE;
    //System.out.println("[DEBUG] Buscando ruta para producto destino=" + producto.ciudadDestino + ", fechaPedido=" + producto.fechaPedido);

    for (String hub : Parametros.CODIGOS_HUBS) {
        String actual = hub;
        Map<String,Integer> capLocal = new HashMap<>();
        Map<String,Integer> visitasPorAeropuerto = new HashMap<>();
        visitasPorAeropuerto.put(actual, 1);
        Ruta ruta = new Ruta();
        //System.out.println("[DEBUG] Probando hub origen: " + hub);
        int hops = 0;

        String contOrig = Geo.continente(actual, inst);
        String contDest = Geo.continente(destino, inst);
        boolean mismoCont = Objects.equals(contOrig, contDest);

        double minCapDisp = Double.POSITIVE_INFINITY;
        long tiempoActual = tiempoPedido;
        String previo = null;

  while(!actual.equals(destino) && hops < MAX_ESCALAS){
          List<Vuelo> candidatos = inst.vuelosPorOrigen.getOrDefault(actual, List.of());
          if (candidatos.isEmpty()) { ruta = null; break; }

          class C { Vuelo v; double valor; long s; long l; }
          PriorityQueue<C> pq = new PriorityQueue<>(Comparator.comparingDouble(c -> -c.valor));
          candidatos = inst.vuelosPorOrigen.getOrDefault(actual, List.of());
          //System.out.println("[DEBUG]  Aeropuerto actual: " + actual + ", vuelos candidatos: " + candidatos.size());
          if (candidatos.isEmpty()) { 
              //System.out.println("[DEBUG]   Sin vuelos disponibles desde " + actual);
              ruta = null; break; 
          }

          String contActual = Geo.continente(actual, inst);
            for (Vuelo v: candidatos){
                int visitasDestino = visitasPorAeropuerto.getOrDefault(v.destino,0);
                if (visitasDestino >= MAX_VISITAS_POR_AEROPUERTO) continue;
                if (EVITAR_RETROCESO && hops > 0 && v.destino.equals(actual)) continue;

                if (APLICAR_REGLAS_CONTINENTE){
                    //System.out.println("[DEBUG]   Evaluando vuelo: " + v.origen + "->" + v.destino + " salida:" + v.salidaUTC + " llegada:" + v.llegadaUTC);
                    String contSiguiente = Geo.continente(v.destino, inst);
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


        long[] t = new long[2];
        double eta = Heuristica.evaluarHeuristicaProducto(producto, actual, tiempoActual, v, capLocal, capGlobal, inst, t);
        if (eta <= 0) continue;

        double tau = feromona.getOrDefault(Claves.claveVuelo(v), Parametros.FEROMONA_INICIAL);
        double valor = Math.pow(Math.max(Parametros.FEROMONA_MIN, Math.min(Parametros.FEROMONA_MAX, tau)), Parametros.PESO_FEROMONA)
               * Math.pow(eta, Parametros.PESO_HEURISTICA);
        if (eta <= 0) {
          //System.out.println("[DEBUG]    Vuelo descartado por heurística (eta <= 0): " + v.origen + "->" + v.destino);
          continue;
        }

        C c = new C(); c.v=v; c.valor=valor; c.s=t[0]; c.l=t[1];
        pq.offer(c);
            }
          if (pq.isEmpty()) { ruta = null; break; }

          List<C> lista = new ArrayList<>();
          for (int i=0; i<Parametros.TAMANIO_LISTA_CANDIDATOS && !pq.isEmpty(); i++) lista.add(pq.poll());

              System.out.println("[DEBUG]   Ningún vuelo válido desde " + actual + " en este paso");
          C elegido = null;
          if (!lista.isEmpty()){
            if (azar.nextDouble() < Parametros.PROBABILIDAD_EXPLOTAR){
              elegido = lista.get(0);
            } else {
              double sum = 0.0; for (C c: lista) sum += c.valor;
              double r = azar.nextDouble() * sum;
              for (int i=0;i<lista.size();i++){ r -= lista.get(i).valor; if (r<=0 || i==lista.size()-1){ elegido = lista.get(i); break; } }
            }
          }
          if (elegido == null) { ruta = null; break; }

          // actualización local
          String k = Claves.claveVuelo(elegido.v);
          double tVal = feromona.getOrDefault(k, Parametros.FEROMONA_INICIAL);
          double nuevo = (1.0 - Parametros.TASA_ACTUALIZACION_LOCAL) * tVal + Parametros.TASA_ACTUALIZACION_LOCAL * Parametros.FEROMONA_INICIAL;
              //System.out.println("[DEBUG]   No se pudo elegir vuelo desde " + actual);
          feromona.put(k, limitarEntre(nuevo, Parametros.FEROMONA_MIN, Parametros.FEROMONA_MAX));

          // capacidad local (día)
          String claveDia = Claves.claveCapacidadVueloDia(elegido.v, elegido.s);
          capLocal.put(claveDia, capLocal.getOrDefault(claveDia,0) + cantidad);

          // subruta
          SubRuta sr = new SubRuta(elegido.v, elegido.s, elegido.l, cantidad);
          ruta.subrutas.add(sr);

          int usadoEstimado = capLocal.get(claveDia);
          double disp = Math.max(0, elegido.v.capacidadMaxima - usadoEstimado);
          minCapDisp = Math.min(minCapDisp, disp);

          String anterior = actual;
          actual = elegido.v.destino;
          previo = anterior;
          tiempoActual = elegido.l;
          visitasPorAeropuerto.put(actual, visitasPorAeropuerto.getOrDefault(actual,0)+1);
          hops++;
        }

        if (ruta != null && actual.equals(destino)) {
            ruta.llegadaFinalUTC = tiempoActual;
            ruta.tiempoTotal = Math.max(0, (ruta.llegadaFinalUTC - tiempoPedido)/60.0);
            ruta.capacidadMinimaDisponible = (Double.isInfinite(minCapDisp) ? 0.0 : minCapDisp);
            if (ruta.llegadaFinalUTC < mejorLlegada) {
                mejorRuta = ruta;
                mejorLlegada = ruta.llegadaFinalUTC;
            //System.out.println("[DEBUG] Ruta encontrada desde hub " + hub + " hasta " + destino + " llegada: " + tiempoActual);
            }
        }
    }
    return mejorRuta;
  }

  public static double limitarEntre(double v, double lo, double hi){ return Math.max(lo, Math.min(hi, v)); }
}
