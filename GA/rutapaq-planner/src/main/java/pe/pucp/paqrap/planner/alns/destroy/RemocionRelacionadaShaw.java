package pe.pucp.paqrap.planner.alns.destroy;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

import pe.pucp.paqrap.planner.alns.EstadoALNS;
import pe.pucp.paqrap.planner.alns.OperadorDestroy;
import pe.pucp.paqrap.planner.alns.VisitaUbicada;
import pe.pucp.paqrap.planner.core.RedVial;
import pe.pucp.paqrap.planner.core.ValidadorFactibilidad;
import pe.pucp.paqrap.planner.model.Pedido;
import pe.pucp.paqrap.planner.model.VisitaCliente;

/**
 * Remocion por afinidad espacio-temporal (Shaw) orientada a ventanas de tiempo:
 * R(v) = phi1 * dist(base, v) / distMax + phi2 * |llegada(base) - llegada(v)| / H
 * + phi3 * |limite(base) - limite(v)| / H. Extrae pedidos proximos en espacio y
 * en plazo, que son los que admiten recombinacion util en la reparacion.
 */
public final class RemocionRelacionadaShaw implements OperadorDestroy {

    @Override
    public String nombre() {
        return "TimeWindowRelatedRemoval";
    }

    @Override
    public List<Pedido> destruir(EstadoALNS estado, int q, RandomGenerator rnd) {
        List<Pedido> banco = new ArrayList<>();
        List<VisitaUbicada> libres = estado.visitasLibres();
        if (libres.isEmpty()) {
            return banco;
        }
        RedVial red = estado.getContexto().getRedVial();
        double phi1 = estado.getConfiguracion().phi1();
        double phi2 = estado.getConfiguracion().phi2();
        double phi3 = estado.getConfiguracion().phi3();
        double horizonte = Math.max(1.0, estado.getConfiguracion().horizonteShawHoras());

        VisitaUbicada semilla = libres.get(rnd.nextInt(libres.size()));
        VisitaCliente referencia = semilla.visita();
        Pedido primero = estado.remover(semilla.ruta(), semilla.visita());
        if (primero == null) {
            return banco;
        }
        banco.add(primero);

        while (banco.size() < q) {
            List<VisitaUbicada> restantes = estado.visitasLibres();
            if (restantes.isEmpty()) {
                break;
            }
            double distMax = 1.0E-6;
            for (VisitaUbicada u : restantes) {
                double d = red.distanciaKm(referencia.getNodoCliente(), u.visita().getNodoCliente());
                if (!Double.isInfinite(d)) {
                    distMax = Math.max(distMax, d);
                }
            }
            VisitaUbicada mejor = null;
            double mejorR = Double.POSITIVE_INFINITY;
            for (VisitaUbicada u : restantes) {
                double d = red.distanciaKm(referencia.getNodoCliente(), u.visita().getNodoCliente());
                if (Double.isInfinite(d)) {
                    continue;
                }
                double dt = 0.0;
                if (referencia.getHoraLlegadaEstimada() != null && u.visita().getHoraLlegadaEstimada() != null) {
                    dt = Math.abs(ValidadorFactibilidad.horasEntre(
                            referencia.getHoraLlegadaEstimada(), u.visita().getHoraLlegadaEstimada()));
                }
                double dl = Math.abs(ValidadorFactibilidad.horasEntre(
                        referencia.getPedido().getFechaHoraLimite(),
                        u.visita().getPedido().getFechaHoraLimite()));
                double r = phi1 * (d / distMax) + phi2 * (dt / horizonte) + phi3 * (dl / horizonte);
                if (r < mejorR) {
                    mejorR = r;
                    mejor = u;
                }
            }
            if (mejor == null) {
                break;
            }
            Pedido p = estado.remover(mejor.ruta(), mejor.visita());
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
