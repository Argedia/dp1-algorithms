package alg2.config;

import java.time.Duration;
import java.util.Set;

public final class Parametros {
  private Parametros(){}

  // Negocio
  public static final int MINUTOS_CONEXION_MINIMA = 60;
  public static final Duration SLA_MISMO_CONTINENTE = Duration.ofDays(2);
  public static final Duration SLA_CONTINENTES_DISTINTOS = Duration.ofDays(3);
  public static final Set<String> CODIGOS_HUBS = Set.of("SPIM","EBCI","UBBB");

  // ACO
  public static int NUM_HORMIGAS = 150;
  public static int MAX_ITERACIONES = 250;

  public static double PESO_FEROMONA = 1.25;
  public static double PESO_HEURISTICA = 6.0;
  public static double TASA_EVAPORACION_GLOBAL = 0.08;
  public static double TASA_ACTUALIZACION_LOCAL = 0.10;
  public static double FEROMONA_INICIAL = 0.01;
  public static double INTENSIDAD_REFUERZO = 1.5;
  public static double FRACCION_REFUERZO_ELITE = 0.35;
  public static double PROBABILIDAD_EXPLOTAR = 0.17;

  public static double FEROMONA_MIN = 1e-6;
  public static double FEROMONA_MAX = 10.0;

  // Restricciones
  public static final int MAX_ESCALAS = 4;
  public static final int MAX_VISITAS_POR_AEROPUERTO = 1;
  public static final int PACIENCIA_ESTANCAMIENTO = 50;
  public static final boolean EVITAR_RETROCESO = true;
  public static final boolean APLICAR_REGLAS_CONTINENTE = true;

  // SLA / split
  public static final int TAM_MAX_SUBPEDIDO = 250;
  public static final int TOLERANCIA_RETRASO_MINUTOS = 90;

  // Candidatos
  public static final int TAMANIO_LISTA_CANDIDATOS = 8;

  // Analítica
  public static final int SLACK_CRITICO_MINUTOS = 120;
  public static final int COLCHON_SEGURIDAD_MINUTOS = 0;
}
