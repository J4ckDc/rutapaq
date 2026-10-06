package pe.pucp.paqrap.app.datos;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Lee archivos mensuales línea por línea a medida que avanza el reloj simulado.
 * Nunca carga el archivo completo: abre directamente el mes de inicio y mantiene en memoria
 * solo la línea siguiente. Así funciona igual con miles o con millones de registros.
 */
final class LectorMensual<T> {
    private final Path carpeta;
    private final Function<YearMonth, String> nombre;
    private final BiFunction<YearMonth, String, T> parser;
    private final Function<T, LocalDateTime> instante;

    private YearMonth mes;
    private BufferedReader lector;
    private T siguiente;
    private long errores;

    LectorMensual(Path carpeta, YearMonth mesInicial, Function<YearMonth, String> nombre,
                  BiFunction<YearMonth, String, T> parser, Function<T, LocalDateTime> instante) {
        this.carpeta = carpeta;
        this.mes = mesInicial;
        this.nombre = nombre;
        this.parser = parser;
        this.instante = instante;
    }

    /** Devuelve los registros cuyo instante es menor o igual a t. */
    List<T> leerHasta(LocalDateTime t) throws IOException {
        List<T> r = new ArrayList<>();
        while (true) {
            if (siguiente == null) siguiente = leerLinea(t);
            if (siguiente == null) break;
            if (instante.apply(siguiente).isAfter(t)) break;
            r.add(siguiente);
            siguiente = null;
        }
        return r;
    }

    long errores() {
        return errores;
    }

    private T leerLinea(LocalDateTime t) throws IOException {
        while (true) {
            if (lector == null) {
                if (mes.atDay(1).atStartOfDay().isAfter(t)) return null;   // aún no toca abrir ese mes
                Path p = carpeta.resolve(nombre.apply(mes));
                if (!Files.exists(p)) {                                     // mes sin archivo: se salta
                    mes = mes.plusMonths(1);
                    continue;
                }
                lector = Files.newBufferedReader(p, StandardCharsets.UTF_8);
            }
            String linea = lector.readLine();
            if (linea == null) {
                lector.close();
                lector = null;
                mes = mes.plusMonths(1);
                continue;
            }
            linea = linea.trim();
            if (linea.isEmpty() || linea.startsWith("#")) continue;
            try {
                return parser.apply(mes, linea);
            } catch (RuntimeException e) {
                errores++;
            }
        }
    }

    void cerrar() throws IOException {
        if (lector != null) lector.close();
    }
}
