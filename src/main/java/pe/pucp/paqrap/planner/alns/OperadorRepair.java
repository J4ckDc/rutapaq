package pe.pucp.paqrap.planner.alns;

import java.util.List;
import java.util.random.RandomGenerator;

import pe.pucp.paqrap.planner.core.SolucionRutas;

/**
 * Operador de reparacion: reinserta los pedidos del banco. Toda insercion se
 * valida con el evaluador comun, que verifica capacidad, saldo de almacenes,
 * plazo, jornada y refrigerio, e inserta las recargas necesarias.
 */
public interface OperadorRepair {

    String nombre();

    void reparar(SolucionRutas s, List<Integer> banco, RandomGenerator rnd);
}
