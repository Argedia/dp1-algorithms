
package alg2.algoritmo;

import alg2.model.*;
import alg2.funcaux.Geo;
import alg2.funcaux.Claves;
import alg2.config.Parametros;
import java.util.Map;

import static alg2.config.Parametros.*;

public class Heuristica {
 
  public static double evaluarHeuristica(
      Pedido pedido, String aeropuertoActual, long tiempoActualUTC,
      Vuelo vuelo, Map<String,Integer> capLocal, Map<String,Integer> capGlobal,
      Instancia inst, long[] salidaLlegadaAjustadas) {

    if (!vuelo.origen.equals(aeropuertoActual)) return 0;

    long salida = vuelo.salidaUTC, llegada = vuelo.llegadaUTC;
    while (salida < tiempoActualUTC + MINUTOS_CONEXION_MINIMA) { salida += 1440; llegada += 1440; }

    salidaLlegadaAjustadas[0] = salida;
    salidaLlegadaAjustadas[1] = llegada;

    if (llegada > pedido.vencimientoUTC + TOLERANCIA_RETRASO_MINUTOS) return 0;

    String claveDia = Claves.claveCapacidadVueloDia(vuelo, salida);
    int usado = capGlobal.getOrDefault(claveDia,0) + capLocal.getOrDefault(claveDia,0);
    int capacidadDisponible = vuelo.capacidadMaxima - usado;
    if (capacidadDisponible < pedido.cantidad) return 0;

    double hCap   = capacidadDisponible / (double) vuelo.capacidadMaxima;
    double hProg  = Geo.progresoHaciaDestino(vuelo.destino, pedido.destino, inst);
    double espera = Math.max(0, salida - tiempoActualUTC);
    double hWait  = 1.0 / (1.0 + espera/45.0);

    double slackBruto = pedido.vencimientoUTC - llegada;
    double slackAjustado = slackBruto - COLCHON_SEGURIDAD_MINUTOS;
    double hSlack = slackAjustado >= 0 ? 1.0 : Math.exp(slackAjustado/60.0);

    double eta = 0.20*hCap + 0.45*hProg + 0.20*hWait + 0.15*hSlack;
    return Math.max(0, eta);
  }

  public static double evaluarHeuristicaProducto(
      Producto producto, String aeropuertoActual, long tiempoActualUTC,
      Vuelo vuelo, Map<String,Integer> capLocal, Map<String,Integer> capGlobal,
      Instancia inst, long[] salidaLlegadaAjustadas){

    if (!vuelo.origen.equals(aeropuertoActual)) return 0;

    long salida = vuelo.salidaUTC, llegada = vuelo.llegadaUTC;
    while (salida < tiempoActualUTC + MINUTOS_CONEXION_MINIMA) { salida += 1440; llegada += 1440; }

    salidaLlegadaAjustadas[0] = salida;
    salidaLlegadaAjustadas[1] = llegada;

    // SLA: 3 días (4320 min) si es intercontinental, 2 días (2880 min) si es intracontinental
    String contOrig = Geo.continente(aeropuertoActual, inst);
    String contDest = Geo.continente(producto.ciudadDestino, inst);
    boolean mismoCont = contOrig != null && contOrig.equals(contDest);
    long slaMin = mismoCont ? 2880 : 4320;
    long tiempoLimite = tiempoActualUTC + slaMin;

    if (llegada > tiempoLimite + TOLERANCIA_RETRASO_MINUTOS) return 0;

    String claveDia = Claves.claveCapacidadVueloDia(vuelo, salida);
    int usado = capGlobal.getOrDefault(claveDia,0) + capLocal.getOrDefault(claveDia,0);
    int capacidadDisponible = vuelo.capacidadMaxima - usado;
    // Suponemos que cada producto cuenta como 1 unidad
    if (capacidadDisponible < 1) return 0;

    double hCap   = capacidadDisponible / (double) vuelo.capacidadMaxima;
    double hProg  = Geo.progresoHaciaDestino(vuelo.destino, producto.ciudadDestino, inst);
    double espera = Math.max(0, salida - tiempoActualUTC);
    double hWait  = 1.0 / (1.0 + espera/45.0);

    double slackBruto = tiempoLimite - llegada;
    double slackAjustado = slackBruto - COLCHON_SEGURIDAD_MINUTOS;
    double hSlack = slackAjustado >= 0 ? 1.0 : Math.exp(slackAjustado/60.0);

    double eta = 0.20*hCap + 0.45*hProg + 0.20*hWait + 0.15*hSlack;
    return Math.max(0, eta);
  }


}
