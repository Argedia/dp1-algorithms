package alg2.entrada;

import alg2.model.*;
import alg2.config.Parametros;
import java.nio.file.*; 
import java.io.*; 
import java.time.*; 
import java.util.*; 
import java.util.regex.*;

public class CargaPedidos {
  public static java.util.List<Pedido> cargar(Path path, Instancia inst, LocalDate ancla, Random azar) throws IOException {
    List<String> lineas = Files.readAllLines(path);
    List<Pedido> pedidos = new ArrayList<>();
    int idAuto = 1;

    Pattern pat = Pattern.compile("^(\\d{2})-(\\d{2})-(\\d{2})-([A-Z0-9]{3,4})-(\\d{3})-(\\d{7})$");

    for (String ln : lineas) {
      String l = ln.trim();
      if (l.isEmpty() || l.startsWith("#")) continue;
      Matcher m = pat.matcher(l);
      if (!m.matches()) continue;

      int dd = Integer.parseInt(m.group(1));
      int hh = Integer.parseInt(m.group(2));
      int mm = Integer.parseInt(m.group(3));
      String dest = m.group(4);
      int qty = Integer.parseInt(m.group(5));

      if (dd<1||dd>24||hh<1||hh>23||mm<1||mm>59||qty<1||qty>999) continue;

      Aeropuerto aDest = inst.aeropuertos.get(dest);
      if (aDest == null) continue;

      String origen = null;  

      int offsetSecDest = aDest.desfaseGMT * 3600;
      long liberacionUTC = ancla.atStartOfDay().plusDays(dd)
        .withHour(hh).withMinute(mm)
        .toEpochSecond(ZoneOffset.ofTotalSeconds(offsetSecDest)) / 60;

      boolean same = false; 
      long venc = liberacionUTC + Parametros.SLA_CONTINENTES_DISTINTOS.toMinutes(); 

      Pedido p = new Pedido();
      p.id = idAuto++; 
      p.origen = origen; 
      p.destino = dest; 
      p.cantidad = qty;
      p.liberacionUTC = liberacionUTC; 
      p.vencimientoUTC = venc;

      Producto prod = new Producto();
      prod.idProducto = "PRD-" + p.id;
      prod.idPedido = "PED-" + (p.idPedidoOriginal == -1 ? p.id : p.idPedidoOriginal);
      prod.fechaPedido = Date.from(Instant.ofEpochSecond(liberacionUTC*60L));
      prod.fechaLimite = Date.from(Instant.ofEpochSecond(venc*60L));
      prod.estado = EstadoEnvio.EN_CURSO;
      prod.ciudadDestino = dest;
      p.producto = prod;

      pedidos.add(p);
    }
    return pedidos;
  }
}
