
package alg2.reporte;

import alg2.model.*;
import alg2.funcaux.Tiempo;
import java.io.*; import java.util.*;

public class Reportes {

  public static void mostrarPlanificacionPorPedido(Instancia inst, Solucion sol, String rutaArchivo){
    try (PrintWriter out = new PrintWriter(new FileWriter(rutaArchivo))) {
      String sep = "=".repeat(120);
      out.println(sep);
      out.println("REPORTE DE PLANIFICACION POR PEDIDO");
      out.println(sep);

      Map<Integer, List<Pedido>> porOriginal = new HashMap<>();
      for (Pedido p : inst.pedidos) {
        int pid = (p.idPedidoOriginal == -1 ? p.id : p.idPedidoOriginal);
        porOriginal.computeIfAbsent(pid, k -> new ArrayList<>()).add(p);
      }

      for (Map.Entry<Integer, List<Pedido>> e : porOriginal.entrySet()) {
        List<Pedido> lista = e.getValue();
        lista.sort(java.util.Comparator.comparingInt(p -> p.indiceSubpedido));
        Pedido base = lista.get(0);
        Aeropuerto apOri = inst.aeropuertos.get(base.origen);
        Aeropuerto apDes = inst.aeropuertos.get(base.destino);
        int totalCantidad = lista.stream().mapToInt(p -> p.cantidad).sum();

        out.printf(">> PEDIDO #%d | Origen: %s | Destino: %s | Cantidad: %d | Subpedidos: %d%n",
          e.getKey(), base.origen, base.destino, totalCantidad, lista.size());
        out.println("-".repeat(120));
        out.printf("%-12s | %-8s | %-10s | %-12s | %-12s | %-15s%n",
          "SUBPEDIDO","CANTIDAD","ESTADO","HORA LIB.","PLAZO","RESULTADO");
        out.println("-".repeat(120));

        for (Pedido p : lista) {
          Ruta r = sol.rutas.get(p.id);
          String estado = (r == null) ? "SIN RUTA" : (r.aTiempo ? "A TIEMPO" : "RETRASO");

          out.printf("%-12s | %-8d | %-10s | %-12s | %-12s | %-15s%n",
            (p.indiceSubpedido + "/" + p.totalSubpedidos),
            p.cantidad,
            estado,
            Tiempo.fmtLocalDHHMM(p.liberacionUTC, apOri.desfaseGMT, inst.fechaAncla),
            Tiempo.fmtLocalDHHMM(p.vencimientoUTC, apDes.desfaseGMT, inst.fechaAncla),
            (r == null ? "❌" : (r.aTiempo ? "✅" : "⚠️"))
          );

          if (r != null) {
            out.println("      Resumen de la ruta:");
            out.printf("      - Duración total: %.2f hrs%n", r.duracionViaje());
            out.printf("      - Capacidad mínima disponible estimada: %.1f%n", r.capacidadMinimaDisponible);
            out.println("      - Subrutas (tramos):");

            for (SubRuta s : r.subrutas) {
              Aeropuerto aOri = inst.aeropuertos.get(s.vuelo.origen);
              Aeropuerto aDes = inst.aeropuertos.get(s.vuelo.destino);
              out.printf("         %s (%s) %s -> %s (%s) %s | dur=%.2f h | capUsada=%d%n",
                s.vuelo.origen, aOri.ciudad, Tiempo.fmtLocalDHHMM(s.salidaAjustadaUTC, aOri.desfaseGMT, inst.fechaAncla),
                s.vuelo.destino, aDes.ciudad, Tiempo.fmtLocalDHHMM(s.llegadaAjustadaUTC, aDes.desfaseGMT, inst.fechaAncla),
                s.duracionViajeSubruta(), s.capacidadUsada);
            }

            out.println("      Entrega planificada: " + Tiempo.fmtLocalDHHMM(r.llegadaFinalUTC, apDes.desfaseGMT, inst.fechaAncla));
            out.println();
          }
        }
        out.println(sep);
      }
    } catch (IOException ex) {
      ex.printStackTrace();
    }
  }


  //////////////REPORTE DE RUTAS POR PRODUCTO//////////////
    public static void mostrarPlanificacionPorProducto(Instancia inst, Solucion sol, String rutaArchivo){
    try (PrintWriter out = new PrintWriter(new FileWriter(rutaArchivo))) {
      String sep = "=".repeat(120);
      out.println(sep);
      out.println("REPORTE DE PLANIFICACION POR PRODUCTO");
      out.println(sep);

      int idx = 1;
      for (Producto prod : inst.productos) {
        Ruta r = sol.rutas.get(prod.hashCode());
        String estado = (r == null) ? "SIN RUTA" : (r.aTiempo ? "A TIEMPO" : "RETRASO");


        out.printf(">> PRODUCTO #%d | Destino: %s | Fecha Pedido: %s\n",
          idx, prod.ciudadDestino, prod.fechaPedido);
        out.println("-".repeat(120));
        out.printf("%-10s | %-12s | %-10s | %-15s\n",
          "ESTADO","HORA PEDIDO","LLEGADA","RESULTADO");
        out.println("-".repeat(120));

        String llegada = (r == null) ? "-" : Tiempo.fmtLocalDHHMM(r.llegadaFinalUTC, inst.aeropuertos.get(prod.ciudadDestino).desfaseGMT, inst.fechaAncla);
        out.printf("%-10s | %-12s | %-10s | %-15s\n",
          estado,
          prod.fechaPedido,
          llegada,
          (r == null ? "❌" : (r.aTiempo ? "✅" : "⚠️"))
        );

        if (r != null) {
          out.println("      Resumen de la ruta:");
          out.printf("      - Duración total: %.2f hrs\n", r.duracionViaje());
          out.printf("      - Capacidad mínima disponible estimada: %.1f\n", r.capacidadMinimaDisponible);
          out.println("      - Subrutas (tramos):");

          for (SubRuta s : r.subrutas) {
            Aeropuerto aOri = inst.aeropuertos.get(s.vuelo.origen);
            Aeropuerto aDes = inst.aeropuertos.get(s.vuelo.destino);
            out.printf("         %s (%s) %s -> %s (%s) %s | dur=%.2f h | capUsada=%d\n",
              s.vuelo.origen, aOri.ciudad, Tiempo.fmtLocalDHHMM(s.salidaAjustadaUTC, aOri.desfaseGMT, inst.fechaAncla),
              s.vuelo.destino, aDes.ciudad, Tiempo.fmtLocalDHHMM(s.llegadaAjustadaUTC, aDes.desfaseGMT, inst.fechaAncla),
              s.duracionViajeSubruta(), s.capacidadUsada);
          }

          out.println("      Entrega planificada: " + Tiempo.fmtLocalDHHMM(r.llegadaFinalUTC, inst.aeropuertos.get(prod.ciudadDestino).desfaseGMT, inst.fechaAncla));
          out.println();
        }
        out.println(sep);
        idx++;
      }
    } catch (IOException ex) {
      ex.printStackTrace();
    }
  }
}
