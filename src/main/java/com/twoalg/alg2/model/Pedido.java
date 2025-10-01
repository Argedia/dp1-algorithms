package alg2.model;

public class Pedido {
  public int id;
  public String origen, destino;
  public int cantidad;
  public long liberacionUTC, vencimientoUTC;
  public int idPedidoOriginal = -1;
  public int indiceSubpedido = 1;
  public int totalSubpedidos = 1;
  public Producto producto; // <— nombre consistente
}
