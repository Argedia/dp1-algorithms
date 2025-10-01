package alg2.funcaux;
import java.time.*;
import java.time.format.DateTimeFormatter;

public final class Tiempo {
  private Tiempo(){}

  public static long aUTC(String hhmm, int gmt, LocalDate ancla){
    DateTimeFormatter fmt = DateTimeFormatter.ofPattern("HH:mm");
    LocalTime lt = LocalTime.parse(hhmm, fmt);
    LocalDateTime localDT = LocalDateTime.of(ancla, lt);
    int minutos = gmt * 60;
    return localDT.toEpochSecond(ZoneOffset.ofTotalSeconds(minutos*60))/60L;
  }

  public static int dayIndexLocal(long utcMin, int gmt, LocalDate ancla){
    long base = ancla.atStartOfDay().toEpochSecond(ZoneOffset.ofHours(gmt))/60L;
    long diff = utcMin - base;
    return (int)Math.floorDiv(diff, 1440L);
  }

  public static String fmtLocalDHHMM(long utcMin, int gmt, LocalDate ancla){
    int d = dayIndexLocal(utcMin, gmt, ancla);
    String hhmm = Instant.ofEpochSecond(utcMin*60L)
                .atOffset(ZoneOffset.ofHours(gmt))
                .toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"));
    return "D-" + d + " " + hhmm;
  }
}
