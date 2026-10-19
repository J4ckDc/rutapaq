package pe.pucp.paqrap.app.datos;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Plan de mantenimiento preventivo (v02), según las respuestas del curso:
 *
 *   Archivo:  mant.preventivo.m1.m2  (p. ej. mant.preventivo.09.10.txt), registro aaaammdd:TTNN
 *   - La unidad NO está disponible para la programación de rutas ese día, de 00:00 a 23:59.
 *   - El plan es bimensual y se repite cada 2 meses: lo indicado para setiembre (mes impar) vale
 *     para todos los meses impares y lo indicado para octubre (mes par), para todos los pares.
 *
 * Se leen todos los archivos de la carpeta de datos cuyo nombre empieza con "mant.preventivo".
 */
public final class PlanMantenimiento {
    private static final DateTimeFormatter AAAAMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** paridad del mes (0 par, 1 impar) → día del mes → unidades en mantenimiento. */
    private final Map<Integer, Map<Integer, Set<String>>> plan = new HashMap<>();
    private int registros;
    private int errores;

    public PlanMantenimiento(Path carpeta) {
        if (!Files.isDirectory(carpeta)) return;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(carpeta, "mant.preventivo*")) {
            for (Path p : ds) leer(p);
        } catch (IOException e) {
            System.err.println("No se pudo leer el plan de mantenimiento: " + e);
        }
        if (registros > 0) System.out.printf("Mantenimiento preventivo: %d registros%n", registros);
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

    /** ¿La unidad está en mantenimiento ese día? */
    public boolean enMantenimiento(String unidad, LocalDate dia) {
        Map<Integer, Set<String>> mes = plan.get(dia.getMonthValue() % 2);
        if (mes == null) return false;
        Set<String> u = mes.get(dia.getDayOfMonth());
        return u != null && u.contains(unidad);
    }

    /** ¿Empieza un mantenimiento de la unidad (a las 00:00) dentro del intervalo (desde, hasta]? */
    public boolean empiezaEntre(String unidad, LocalDateTime desde, LocalDateTime hasta) {
        for (LocalDate d = desde.toLocalDate().plusDays(1); !d.atStartOfDay().isAfter(hasta); d = d.plusDays(1)) {
            if (enMantenimiento(unidad, d)) return true;
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
