package alg2.algoritmo;

import alg2.model.*;
import alg2.funcaux.Claves;
import alg2.config.Parametros;

import java.util.*;
import static alg2.algoritmo.ConstruccionRuta.feromona;
import static alg2.algoritmo.ConstruccionRuta.limitarEntre;

public class Planificador {

  public static Solucion construirSolucionHeuristica(Instancia inst){
    Solucion sol = new Solucion();
    Map<String,Integer> vacio = new HashMap<>();

    for (Pedido p : inst.pedidos) {
        Ruta mejorRuta = null;

        List<String> posiblesOrígenes = (p.origen == null)
            ? new ArrayList<>(Parametros.CODIGOS_HUBS)
            : List.of(p.origen);

        for (String hub : posiblesOrígenes) {
            p.origen = hub;
            Ruta r = ConstruccionRuta.construirRutaParaPedido(p, inst, vacio);
            if (r != null) {
                if (mejorRuta == null || r.llegadaFinalUTC < mejorRuta.llegadaFinalUTC) {
                    mejorRuta = r;
                }
            }
        }

        if (mejorRuta != null) {
            mejorRuta.aTiempo = (mejorRuta.llegadaFinalUTC <= p.vencimientoUTC);
            sol.rutas.put(p.id, mejorRuta);
            if (mejorRuta.aTiempo) sol.subpedidosATiempo++; else sol.subpedidosTarde++;
        } else {
            sol.subpedidosTarde++;
        }
    }

    long aTiempo = inst.pedidos.stream()
        .filter(p -> sol.rutas.get(p.id)!=null && sol.rutas.get(p.id).aTiempo)
        .mapToLong(p -> p.cantidad).sum();

    long tarde = inst.pedidos.stream()
        .filter(p -> sol.rutas.get(p.id)==null || !sol.rutas.get(p.id).aTiempo)
        .mapToLong(p -> p.cantidad).sum();

    sol.valorObjetivo = aTiempo - 3*tarde;
    return sol;
  }




