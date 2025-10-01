package alg2.entrada;


import alg2.model.Pedido;
import alg2.model.Producto;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;

public class CargarProductos {
    public static java.util.List<Producto>cargarProductosdesdePedidos(java.util.List<Pedido> pedidosOriginales) {
        ArrayList<Producto> productos = new ArrayList<>();
        for (Pedido pedido : pedidosOriginales) {
            for (int i = 1; i <= pedido.cantidad; i++) {
                Producto prod = new Producto();
                prod.idProducto = pedido.id + "-" + i;
                prod.idPedido = "PED-" + pedido.id;
                prod.fechaPedido = pedido.liberacionUTC;
                //long venc = pedido.liberacionUTC + Parametros.SLA_CONTINENTES_DISTINTOS.toMinutes(); 
                //prod.fechaLimite = venc;
                prod.estado = pedido.producto != null ? pedido.producto.estado : null;
                prod.ciudadDestino = pedido.destino;
                productos.add(prod);
            }
        }
        return productos;
    }
}

