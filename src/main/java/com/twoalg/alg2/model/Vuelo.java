
package alg2.model;
public class Vuelo {
  public String idVuelo;
  public EstadoVuelo estado = EstadoVuelo.PROGRAMADO;
  public int capacidadMaxima;
  public String origen, destino;
  public String horaOrigen, horaDestino;
  public double horaSalida, horaLlegada;
  public long salidaUTC, llegadaUTC;

  public double capacidadDisponible(){ return capacidadMaxima; }

  @Override public String toString(){
    return origen+"->"+destino+"("+horaOrigen+"-"+horaDestino+")";
  }
}
