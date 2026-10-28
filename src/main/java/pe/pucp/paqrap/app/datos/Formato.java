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
 * Formato de los archivos de datos del curso (versión v20260909 publicada por el profesor):
 *
 * ventas.aaaamm.txt   ##d##h##m:x,y,idCliente,cantidad,plazoHoras         01d01h30m:56,30,c4910,02,36
 * bloqueo.aamm.txt    ##d##h##m-##d##h##m:x1,y1,x2,y2,...                 01d02h22m-01d04h42m:25,45,45,45,45,40
 *
 * v02: se aceptan las letras d/h/m en minúscula o mayúscula y la "m" final es opcional, de modo que
 * también se leen los archivos sintéticos antiguos (01D01H052). La v01 solo aceptaba el formato
 * sintético y descartaba en silencio todas las líneas de los archivos del profesor.
 *
 * Se lee línea por línea (ver LectorMensual), no el archivo completo, para que el volumen de
 * registros no afecte la memoria del servidor.
 */
public final class Formato {
    /** Instante dentro del mes: día, hora y minuto. Acepta 01d01h30m y 01D01H030. */
    private static final String INSTANTE = "(\\d+)[dD](\\d+)[hH](\\d+)[mM]?";
    private static final Pattern VENTA =
            Pattern.compile("^\\s*" + INSTANTE + ":([^,]+),([^,]+),([^,]+),(\\d+),(\\d+)\\s*$");
    private static final Pattern BLOQUEO =
            Pattern.compile("^\\s*" + INSTANTE + "-" + INSTANTE + ":(.+)$");
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
        List<Nodo> vertices = new ArrayList<>();
        for (int i = 0; i + 1 < puntos.length; i += 2) vertices.add(new Nodo(puntos[i], puntos[i + 1]));
        List<Nodo> nodos = new ArrayList<>();
        for (int n : LectorBloqueos.nodosDePoligonal(puntos, INDICES)) nodos.add(new Nodo(INDICES.x(n), INDICES.y(n)));
        return new Bloqueo(instante(m, x.group(1), x.group(2), x.group(3)),
                instante(m, x.group(4), x.group(5), x.group(6)), vertices, nodos);
    }
}
