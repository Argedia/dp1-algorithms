package alg2.funcaux;
import alg2.model.*;

public final class Geo {
  private Geo(){}

  public static double distanciaHaversine(double lat1,double lon1,double lat2,double lon2){
    double R=6371.0, dLat=Math.toRadians(lat2-lat1), dLon=Math.toRadians(lon2-lon1);
    double a=Math.sin(dLat/2)*Math.sin(dLat/2)+
      Math.cos(Math.toRadians(lat1))*Math.cos(Math.toRadians(lat2))*
      Math.sin(dLon/2)*Math.sin(dLon/2);
    double c=2*Math.atan2(Math.sqrt(a),Math.sqrt(1-a));
    return R*c;
  }

  public static String continente(String apt, Instancia inst){
    Aeropuerto a = inst.aeropuertos.get(apt);
    return a==null? null : a.continente;
  }

  public static double progresoHaciaDestino(String actual, String destino, Instancia inst){
    Aeropuerto a=inst.aeropuertos.get(actual), b=inst.aeropuertos.get(destino);
    if (a==null||b==null) return 0.0;
    boolean geoOK = a.coord!=null && b.coord!=null
      && !Double.isNaN(a.coord.latitud) && !Double.isNaN(a.coord.longitud)
      && !Double.isNaN(b.coord.latitud) && !Double.isNaN(b.coord.longitud);
    if (geoOK){
      double d=distanciaHaversine(a.coord.latitud,a.coord.longitud,b.coord.latitud,b.coord.longitud);
      return Math.max(0.0, Math.min(1.0, 1.0 - d/15000.0));
    } else {
      Integer ia = inst.indiceAeropuerto.get(actual), ib = inst.indiceAeropuerto.get(destino);
      if (ia==null || ib==null) return 0.0;
      int d = inst.distanciaSaltos[ia][ib];
      if (d >= 1_000_000) return 0.0;
      double norm = Math.max(1.0, inst.normalizadorSaltos);
      return Math.max(0.0, Math.min(1.0, 1.0 - d / norm));
    }
  }
}
