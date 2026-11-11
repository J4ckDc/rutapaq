package pe.pucp.paqrap.app.datos;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;

/**
 * Fuente de pedidos y bloqueos a partir de los archivos de la carpeta datos/.
 *
 * S13v1: en la operación día a día no se leen los archivos de ventas (los pedidos solo llegan por el registro en
 * línea); los bloqueos sí se leen de bloqueo.aamm.txt, porque son planificados con antelación (P&R 7).
 */
public final class FuenteDatos implements AutoCloseable {
    private final LectorMensual<Formato.LineaPedido> pedidos;   // null: sin archivos de ventas (día a día)
    private final LectorMensual<Bloqueo> bloqueos;

    public FuenteDatos(Path carpeta, LocalDateTime inicio) {
        this(carpeta, inicio, true);
    }

    /** S13v1: conVentas = false para la operación día a día. */
    public FuenteDatos(Path carpeta, LocalDateTime inicio, boolean conVentas) {
        YearMonth m = YearMonth.from(inicio);
        pedidos = conVentas ? new LectorMensual<>(carpeta, m, Formato::archivoVentas, Formato::pedido, Formato.LineaPedido::registro) : null;
        bloqueos = new LectorMensual<>(carpeta, m, Formato::archivoBloqueos, Formato::bloqueo, Bloqueo::inicio);
    }

    /**
     * v04: instante en que se acaban los datos de ventas de la carpeta (inicio del mes siguiente al último archivo
     * ventas.aaaamm.txt); null si la carpeta no tiene ninguno. Lo usa la simulación hasta el colapso para terminar
     * cuando ya no llegan más pedidos.
     */
    public static LocalDateTime finDeVentas(Path carpeta) {
        YearMonth ultimo = null;
        try (java.nio.file.DirectoryStream<Path> ds = java.nio.file.Files.newDirectoryStream(carpeta, "ventas.*.txt")) {
            for (Path p : ds) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("ventas\\.(\\d{4})(\\d{2})\\.txt")
                        .matcher(p.getFileName().toString());
                if (!m.matches()) continue;
                YearMonth ym = YearMonth.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
                if (ultimo == null || ym.isAfter(ultimo)) ultimo = ym;
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
        return ultimo == null ? null : ultimo.plusMonths(1).atDay(1).atStartOfDay();
    }

    public List<Formato.LineaPedido> pedidosHasta(LocalDateTime t) throws IOException {
        return pedidos == null ? List.of() : pedidos.leerHasta(t);
    }

    public List<Bloqueo> bloqueosHasta(LocalDateTime t) throws IOException {
        return bloqueos.leerHasta(t);
    }

    public long lineasConError() {
        return (pedidos == null ? 0 : pedidos.errores()) + bloqueos.errores();
    }

    @Override
    public void close() throws IOException {
        if (pedidos != null) pedidos.cerrar();
        bloqueos.cerrar();
    }
}
