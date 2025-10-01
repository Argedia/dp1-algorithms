package alg2.entrada;
import alg2.model.*;
import alg2.funcaux.Tiempo;
import java.nio.file.*; import java.io.*; import java.time.*; import java.time.format.DateTimeFormatter;
import java.util.*;

public class CargaVuelos {
  public static java.util.List<Vuelo> cargar(Path path, Map<String,Aeropuerto> aeropuertos, LocalDate ancla) throws IOException {
    List<String> lineas = Files.readAllLines(path);
    List<Vuelo> lista = new ArrayList<>();
    for (String ln: lineas){
      String[] t = ln.split("-");
      if (t.length < 5) continue;
      Vuelo v = new Vuelo();
      v.origen = t[0].trim(); v.destino = t[1].trim();
      v.horaOrigen = t[2].trim(); v.horaDestino = t[3].trim();
      try { v.capacidadMaxima = Integer.parseInt(t[4].trim()); } catch(Exception e){ v.capacidadMaxima = 300; }

      Aeropuerto ao = aeropuertos.get(v.origen);
      Aeropuerto ad = aeropuertos.get(v.destino);
      if (ao == null || ad == null) { continue; }

      v.salidaUTC = Tiempo.aUTC(v.horaOrigen, ao.desfaseGMT, ancla);
      v.llegadaUTC = Tiempo.aUTC(v.horaDestino, ad.desfaseGMT, ancla);
      long dur = v.llegadaUTC - v.salidaUTC;
      while (dur <= 0) { v.llegadaUTC += 24*60; dur = v.llegadaUTC - v.salidaUTC; }

      DateTimeFormatter fmt = DateTimeFormatter.ofPattern("HH:mm");
      v.horaSalida  = LocalTime.parse(v.horaOrigen, fmt).getHour() + LocalTime.parse(v.horaOrigen, fmt).getMinute()/60.0;
      v.horaLlegada = LocalTime.parse(v.horaDestino, fmt).getHour() + LocalTime.parse(v.horaDestino, fmt).getMinute()/60.0;
      v.idVuelo = v.origen+"|"+v.destino+"|"+v.horaOrigen+"|"+v.horaDestino;

      lista.add(v);
    }
    return lista;
  }
}
