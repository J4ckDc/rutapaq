package pe.pucp.paqrap.app.datos;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;

/**
 * Fuente de pedidos y bloqueos a partir de los archivos de la carpeta datos/.
 * v03: también las averías programadas (averias.aaaamm.txt), solo en los escenarios que las usan.
 */
public final class FuenteDatos implements AutoCloseable {
    private final LectorMensual<Formato.LineaPedido> pedidos;
    private final LectorMensual<Bloqueo> bloqueos;
    private final LectorMensual<Formato.LineaAveria> averias;     // null: el escenario no lee averías programadas

    public FuenteDatos(Path carpeta, LocalDateTime inicio) {
        this(carpeta, inicio, false);
    }

    public FuenteDatos(Path carpeta, LocalDateTime inicio, boolean conAverias) {
        YearMonth m = YearMonth.from(inicio);
        pedidos = new LectorMensual<>(carpeta, m, Formato::archivoVentas, Formato::pedido, Formato.LineaPedido::registro);
        bloqueos = new LectorMensual<>(carpeta, m, Formato::archivoBloqueos, Formato::bloqueo, Bloqueo::inicio);
        averias = conAverias
                ? new LectorMensual<>(carpeta, m, Formato::archivoAverias, Formato::averia, Formato.LineaAveria::instante)
                : null;
    }

    public List<Formato.LineaPedido> pedidosHasta(LocalDateTime t) throws IOException {
        return pedidos.leerHasta(t);
    }

    public List<Bloqueo> bloqueosHasta(LocalDateTime t) throws IOException {
        return bloqueos.leerHasta(t);
    }

    /** v03: averías programadas cuyo instante ya llegó (lista vacía si el escenario no las usa). */
    public List<Formato.LineaAveria> averiasHasta(LocalDateTime t) throws IOException {
        return averias == null ? List.of() : averias.leerHasta(t);
    }

    public long lineasConError() {
        return pedidos.errores() + bloqueos.errores() + (averias == null ? 0 : averias.errores());
    }

    @Override
    public void close() throws IOException {
        pedidos.cerrar();
        bloqueos.cerrar();
        if (averias != null) averias.cerrar();
    }
}
