package pe.pucp.paqrap.app.datos;

import pe.pucp.paqrap.app.modelo.Nodo;
import pe.pucp.paqrap.planner.core.MapaReticula;
import pe.pucp.paqrap.planner.ien.LectorBloqueos;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Formato oficial de los archivos (el mismo que leen LectorVentas y LectorBloqueos de rutapaq-planner):
 *
 * ventas.aaaamm.txt   ddDhhHmmm:x,y,idCliente,cantidad,plazoHoras        01D01H052:39,45,c-00001,1,36
 * bloqueo.aamm.txt    ddDhhHmmm-ddDhhHmmm:x1,y1,x2,y2,...                01D01H040-01D03H013:30,5,45,5
 *
 * La diferencia con los lectores del planner es que aquí se lee línea por línea (ver LectorMensual),
 * no el archivo completo, para que el volumen de registros no afecte la memoria del servidor.
 */
public final class Formato {
    private static final Pattern VENTA =
            Pattern.compile("^\\s*(\\d+)D(\\d+)H(\\d+):([^,]+),([^,]+),([^,]+),(\\d+),(\\d+)\\s*$");
    private static final Pattern BLOQUEO =
            Pattern.compile("^\\s*(\\d+)D(\\d+)H(\\d+)-(\\d+)D(\\d+)H(\\d+):(.+)$");
    private static final MapaReticula INDICES = new MapaReticula(70, 50);

    private Formato() {
    }

    public static String archivoVentas(YearMonth m) {
        return String.format("ventas.%04d%02d.txt", m.getYear(), m.getMonthValue());
    }

    public static String archivoBloqueos(YearMonth m) {
        return String.format("bloqueo.%02d%02d.txt", m.getYear() % 100, m.getMonthValue());
    }

    private static LocalDateTime instante(YearMonth m, String dia, String hora, String minuto) {
        return m.atDay(1).atStartOfDay().plusDays(Integer.parseInt(dia) - 1L)
                .plusHours(Integer.parseInt(hora)).plusMinutes(Integer.parseInt(minuto));
    }

    public record LineaPedido(LocalDateTime registro, Nodo nodo, String cliente, int cantidad, int plazoHoras) {
    }

    public static LineaPedido pedido(YearMonth m, String linea) {
        Matcher x = VENTA.matcher(linea);
        if (!x.matches()) throw new IllegalArgumentException("Registro de ventas inválido: " + linea);
        return new LineaPedido(instante(m, x.group(1), x.group(2), x.group(3)),
                new Nodo(Integer.parseInt(x.group(4).trim()), Integer.parseInt(x.group(5).trim())),
                x.group(6).trim(), Integer.parseInt(x.group(7)), Integer.parseInt(x.group(8)));
    }

    public static Bloqueo bloqueo(YearMonth m, String linea) {
        Matcher x = BLOQUEO.matcher(linea);
        if (!x.matches()) throw new IllegalArgumentException("Registro de bloqueo inválido: " + linea);
        String[] c = x.group(7).split(",");
        int[] puntos = new int[c.length];
        for (int i = 0; i < c.length; i++) puntos[i] = Integer.parseInt(c[i].trim());
        List<Nodo> nodos = new ArrayList<>();
        for (int n : LectorBloqueos.nodosDePoligonal(puntos, INDICES)) nodos.add(new Nodo(INDICES.x(n), INDICES.y(n)));
        return new Bloqueo(instante(m, x.group(1), x.group(2), x.group(3)),
                instante(m, x.group(4), x.group(5), x.group(6)), nodos);
    }
}
