package pe.pucp.paqrap.app.datos;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;

/** Fuente de pedidos y bloqueos a partir de los archivos de la carpeta datos/. */
public final class FuenteDatos implements AutoCloseable {
    private final LectorMensual<Formato.LineaPedido> pedidos;
    private final LectorMensual<Bloqueo> bloqueos;

    public FuenteDatos(Path carpeta, LocalDateTime inicio) {
        YearMonth m = YearMonth.from(inicio);
        pedidos = new LectorMensual<>(carpeta, m, Formato::archivoVentas, Formato::pedido, Formato.LineaPedido::registro);
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
        return pedidos.leerHasta(t);
    }

    public List<Bloqueo> bloqueosHasta(LocalDateTime t) throws IOException {
        return bloqueos.leerHasta(t);
    }

    public long lineasConError() {
        return pedidos.errores() + bloqueos.errores();
    }

    @Override
    public void close() throws IOException {
        pedidos.cerrar();
        bloqueos.cerrar();
    }
}
