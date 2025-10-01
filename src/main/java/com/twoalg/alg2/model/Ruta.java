package alg2.model;
import java.util.*;

public class Ruta {
  public String idRuta = UUID.randomUUID().toString();
  public java.util.List<SubRuta> subrutas = new ArrayList<>();
  public double tiempoTotal;
  public double capacidadMinimaDisponible;
  public long llegadaFinalUTC;
  public boolean aTiempo;
  public double duracionViaje(){ return tiempoTotal; }
}