  public static Solucion construirSolucionGlobal(Instancia inst){
    Solucion sol = new Solucion();

    Map<String,Integer> capGlobalVueloDia = new HashMap<>();
    int capTotalDiaria = inst.vuelos.stream().mapToInt(v -> v.capacidadMaxima).sum();
    Map<Integer,Integer> capUsadaGlobalDia = new HashMap<>();

    Map<Integer,List<Pedido>> porOriginal = new HashMap<>();
    for (Pedido p : inst.pedidos) {
        int pid = (p.idPedidoOriginal == -1 ? p.id : p.idPedidoOriginal);
        porOriginal.computeIfAbsent(pid, k -> new ArrayList<>()).add(p);
    }

    for (List<Pedido> grupo : porOriginal.values()) {
        boolean grupoOk = true;
        List<Ruta> rutasGrupo = new ArrayList<>();
        Map<Integer,Integer> aAgregarDiaGrupo = new HashMap<>();

        for (Pedido p : grupo) {
            Ruta mejorRuta = null;

            // Si no hay origen definido, probamos todos los hubs
            List<String> posiblesOrígenes = (p.origen == null)
                ? new ArrayList<>(Parametros.CODIGOS_HUBS)
                : List.of(p.origen);

            for (String hub : posiblesOrígenes) {
                p.origen = hub;
                Ruta r = ConstruccionRuta.construirRutaParaPedido(p, inst, capGlobalVueloDia);
                if (r != null) {
                    if (mejorRuta == null || r.llegadaFinalUTC < mejorRuta.llegadaFinalUTC) {
                        mejorRuta = r;
                    }
                }
            }

            if (mejorRuta == null) { grupoOk = false; break; }

            Map<Integer,Integer> aAgregar = new HashMap<>();
            boolean okDia = true;
            for (SubRuta s : mejorRuta.subrutas) {
                int idxDia = (int)Math.floorDiv(s.salidaAjustadaUTC - s.vuelo.salidaUTC, 1440L);
                int add = aAgregar.getOrDefault(idxDia, 0) + p.cantidad;
                if (capUsadaGlobalDia.getOrDefault(idxDia, 0) + add > capTotalDiaria) { 
                    okDia = false; break; 
                }
                aAgregar.put(idxDia, add);
            }
            if (!okDia) { grupoOk = false; break; }

            rutasGrupo.add(mejorRuta);
            for (Map.Entry<Integer,Integer> e : aAgregar.entrySet()) {
                aAgregarDiaGrupo.put(e.getKey(), aAgregarDiaGrupo.getOrDefault(e.getKey(), 0) + e.getValue());
            }
        }

        if (!grupoOk || rutasGrupo.size() != grupo.size()) {
            for (Pedido p : grupo) sol.subpedidosTarde++;
            continue;
        }

        for (int i = 0; i < grupo.size(); i++) {
            Pedido p = grupo.get(i);
            Ruta r = rutasGrupo.get(i);
            r.aTiempo = (r.llegadaFinalUTC <= p.vencimientoUTC);
            sol.rutas.put(p.id, r);
            if (r.aTiempo) sol.subpedidosATiempo++; else sol.subpedidosTarde++;
        }

        for (Map.Entry<Integer,Integer> e : aAgregarDiaGrupo.entrySet()) {
            int d = e.getKey();
            capUsadaGlobalDia.put(d, capUsadaGlobalDia.getOrDefault(d, 0) + e.getValue());
        }
        for (int i = 0; i < grupo.size(); i++) {
            Pedido p = grupo.get(i);
            Ruta r = rutasGrupo.get(i);
            for (SubRuta s : r.subrutas) {
                String claveDia = Claves.claveCapacidadVueloDia(s.vuelo, s.salidaAjustadaUTC);
                int nuevo = capGlobalVueloDia.getOrDefault(claveDia, 0) + p.cantidad;
                if (nuevo > s.vuelo.capacidadMaxima) sol.violacionesCapacidad++;
                capGlobalVueloDia.put(claveDia, nuevo);
            }
        }
    }

    long aTiempo = inst.pedidos.stream()
        .filter(p -> sol.rutas.get(p.id) != null && sol.rutas.get(p.id).aTiempo)
        .mapToLong(p -> p.cantidad).sum();

    long tarde = inst.pedidos.stream()
        .filter(p -> sol.rutas.get(p.id) == null || !sol.rutas.get(p.id).aTiempo)
        .mapToLong(p -> p.cantidad).sum();

    sol.valorObjetivo = aTiempo - 3*tarde - 5*sol.violacionesCapacidad;
    return sol;
  }


  public static void aplicarRefuerzoFeromonas(Solucion sol, double q, Instancia inst){
    if (sol == null) return;
    Map<Integer,Integer> cantidadPorId = new HashMap<>();
    for (Pedido p : inst.pedidos) cantidadPorId.put(p.id, p.cantidad);

    for (Map.Entry<Integer, Ruta> eR : sol.rutas.entrySet()){
      int chunkId = eR.getKey();
      Ruta r = eR.getValue();
      int cantidad = cantidadPorId.getOrDefault(chunkId, 1);
      double bonusBase = r.aTiempo ? q : q * 0.1;
      double bonus = bonusBase * Math.max(1, cantidad);

      for (SubRuta s: r.subrutas){
        String kk = Claves.claveVuelo(s.vuelo);
        double nv = feromona.getOrDefault(kk, Parametros.FEROMONA_INICIAL) + bonus;
        feromona.put(kk, limitarEntre(nv, Parametros.FEROMONA_MIN, Parametros.FEROMONA_MAX));
      }
    }
  }

