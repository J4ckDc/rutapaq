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
