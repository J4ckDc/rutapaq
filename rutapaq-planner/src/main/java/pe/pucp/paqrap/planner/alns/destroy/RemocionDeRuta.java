package pe.pucp.paqrap.planner.alns.destroy;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

import pe.pucp.paqrap.planner.alns.OperadorDestroy;
import pe.pucp.paqrap.planner.core.SolucionRutas;

/**
 * Remocion de ruta completa: libera una unidad entera y permite que la
 * reparacion redistribuya su carga entre unidades de otro tipo, lo que resulta
 * decisivo para el equilibrio de costos de la flota heterogenea.
 */
public final class RemocionDeRuta implements OperadorDestroy {

    @Override
    public String nombre() {
        return "RouteRemoval";
    }

    @Override
    public List<Integer> destruir(SolucionRutas s, int q, RandomGenerator rnd) {
        List<Integer> banco = new ArrayList<>();
        List<Integer> unidades = new ArrayList<>();
        for (int u = 0; u < s.datos().K; u++) {
            if (s.secuencia(u).length > 0) {
                unidades.add(u);
            }
        }
        while (banco.size() < q && !unidades.isEmpty()) {
            int u = unidades.remove(rnd.nextInt(unidades.size()));
            for (int p : s.secuencia(u).clone()) {
                if (s.remover(p)) {
                    banco.add(p);
                }
            }
        }
        return banco;
    }

    @Override
    public String toString() {
        return nombre();
    }
}