  public static Solucion ejecutarACO(Instancia inst){
    feromona.clear();
    for (Vuelo f: inst.vuelos) feromona.putIfAbsent(Claves.claveVuelo(f), Parametros.FEROMONA_INICIAL);

    Solucion mejorGlobal = construirSolucionHeuristica(inst);
    double mejorValor = mejorGlobal.valorObjetivo;
    aplicarRefuerzoFeromonas(mejorGlobal, Parametros.INTENSIDAD_REFUERZO, inst);

    int sinMejora = 0;
    for (int it=0; it<Parametros.MAX_ITERACIONES; it++){
      List<Solucion> sols = new ArrayList<>(Parametros.NUM_HORMIGAS);
      for (int k=0; k<Parametros.NUM_HORMIGAS; k++) sols.add(construirSolucionGlobal(inst));

      sols.sort(Comparator.comparingDouble(s->-s.valorObjetivo));
      Solucion mejorIter = sols.get(0);

      for (String k: feromona.keySet()){
        double nv = (1.0 - Parametros.TASA_EVAPORACION_GLOBAL) * feromona.get(k);
        feromona.put(k, limitarEntre(nv, Parametros.FEROMONA_MIN, Parametros.FEROMONA_MAX));
      }

      aplicarRefuerzoFeromonas(mejorIter, Parametros.INTENSIDAD_REFUERZO * Parametros.FRACCION_REFUERZO_ELITE, inst);
      aplicarRefuerzoFeromonas(mejorGlobal, Parametros.INTENSIDAD_REFUERZO, inst);

      if (mejorIter.valorObjetivo > mejorValor){
        mejorGlobal = mejorIter; mejorValor = mejorIter.valorObjetivo; sinMejora = 0;
      } else sinMejora++;

      if (sinMejora >= Parametros.PACIENCIA_ESTANCAMIENTO){
        for (String k: feromona.keySet()) feromona.put(k, Parametros.FEROMONA_INICIAL);
        aplicarRefuerzoFeromonas(mejorGlobal, Parametros.INTENSIDAD_REFUERZO, inst);
        sinMejora = 0;
      }
    }
    return mejorGlobal;
  }
//////////////////////////PLANIFICACION PRODUCTOS//////////////////////////

    public static Solucion construirSolucionHeuristicaProducto(Instancia inst){
    Solucion sol = new Solucion();
    Map<String,Integer> vacio = new HashMap<>();

    int id = 1;
    for (Producto prod : inst.productos) {
        Ruta ruta = ConstruccionRuta.construirRutaParaProducto(prod, inst, vacio);
        if (ruta != null) {
            // Consideramos "aTiempo" si cumple el SLA (ya calculado en heurística)
            ruta.aTiempo = true; // O puedes calcularlo según la lógica de negocio
            sol.rutas.put(id, ruta);
            sol.subpedidosATiempo++;
        } else {
            sol.subpedidosTarde++;
        }
        id++;
    }

    sol.valorObjetivo = sol.subpedidosATiempo - 3*sol.subpedidosTarde;
    return sol;
    }

    public static Solucion construirSolucionGlobalProducto(Instancia inst){

        Solucion sol = new Solucion();
        Map<String,Integer> capGlobalVueloDia = new HashMap<>();
        int capTotalDiaria = inst.vuelos.stream().mapToInt(v -> v.capacidadMaxima).sum();
        Map<Integer,Integer> capUsadaGlobalDia = new HashMap<>();

        // Agrupar productos por algún criterio si es necesario, aquí se procesan individualmente
        for (Producto prod : inst.productos) {
            Ruta mejorRuta = null;

            // Intentar construir la mejor ruta para el producto
            Ruta r = ConstruccionRuta.construirRutaParaProducto(prod, inst, capGlobalVueloDia);
            if (r != null) {
                mejorRuta = r;
            }

            if (mejorRuta == null) {
                sol.subpedidosTarde++;
                continue;
            }

            // Verificar restricciones de capacidad global por día
            Map<Integer,Integer> aAgregar = new HashMap<>();
            boolean okDia = true;
            for (SubRuta s : mejorRuta.subrutas) {
                int idxDia = (int)Math.floorDiv(s.salidaAjustadaUTC - s.vuelo.salidaUTC, 1440L);
                int add = aAgregar.getOrDefault(idxDia, 0) + 1; // cada producto es 1 unidad
                if (capUsadaGlobalDia.getOrDefault(idxDia, 0) + add > capTotalDiaria) {
                    okDia = false; break;
                }
                aAgregar.put(idxDia, add);
            }
            if (!okDia) {
                sol.subpedidosTarde++;
                continue;
            }

            // Si pasa las restricciones, agregar la ruta y actualizar capacidades
            mejorRuta.aTiempo = true; // Consideramos a tiempo si se construyó ruta válida
            sol.rutas.put(prod.hashCode(), mejorRuta); // O usa un id único de producto
            sol.subpedidosATiempo++;

            for (Map.Entry<Integer,Integer> e : aAgregar.entrySet()) {
                int d = e.getKey();
                capUsadaGlobalDia.put(d, capUsadaGlobalDia.getOrDefault(d, 0) + e.getValue());
            }
            for (SubRuta s : mejorRuta.subrutas) {
                String claveDia = Claves.claveCapacidadVueloDia(s.vuelo, s.salidaAjustadaUTC);
                int nuevo = capGlobalVueloDia.getOrDefault(claveDia, 0) + 1;
                if (nuevo > s.vuelo.capacidadMaxima) sol.violacionesCapacidad++;
                capGlobalVueloDia.put(claveDia, nuevo);
            }
        }

        sol.valorObjetivo = sol.subpedidosATiempo - 3*sol.subpedidosTarde - 5*sol.violacionesCapacidad;
        return sol;
    }


