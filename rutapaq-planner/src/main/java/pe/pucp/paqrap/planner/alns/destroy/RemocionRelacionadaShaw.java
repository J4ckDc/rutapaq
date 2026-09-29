package pe.pucp.paqrap.planner.alns.destroy;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

import pe.pucp.paqrap.planner.alns.OperadorDestroy;
import pe.pucp.paqrap.planner.core.DatosPlanificacion;
import pe.pucp.paqrap.planner.core.SolucionRutas;

/**
 * Remocion por afinidad espacio-temporal (Shaw) orientada a los plazos:
 * R(v) = phi1 * dist(base, v) / distMax + phi2 * |limite(base) - limite(v)| / H.
 * Extrae pedidos proximos en espacio y en vencimiento, que son los que admiten
 * recombinacion util durante la reparacion.
 */
public final class RemocionRelacionadaShaw implements OperadorDestroy {

    private final double phi1;
    private final double phi2;
    private final double horizonteHoras;

    public RemocionRelacionadaShaw(double phi1, double phi2, double horizonteHoras) {
        this.phi1 = phi1;
        this.phi2 = phi2;
        this.horizonteHoras = Math.max(1.0, horizonteHoras);
    }

    @Override
    public String nombre() {
        return "TimeWindowRelatedRemoval";
    }

    @Override
    public List<Integer> destruir(SolucionRutas s, int q, RandomGenerator rnd) {
        DatosPlanificacion d = s.datos();
        List<Integer> banco = new ArrayList<>();
        List<Integer> asignados = new ArrayList<>();
        for (int p = 0; p < d.n; p++) {
            if (s.asignado(p)) {
                asignados.add(p);
            }
        }
        if (asignados.isEmpty()) {
            return banco;
        }
        int semilla = asignados.get(rnd.nextInt(asignados.size()));
        if (!s.remover(semilla)) {
            return banco;
        }
        banco.add(semilla);
        while (banco.size() < q) {
            int mejor = -1;
            double mejorR = Double.POSITIVE_INFINITY;
            double distMax = 1.0;
            for (int p = 0; p < d.n; p++) {
                if (s.asignado(p)) {
                    int km = d.dist.km(d.nodoPedido[semilla], d.nodoPedido[p]);
                    if (km > distMax) {
                        distMax = km;
                    }
                }
            }
            for (int p = 0; p < d.n; p++) {
                if (!s.asignado(p)) {
                    continue;
                }
                int km = d.dist.km(d.nodoPedido[semilla], d.nodoPedido[p]);
                if (km < 0) {
                    continue;
                }
                double dl = Math.abs(d.limite[semilla] - d.limite[p]) / 3600.0;
                double r = phi1 * (km / distMax) + phi2 * (dl / horizonteHoras);
                if (r < mejorR) {
                    mejorR = r;
                    mejor = p;
                }
            }
            if (mejor < 0 || !s.remover(mejor)) {
                break;
            }
            banco.add(mejor);
        }
        return banco;
    }

    @Override
    public String toString() {
        return nombre();
    }
}
