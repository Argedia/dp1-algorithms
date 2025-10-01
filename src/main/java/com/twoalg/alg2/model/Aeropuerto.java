package alg2.model;
import alg2.config.Parametros;

public class Aeropuerto {
  public String idAeropuerto, ciudad, pais, continente;
  public int desfaseGMT;
  public int capacidadUsada, capacidadMaxima;
  public boolean esSedeExportadora;
  public Coordenada coord = new Coordenada(Double.NaN, Double.NaN);

  public double capacidadDisponible(){
    return Math.max(0, capacidadMaxima - capacidadUsada);
  }

  public static boolean mismoContinente(Aeropuerto a, Aeropuerto b){
    return a!=null && b!=null && String.valueOf(a.continente).equals(b.continente);
  }

  public static String hubParaContinente(String continente){
    switch (continente){
      case "SA": return "SPIM";
      case "EU": return "EBCI";
      case "AS": return "UBBB";
      default:   return "SPIM";
    }
  }
}
