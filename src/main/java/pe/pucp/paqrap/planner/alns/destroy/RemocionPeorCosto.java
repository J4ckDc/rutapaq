package pe.pucp.paqrap.planner.alns.destroy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.random.RandomGenerator;

import pe.pucp.paqrap.planner.alns.OperadorDestroy;
import pe.pucp.paqrap.planner.core.SolucionRutas;

/**
 * Remocion del peor: retira los pedidos de mayor costo marginal. El sesgo hacia
 * los peores se controla con el factor de determinismo p mediante el indice
 * floor(|L| * U(0,1)^p).
 */
public final class RemocionPeorCosto implements OperadorDestroy {

    private final double pDeterminismo;

    public RemocionPeorCosto(double pDeterminismo) {
        this.pDeterminismo = pDeterminismo;
    }

    @Override
    public String nombre() {
        return "WorstCostRemoval";
    }

    @Override
    public List<Integer> destruir(SolucionRutas s, int q, RandomGenerator rnd) {
        List<Integer> banco = new ArrayList<>();
        while (banco.size() < q) {
            List<Integer> asignados = new ArrayList<>();
            for (int p = 0; p < s.datos().n; p++) {
                if (s.asignado(p)) {
                    asignados.add(p);
                }
            }
            if (asignados.isEmpty()) {
                break;
            }
            asignados.sort(Comparator.comparingDouble((Integer p) -> s.ahorroRemocion(p)).reversed());
            int idx = Math.min(asignados.size() - 1,
                    (int) (asignados.size() * Math.pow(rnd.nextDouble(), pDeterminismo)));
            int p = asignados.get(idx);
            if (!s.remover(p)) {
                break;
            }
            banco.add(p);
        }
        return banco;
    }

    @Override
    public String toString() {
        return nombre();
    }
}
