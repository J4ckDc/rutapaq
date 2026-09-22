package pe.pucp.paqrap.planner.alns;

import java.util.List;
import java.util.random.RandomGenerator;

import pe.pucp.paqrap.planner.model.Pedido;

/**
 * Operador de reparacion: reinserta los pedidos del banco verificando en cada
 * intento la factibilidad de capacidad, stock, ventana de tiempo, turno y
 * refrigerio mediante ValidadorFactibilidad.
 */
public interface OperadorRepair {

    String nombre();

    void reparar(EstadoALNS estado, List<Pedido> banco, RandomGenerator rnd);
}
