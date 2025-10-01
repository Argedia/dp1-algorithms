package alg2.algoritmo;


import alg2.model.*;
import java.util.*;

public final class Preprocesamiento {
  private Preprocesamiento(){}

  public static void precomputarDistanciasPorSaltos(Instancia inst){
    int n = inst.aeropuertos.size();
    inst.indiceAeropuerto.clear();
    inst.indiceAAeropuerto = new String[n];
    int idx = 0;
    for (String code : inst.aeropuertos.keySet()){
      inst.indiceAeropuerto.put(code, idx);
      inst.indiceAAeropuerto[idx] = code;
      idx++;
    }
    inst.distanciaSaltos = new int[n][n];
    for (int i=0;i<n;i++) Arrays.fill(inst.distanciaSaltos[i], 1_000_000);

    Map<Integer, List<Integer>> adj = new HashMap<>();
    for (Map.Entry<String,List<Vuelo>> e : inst.vuelosPorOrigen.entrySet()){
      Integer u = inst.indiceAeropuerto.get(e.getKey());
      if (u==null) continue;
      List<Integer> lst = adj.computeIfAbsent(u, k-> new ArrayList<>());
      for (Vuelo v : e.getValue()){
        Integer w = inst.indiceAeropuerto.get(v.destino);
        if (w!=null) lst.add(w);
      }
    }

    int hopNorm = 1;
    for (int s=0;s<n;s++){
      int[] dist = inst.distanciaSaltos[s];
      dist[s] = 0;
      ArrayDeque<Integer> q = new ArrayDeque<>();
      q.add(s);
      while(!q.isEmpty()){
        int u = q.poll();
        for (int v : adj.getOrDefault(u, List.of())){
          if (dist[v] > dist[u] + 1){
            dist[v] = dist[u] + 1;
            hopNorm = Math.max(hopNorm, dist[v]);
            q.add(v);
          }
        }
      }
    }
    inst.normalizadorSaltos = hopNorm;
  }
}
