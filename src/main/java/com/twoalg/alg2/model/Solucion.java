package alg2.model;
import java.util.*;

public class Solucion {
  public Map<Integer,Ruta> rutas = new HashMap<>();
  public int subpedidosATiempo=0, subpedidosTarde=0, violacionesCapacidad=0;
  public double valorObjetivo=0;
}
