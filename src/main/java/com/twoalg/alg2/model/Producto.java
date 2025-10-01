package alg2.model;
import java.util.Date;

public class Producto {
  public String idProducto;
  public String idPedido;
  public long fechaPedido;
  public Date fechaLimite;
  public EstadoEnvio estado = EstadoEnvio.EN_CURSO;
  public String ciudadDestino;

  @Override
  public String toString() {
    return "Producto{" +
            "idProducto='" + idProducto + '\'' +
            ", idPedido='" + idPedido + '\'' +
            ", fechaPedido=" + fechaPedido +
            ", fechaLimite=" + fechaLimite +
            ", estado=" + estado +
            ", ciudadDestino='" + ciudadDestino + '\'' +
            '}';
  }
}