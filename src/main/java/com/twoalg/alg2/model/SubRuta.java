package alg2.model;

public class SubRuta {
  public String idRuta;
  public Vuelo vuelo;
  public long salidaAjustadaUTC, llegadaAjustadaUTC;
  public int capacidadUsada;

  public SubRuta(Vuelo v, long s, long l, int capUsada){
    this.vuelo=v;
    this.salidaAjustadaUTC=s; this.llegadaAjustadaUTC=l;
    this.idRuta = v.origen+"|"+v.destino+"|"+v.horaOrigen+"|"+v.horaDestino;
    this.capacidadUsada = capUsada;
  }

  public double duracionViajeSubruta(){
    return Math.max(0, (llegadaAjustadaUTC - salidaAjustadaUTC)/60.0);
  }
}
