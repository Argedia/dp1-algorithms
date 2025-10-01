package com.twoalg.alg2.main;

import alg2.model.*;
import alg2.entrada.*;
import alg2.algoritmo.Planificador;
import alg2.config.Parametros;
import alg2.algoritmo.Preprocesamiento;
import alg2.algoritmo.PedidoSplitter;
import alg2.algoritmo.PedidoSplitter;
import alg2.reporte.Reportes;
import alg2.entrada.CargaAeropuertos;
import alg2.entrada.CargaPedidos;
import alg2.entrada.CargaVuelos;
import alg2.entrada.CargarProductos;
import alg2.model.Instancia;
import alg2.model.Pedido;
import alg2.model.Producto;
import alg2.model.Vuelo;

import java.nio.file.*; import java.time.*; import java.util.*;

public class MoraPackACOmain {
  public static void main(String[] args) throws Exception {
    Path archivoAeropuertos = Paths.get("com/twoalg/alg2/c.1inf54.25.2.Aeropuerto.husos.v1.20250818__estudiantes.txt");
    Path archivoVuelos = Paths.get("com/twoalg/alg2/c.1inf54.25.2.planes_vuelo.v4.20250818.txt");
    Path archivoPedidos = Paths.get(args.length >= 1 ? args[0] : "com/twoalg/alg2/pedidos.txt");

    Long seedUsada = null;
    Random azar = new Random();
    if (args.length >= 2) { 
      seedUsada = Long.parseLong(args[1]); 
      azar.setSeed(seedUsada); 
    }

    LocalDate ancla = LocalDate.now();
    Instancia inst = new Instancia();
    inst.fechaAncla = ancla;
    inst.diasMes = ancla.lengthOfMonth();

    inst.aeropuertos = CargaAeropuertos.cargar(archivoAeropuertos);
    inst.vuelos = CargaVuelos.cargar(archivoVuelos, inst.aeropuertos, ancla);

    for (Vuelo f : inst.vuelos) 
      inst.vuelosPorOrigen.computeIfAbsent(f.origen, k -> new ArrayList<>()).add(f);
    for (java.util.List<Vuelo> lst : inst.vuelosPorOrigen.values()) 
      lst.sort(java.util.Comparator.comparingLong(v -> v.salidaUTC));

    Preprocesamiento.precomputarDistanciasPorSaltos(inst);

    java.util.List<Pedido> pedidosOriginales = CargaPedidos.cargar(archivoPedidos, inst, ancla, azar);
    System.out.printf("Pedidos cargados: %d%n", pedidosOriginales.size());
    java.util.List<Producto> TodosProductos = CargarProductos.cargarProductosdesdePedidos(pedidosOriginales);
  // Asignar productos a la instancia
      for (Producto prod : TodosProductos) {
          System.out.println(prod.toString());
        }
      
    
  inst.productos = TodosProductos;

   // inst.pedidos = PedidoSplitter.dividirPedidosEnSubpedidos(pedidosOriginales, inst, 100000);

     long t0 = System.currentTimeMillis();
    //Solucion heur = Planificador.construirSolucionHeuristica(inst);
    Solucion heur = Planificador.construirSolucionHeuristicaProducto(inst);
    System.out.printf("Heurística: a tiempo=%d tarde=%d fitness=%.2f%n",
      heur.subpedidosATiempo, heur.subpedidosTarde, heur.valorObjetivo);

    //Solucion mejor = Planificador.ejecutarACO(inst);
    Solucion mejor = Planificador.ejecutarACOProducto(inst);
    long t1 = System.currentTimeMillis();

    System.out.printf("ACO: a tiempo=%d tarde=%d fitness=%.2f%n",
    mejor.subpedidosATiempo, mejor.subpedidosTarde, mejor.valorObjetivo);

    long elapsedMs = (t1 - t0);
    //Reportes.mostrarPlanificacionPorPedido(inst, mejor,"com/twoalg/alg2/reporte_planificacion.txt");
    Reportes.mostrarPlanificacionPorProducto(inst, mejor,"com/twoalg/alg2/reporte_planificacionProductos.txt");
  }
}