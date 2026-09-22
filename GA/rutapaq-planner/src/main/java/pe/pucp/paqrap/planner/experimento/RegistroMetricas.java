package pe.pucp.paqrap.planner.experimento;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Registrador de las variables de respuesta del diseno de experimentos. Exporta
 * en formato CSV el costo total de operacion, la distancia total recorrida, el
 * porcentaje de pedidos entregados dentro de su ventana comprometida, el tiempo
 * de respuesta por reoptimizacion y la tasa de resolucion ante saturacion.
 */
public final class RegistroMetricas {

    public static final String CABECERA =
            "escenario,algoritmo,fase,replica,semilla,pedidos,vehiculos,rutas,"
            + "costoSoles,distanciaKm,pctSLA,noAtendidos,iteraciones,tiempoMs,semaforo,parametros";

    private final List<String> filas = new ArrayList<>();

    public void registrar(String escenario, String algoritmo, String fase, int replica, long semilla,
                          int pedidos, int vehiculos, int rutas, double costo, double distancia,
                          double pctSla, int noAtendidos, int iteraciones, long tiempoMs,
                          String semaforo, String parametros) {
        filas.add(String.format(Locale.US,
                "%s,%s,%s,%d,%d,%d,%d,%d,%.2f,%.3f,%.4f,%d,%d,%d,%s,%s",
                escenario, algoritmo, fase, replica, semilla, pedidos, vehiculos, rutas,
                costo, distancia, pctSla, noAtendidos, iteraciones, tiempoMs, semaforo, parametros));
    }

    public List<String> getFilas() {
        return filas;
    }

    public void exportar(Path destino) {
        try {
            if (destino.getParent() != null) {
                Files.createDirectories(destino.getParent());
            }
            List<String> contenido = new ArrayList<>();
            contenido.add(CABECERA);
            contenido.addAll(filas);
            Files.write(destino, contenido, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
