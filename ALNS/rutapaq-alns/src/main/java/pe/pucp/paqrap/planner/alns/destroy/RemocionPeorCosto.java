package pe.pucp.paqrap.planner.alns.destroy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.random.RandomGenerator;

import pe.pucp.paqrap.planner.alns.EstadoALNS;
import pe.pucp.paqrap.planner.alns.OperadorDestroy;
import pe.pucp.paqrap.planner.alns.VisitaUbicada;
import pe.pucp.paqrap.planner.model.Pedido;

/**
 * Remocion del peor: elimina las visitas de mayor costo marginal. El sesgo hacia
 * las peores se controla con el factor de determinismo p: el indice elegido es
 * floor(|L| * U(0,1)^p), de modo que valores altos de p aproximan la seleccion a
 * un comportamiento determinista.
 */
public final class RemocionPeorCosto implements OperadorDestroy {

    @Override
    public String nombre() {
        return "WorstCostRemoval";
    }

    @Override
    public List<Pedido> destruir(EstadoALNS estado, int q, RandomGenerator rnd) {
        List<Pedido> banco = new ArrayList<>();
        double pDeterminismo = estado.getConfiguracion().pDeterminismo();
        while (banco.size() < q) {
            List<VisitaUbicada> libres = estado.visitasLibres();
            if (libres.isEmpty()) {
                break;
            }
            libres.sort(Comparator.comparingDouble(
                    (VisitaUbicada u) -> estado.ahorroRemocion(u.ruta(), u.visita())).reversed());
            int idx = (int) (libres.size() * Math.pow(rnd.nextDouble(), pDeterminismo));
            idx = Math.min(idx, libres.size() - 1);
            VisitaUbicada u = libres.get(idx);
            Pedido p = estado.remover(u.ruta(), u.visita());
            if (p == null) {
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
