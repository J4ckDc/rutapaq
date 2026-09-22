package pe.pucp.paqrap.planner.alns.repair;

import java.util.Comparator;
import java.util.List;
import java.util.random.RandomGenerator;

import pe.pucp.paqrap.planner.alns.EstadoALNS;
import pe.pucp.paqrap.planner.alns.Insercion;
import pe.pucp.paqrap.planner.alns.OperadorRepair;
import pe.pucp.paqrap.planner.model.Pedido;

/**
 * Insercion voraz de menor costo. Ordena el banco por fecha limite ascendente,
 * de modo que los pedidos priorizados de 4 y 8 horas ocupan las posiciones mas
 * favorables antes que los regulares de 36 horas. Es ademas el procedimiento
 * que construye la solucion inicial factible del ALNS.
 */
public final class InsercionCostoMinimo implements OperadorRepair {

    @Override
    public String nombre() {
        return "CostMinimizingInsertion";
    }

    @Override
    public void reparar(EstadoALNS estado, List<Pedido> banco, RandomGenerator rnd) {
        banco.sort(Comparator.comparing(Pedido::getFechaHoraLimite)
                .thenComparing(Pedido::getIdPedido));
        for (Pedido pedido : banco) {
            Insercion ins = estado.mejorInsercion(pedido);
            if (ins == null) {
                ins = estado.abrirRutaNueva(pedido);
            }
            if (ins == null || !estado.aplicar(ins)) {
                estado.marcarNoAtendido(pedido);
            }
        }
        banco.clear();
    }

    @Override
    public String toString() {
        return nombre();
    }
}
