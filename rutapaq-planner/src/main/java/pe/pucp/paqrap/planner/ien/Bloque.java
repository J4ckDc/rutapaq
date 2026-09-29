package pe.pucp.paqrap.planner.ien;

import java.time.LocalDateTime;
import java.util.List;

import pe.pucp.paqrap.planner.core.CalendarioBloqueos;
import pe.pucp.paqrap.planner.model.Pedido;

/** Instancia del experimento: pedidos y bloqueos de una ventana de 48 horas. */
public record Bloque(DefinicionBloque definicion,
                     LocalDateTime t0,
                     LocalDateTime fin,
                     List<Pedido> pedidos,
                     CalendarioBloqueos bloqueos,
                     int productos) {

    public double productosPorDia() {
        double dias = java.time.Duration.between(t0, fin).toMinutes() / (60.0 * 24.0);
        return productos / dias;
    }
}
