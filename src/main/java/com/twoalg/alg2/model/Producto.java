package alg2.model;
import java.util.Date;

public class Producto {
  public String idProducto;
  public String idPedido;
  public Date fechaPedido;
  public Date fechaLimite;
  public EstadoEnvio estado = EstadoEnvio.EN_CURSO;
  public String ciudadDestino;
}    