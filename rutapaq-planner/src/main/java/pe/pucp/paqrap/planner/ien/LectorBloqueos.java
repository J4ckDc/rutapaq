package pe.pucp.paqrap.planner.ien;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import pe.pucp.paqrap.planner.core.CalendarioBloqueos;
import pe.pucp.paqrap.planner.core.MapaReticula;

/**
 * Lector de los bloqueos planificados de la municipalidad. Cada registro tiene
 * la forma {@code ddDhhHmmm-ddDhhHmmm:x1,y1,x2,y2,...}: el primer instante es el
 * inicio y el segundo el fin del bloqueo, y los puntos son los vertices de una
 * poligonal abierta cuyos tramos horizontales o verticales quedan bloqueados
 * nodo por nodo, incluidos extremos y vertices.
 */
public final class LectorBloqueos {

    private static final Pattern PATRON =
            Pattern.compile("^\\s*(\\d+)D(\\d+)H(\\d+)-(\\d+)D(\\d+)H(\\d+):(.+)$");

    private LectorBloqueos() {
    }

    public static String nombreArchivo(int anio, int mes) {
        return String.format("bloqueo.%02d%02d.txt", anio % 100, mes);
    }

    public static CalendarioBloqueos leer(Path archivo, int anio, int mes, MapaReticula mapa) {
        CalendarioBloqueos calendario = new CalendarioBloqueos();
        if (!Files.exists(archivo)) {
            return calendario;
        }
        LocalDate primerDia = LocalDate.of(anio, mes, 1);
        try (var lineas = Files.lines(archivo, StandardCharsets.UTF_8)) {
            lineas.forEach(linea -> {
                if (linea.isBlank()) {
                    return;
                }
                Matcher m = PATRON.matcher(linea.trim());
                if (!m.matches()) {
                    throw new IllegalArgumentException("Registro de bloqueo invalido: " + linea);
                }
                LocalDateTime inicio = primerDia.plusDays(Integer.parseInt(m.group(1)) - 1L).atStartOfDay()
                        .plusHours(Integer.parseInt(m.group(2))).plusMinutes(Integer.parseInt(m.group(3)));
                LocalDateTime fin = primerDia.plusDays(Integer.parseInt(m.group(4)) - 1L).atStartOfDay()
                        .plusHours(Integer.parseInt(m.group(5))).plusMinutes(Integer.parseInt(m.group(6)));
                String[] coords = m.group(7).split(",");
                int[] puntos = new int[coords.length];
                for (int i = 0; i < coords.length; i++) {
                    puntos[i] = Integer.parseInt(coords[i].trim());
                }
                calendario.agregar(new CalendarioBloqueos.Bloqueo(inicio, fin, nodosDePoligonal(puntos, mapa)));
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return calendario;
    }

    /** Nodos de todos los tramos horizontales o verticales de la poligonal abierta. */
    public static int[] nodosDePoligonal(int[] puntos, MapaReticula mapa) {
        Set<Integer> nodos = new LinkedHashSet<>();
        for (int i = 0; i + 3 < puntos.length; i += 2) {
            int x1 = puntos[i];
            int y1 = puntos[i + 1];
            int x2 = puntos[i + 2];
            int y2 = puntos[i + 3];
            if (x1 == x2) {
                for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
                    nodos.add(mapa.indice(x1, y));
                }
            } else if (y1 == y2) {
                for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
                    nodos.add(mapa.indice(x, y1));
                }
            } else {
                throw new IllegalArgumentException("Tramo no horizontal ni vertical: ("
                        + x1 + "," + y1 + ")-(" + x2 + "," + y2 + ")");
            }
        }
        int[] r = new int[nodos.size()];
        int i = 0;
        for (int n : nodos) {
            r[i++] = n;
        }
        return r;
    }

    public static List<int[]> nodosDeTodos(CalendarioBloqueos c) {
        List<int[]> l = new ArrayList<>();
        for (CalendarioBloqueos.Bloqueo b : c.getBloqueos()) {
            l.add(b.nodos());
        }
        return l;
    }
}
