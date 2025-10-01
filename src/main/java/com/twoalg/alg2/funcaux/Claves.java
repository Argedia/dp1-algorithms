package alg2.funcaux;
import alg2.model.*;

public final class Claves {
  private Claves(){}

  public static String claveVuelo(Vuelo v){ 
    return v.origen+"|"+v.destino+"|"+v.horaOrigen+"|"+v.horaDestino; 
  }

  public static String claveCapacidadVueloDia(Vuelo v, long salidaAjustada){
    long base = v.salidaUTC;
    int indiceDia = (int)Math.floorDiv(salidaAjustada - base, 1440L);
    return claveVuelo(v) + "|" + indiceDia;
  }
}