    public static void aplicarRefuerzoFeromonasProducto(Solucion sol, double q, Instancia inst){
        if (sol == null) return;
        // Cada producto cuenta como 1 unidad
        for (Map.Entry<Integer, Ruta> eR : sol.rutas.entrySet()) {
            int prodId = eR.getKey();
            Ruta r = eR.getValue();
            int cantidad = 1; // cada producto es 1 unidad
            double bonusBase = r.aTiempo ? q : q * 0.1;
            double bonus = bonusBase * cantidad;

            for (SubRuta s : r.subrutas) {
                String kk = Claves.claveVuelo(s.vuelo);
                double nv = feromona.getOrDefault(kk, Parametros.FEROMONA_INICIAL) + bonus;
                feromona.put(kk, limitarEntre(nv, Parametros.FEROMONA_MIN, Parametros.FEROMONA_MAX));
            }
        }
    }

    public static Solucion ejecutarACOProducto(Instancia inst){
        feromona.clear();
        for (Vuelo f: inst.vuelos) feromona.putIfAbsent(Claves.claveVuelo(f), Parametros.FEROMONA_INICIAL);

        Solucion mejorGlobal = construirSolucionHeuristicaProducto(inst);
        double mejorValor = mejorGlobal.valorObjetivo;
        aplicarRefuerzoFeromonasProducto(mejorGlobal, Parametros.INTENSIDAD_REFUERZO, inst);

        int sinMejora = 0;
        for (int it=0; it<Parametros.MAX_ITERACIONES; it++){
            List<Solucion> sols = new ArrayList<>(Parametros.NUM_HORMIGAS);
            for (int k=0; k<Parametros.NUM_HORMIGAS; k++) sols.add(construirSolucionGlobalProducto(inst));

            sols.sort(Comparator.comparingDouble(s->-s.valorObjetivo));
            Solucion mejorIter = sols.get(0);

            for (String k: feromona.keySet()){
                double nv = (1.0 - Parametros.TASA_EVAPORACION_GLOBAL) * feromona.get(k);
                feromona.put(k, limitarEntre(nv, Parametros.FEROMONA_MIN, Parametros.FEROMONA_MAX));
            }

            aplicarRefuerzoFeromonasProducto(mejorIter, Parametros.INTENSIDAD_REFUERZO * Parametros.FRACCION_REFUERZO_ELITE, inst);
            aplicarRefuerzoFeromonasProducto(mejorGlobal, Parametros.INTENSIDAD_REFUERZO, inst);

            if (mejorIter.valorObjetivo > mejorValor){
                mejorGlobal = mejorIter; mejorValor = mejorIter.valorObjetivo; sinMejora = 0;
            } else sinMejora++;

            if (sinMejora >= Parametros.PACIENCIA_ESTANCAMIENTO){
                for (String k: feromona.keySet()) feromona.put(k, Parametros.FEROMONA_INICIAL);
                aplicarRefuerzoFeromonasProducto(mejorGlobal, Parametros.INTENSIDAD_REFUERZO, inst);
                sinMejora = 0;
            }
        }
        return mejorGlobal;
    }

}
