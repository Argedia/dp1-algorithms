package alg2.model;
import java.time.LocalDate;
import java.util.*;

public class Instancia {
  public Map<String, Aeropuerto> aeropuertos = new HashMap<>();
  public java.util.List<Vuelo> vuelos = new ArrayList<>();
  public Map<String,java.util.List<Vuelo>> vuelosPorOrigen = new HashMap<>();
  public java.util.List<Pedido> pedidos = new ArrayList<>();
  public LocalDate fechaAncla;
  public int diasMes;

  public Map<String,Integer> indiceAeropuerto = new HashMap<>();
  public String[] indiceAAeropuerto;
  public int[][] distanciaSaltos;
  public int normalizadorSaltos = 1;
}
