package pe.pucp.paqrap.planner.alns.repair;

import java.util.Comparator;
import java.util.List;
import java.util.random.RandomGenerator;

import pe.pucp.paqrap.planner.alns.OperadorRepair;
import pe.pucp.paqrap.planner.core.DatosPlanificacion;
import pe.pucp.paqrap.planner.core.SolucionRutas;

/**
 * Insercion voraz de menor costo. Ordena el banco por fecha limite ascendente,
 * de modo que los pedidos de 4 y 8 h ocupan las posiciones mas favorables antes
 * que los regulares de 36 h. Es tambien el procedimiento que construye la
 * solucion inicial del ALNS.
 */
public final class InsercionCostoMinimo implements OperadorRepair {

    @Override
    public String nombre() {
        return "CostMinimizingInsertion";
    }

    @Override
    public void reparar(SolucionRutas s, List<Integer> banco, RandomGenerator rnd) {
        DatosPlanificacion d = s.datos();
        banco.sort(Comparator.comparingLong((Integer p) -> d.limite[p]).thenComparingInt(p -> p));
        for (int p : banco) {
            int[] mejor = s.mejorInsercion(p);
            if (mejor != null) {
                s.insertar(p, mejor[0], mejor[1]);
            }
        }
        banco.clear();
    }

    @Override
    public String toString() {
        return nombre();
    }
}
