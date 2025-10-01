package alg2.algoritmo;

import alg2.model.*;
import alg2.config.Parametros;

import java.util.ArrayList;
import java.util.List;

import static alg2.config.Parametros.TAM_MAX_SUBPEDIDO;

public final class PedidoSplitter {
  private PedidoSplitter(){}

  public static List<Pedido> dividirPedidosEnSubpedidos(List<Pedido> pedidos, Instancia inst, int siguienteIdInicio) {
    List<Pedido> out = new ArrayList<>();
    int nextId = siguienteIdInicio;

    for (Pedido p : pedidos) {
      int capMin = capacidadVueloMinimoDesde(inst, p.origen, p.destino);
      int tamanioSubpedido = Math.max(1, Math.min(TAM_MAX_SUBPEDIDO, capMin));
      int K = (int) Math.ceil(p.cantidad / (double) tamanioSubpedido);

      int rem = p.cantidad;

      if (K <= 1) { out.add(p); continue; }
      for (int i = 1; i <= K; i++) {
        Pedido c = new Pedido();
        c.id = nextId++;
        c.origen = p.origen;
        c.destino = p.destino;
        c.cantidad = Math.min(rem, tamanioSubpedido);
        c.liberacionUTC = p.liberacionUTC;
        c.vencimientoUTC = p.vencimientoUTC;
        c.idPedidoOriginal = (p.idPedidoOriginal == -1 ? p.id : p.idPedidoOriginal);
        c.indiceSubpedido = i;
        c.totalSubpedidos = K;

        // clonar rastro de Producto (si viene)
        if (p.producto != null){
          Producto cp = new Producto();
          cp.idProducto = p.producto.idProducto + "-" + i;
          cp.idPedido = "PED-" + (c.idPedidoOriginal);
          cp.fechaPedido = p.producto.fechaPedido;
          cp.fechaLimite = p.producto.fechaLimite;
          cp.estado = p.producto.estado;
          cp.ciudadDestino = p.producto.ciudadDestino;
          c.producto = cp;
        }

        out.add(c);
        rem -= c.cantidad;
      }
    }
    return out;
  }

  // helper interno del splitter
  private static int capacidadVueloMinimoDesde(Instancia inst, String origen, String destino) {
    int capMin = Integer.MAX_VALUE;
    for (Vuelo v : inst.vuelos) {
      if (v.origen.equals(origen) && v.destino.equals(destino)) {
        capMin = Math.min(capMin, v.capacidadMaxima);
      }
    }
    if (capMin == Integer.MAX_VALUE) {
      for (Vuelo v : inst.vuelos) if (v.origen.equals(origen)) capMin = Math.min(capMin, v.capacidadMaxima);
    }
    if (capMin == Integer.MAX_VALUE) {
      for (Vuelo v : inst.vuelos) if (v.destino.equals(destino)) capMin = Math.min(capMin, v.capacidadMaxima);
    }
    if (capMin == Integer.MAX_VALUE) capMin = 150;
    return capMin;
  }
}
