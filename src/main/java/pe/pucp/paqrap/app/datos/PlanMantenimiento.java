package pe.pucp.paqrap.app.datos;

import pe.pucp.paqrap.app.modelo.TipoUnidad;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Plan de mantenimiento preventivo (v02), según las respuestas del curso:
 *
 *   Archivo:  mant.preventivo.m1.m2  (p. ej. mant.preventivo.09.10.txt), registro aaaammdd:TTNN
 *   - El mantenimiento empieza a las 00:00 del día indicado; la unidad no está disponible para la programación de rutas.
 *   - El plan es bimensual y se repite cada 2 meses: lo indicado para setiembre (mes impar) vale
 *     para todos los meses impares y lo indicado para octubre (mes par), para todos los pares.
 *
 * v0.5: la duración depende del tipo de unidad y se configura en horas (mantenimiento.horas.auto, .moto, .bici).
 * S13v1: por defecto 48 h para todos los tipos, según la respuesta del profesor a la P&R 19 (03/11/2026): "todos los
 * mantenimientos planificados, independiente del tipo de unidad, no están disponibles por 48 horas".
 *
 * Se leen todos los archivos de la carpeta de datos cuyo nombre empieza con "mant.preventivo".
 */
public final class PlanMantenimiento {
    /** S13v1: duración por defecto del mantenimiento, en horas (P&R 19, 03/11/2026). */
    public static final double HORAS_POR_DEFECTO = 48.0;

    private static final DateTimeFormatter AAAAMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** paridad del mes (0 par, 1 impar) → día del mes → unidades en mantenimiento. */
    private final Map<Integer, Map<Integer, Set<String>>> plan = new HashMap<>();
    /** v0.5: duración del mantenimiento por tipo de unidad, en horas. */
    private final Map<TipoUnidad, Double> horas = new EnumMap<>(TipoUnidad.class);
    private int registros;
    private int errores;

    public PlanMantenimiento(Path carpeta) {
        this(carpeta, Map.of());
    }

    /** @param horasPorTipo duración en horas por tipo; los tipos que falten usan HORAS_POR_DEFECTO (48 h). */
    public PlanMantenimiento(Path carpeta, Map<TipoUnidad, Double> horasPorTipo) {
        for (TipoUnidad t : TipoUnidad.values()) {
            double h = horasPorTipo.getOrDefault(t, HORAS_POR_DEFECTO);
            if (h <= 0) throw new IllegalArgumentException("La duración del mantenimiento debe ser positiva: " + t);
            horas.put(t, h);
        }
        if (!Files.isDirectory(carpeta)) return;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(carpeta, "mant.preventivo*")) {
            for (Path p : ds) leer(p);
        } catch (IOException e) {
            System.err.println("No se pudo leer el plan de mantenimiento: " + e);
        }
        if (registros > 0) System.out.printf("Mantenimiento preventivo: %d registros; duración (h) %s%n", registros, horas);
    }

    private void leer(Path archivo) throws IOException {
        List<String> lineas = Files.readAllLines(archivo, StandardCharsets.UTF_8);
        for (String l : lineas) {
            String linea = l.trim();
            if (linea.isEmpty() || linea.startsWith("#")) continue;
            try {
                String[] partes = linea.split(":");
                LocalDate fecha = LocalDate.parse(partes[0].trim(), AAAAMMDD);
                String unidad = partes[1].trim().toUpperCase();
                plan.computeIfAbsent(fecha.getMonthValue() % 2, k -> new HashMap<>())
                        .computeIfAbsent(fecha.getDayOfMonth(), k -> new HashSet<>()).add(unidad);
                registros++;
            } catch (RuntimeException e) {
                errores++;
                System.err.printf("Línea inválida en %s: %s%n", archivo.getFileName(), linea);
            }
        }
    }

    /** ¿El plan indica que el mantenimiento de la unidad empieza ese día (a las 00:00)? */
    public boolean programado(String unidad, LocalDate dia) {
        Map<Integer, Set<String>> mes = plan.get(dia.getMonthValue() % 2);
        if (mes == null) return false;
        Set<String> u = mes.get(dia.getDayOfMonth());
        return u != null && u.contains(unidad);
    }

    /** v0.5: duración del mantenimiento de la unidad, en segundos (según su tipo). */
    public long segundosDuracion(String unidad) {
        TipoUnidad t = TipoUnidad.deCodigo(unidad);
        return Math.round(horas.getOrDefault(t, HORAS_POR_DEFECTO) * 3600);
    }

    /** v0.5: ¿la unidad está en mantenimiento en ese instante? */
    public boolean enMantenimiento(String unidad, LocalDateTime instante) {
        return finMantenimiento(unidad, instante) != null;
    }

    /**
     * v0.5: fin del mantenimiento que está en curso en ese instante (si dos mantenimientos se encadenan, el fin del
     * último); null si la unidad no está en mantenimiento.
     */
    public LocalDateTime finMantenimiento(String unidad, LocalDateTime instante) {
        long dur = segundosDuracion(unidad);
        LocalDateTime fin = null;
        int diasAtras = (int) Math.ceil(dur / 86400.0);
        for (int k = diasAtras; k >= 0; k--) {
            LocalDate d = instante.toLocalDate().minusDays(k);
            if (!programado(unidad, d)) continue;
            LocalDateTime ini = d.atStartOfDay(), f = ini.plusSeconds(dur);
            if (!instante.isBefore(ini) && instante.isBefore(f)) fin = f;
        }
        // mantenimientos encadenados: el siguiente empieza antes de que termine el actual
        for (int guardia = 0; fin != null && guardia < 62; guardia++) {
            LocalDateTime siguiente = null;
            for (LocalDate d = fin.toLocalDate().minusDays(diasAtras); !d.atStartOfDay().isAfter(fin); d = d.plusDays(1)) {
                LocalDateTime ini = d.atStartOfDay(), f = ini.plusSeconds(dur);
                if (programado(unidad, d) && !ini.isAfter(fin) && f.isAfter(fin)) siguiente = f;
            }
            if (siguiente == null) break;
            fin = siguiente;
        }
        return fin;
    }

    /** ¿Empieza un mantenimiento de la unidad (a las 00:00) dentro del intervalo (desde, hasta]? */
    public boolean empiezaEntre(String unidad, LocalDateTime desde, LocalDateTime hasta) {
        for (LocalDate d = desde.toLocalDate().plusDays(1); !d.atStartOfDay().isAfter(hasta); d = d.plusDays(1)) {
            if (programado(unidad, d)) return true;
        }
        return false;
    }

    public int registros() {
        return registros;
    }

    public int errores() {
        return errores;
    }
}
