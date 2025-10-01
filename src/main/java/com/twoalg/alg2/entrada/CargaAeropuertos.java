package alg2.entrada;

import alg2.model.*;
import alg2.config.Parametros;
import java.nio.file.*; import java.io.*; import java.nio.charset.Charset;
import java.util.*; import java.util.regex.*;

public class CargaAeropuertos {
  public static Map<String,Aeropuerto> cargar(Path path) throws IOException {
    List<String> lineas = Files.readAllLines(path, Charset.forName("UTF-16"));
    Map<String,Aeropuerto> map = new HashMap<>();
    String continente = "SA";
    Pattern fila = Pattern.compile("^\\s*\\d+\\s+([A-Z0-9]{3,4})\\s+(.+?)\\s+(.+?)\\s+[A-Za-z]{4}\\s+([+-]?\\d+)\\s+(\\d+).*$");

    for (String ln : lineas) {
      String l = ln.trim(); if (l.isEmpty()) continue;
      String ll = l.toLowerCase(Locale.ROOT);
      if (ll.contains("américa del sur") || ll.contains("america del sur")) { continente = "SA"; continue; }
      if (ll.contains("europa")) { continente = "EU"; continue; }
      if (ll.contains("asia"))   { continente = "AS"; continue; }

      Matcher m = fila.matcher(ln);
      if (m.matches()) {
        Aeropuerto a = new Aeropuerto();
        a.idAeropuerto = m.group(1).trim();
        a.ciudad = m.group(2).trim().replaceAll("\\s+", " ");
        a.pais   = m.group(3).trim().replaceAll("\\s+", " ");
        a.desfaseGMT = Integer.parseInt(m.group(4).trim());
        a.capacidadMaxima = 900_000_000;
        a.capacidadUsada = 0;
        a.esSedeExportadora = Parametros.CODIGOS_HUBS.contains(a.idAeropuerto);
        a.continente = continente;
        a.coord = new Coordenada(Double.NaN, Double.NaN);
        map.put(a.idAeropuerto, a);
      }
    }
    return map;
  }
}
