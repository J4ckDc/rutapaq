package pe.pucp.paqrap.planner.alns.destroy;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

import pe.pucp.paqrap.planner.alns.EstadoALNS;
import pe.pucp.paqrap.planner.alns.OperadorDestroy;
import pe.pucp.paqrap.planner.model.Pedido;
import pe.pucp.paqrap.planner.model.Ruta;
import pe.pucp.paqrap.planner.model.VisitaCliente;

/**
 * Remocion de ruta completa: libera una unidad entera y permite que la
 * reparacion reasigne su carga a unidades de otro tipo, lo que resulta decisivo
 * para el equilibrio de costos de la flota heterogenea.
 */
public final class RemocionDeRuta implements OperadorDestroy {

    @Override
    public String nombre() {
        return "RouteRemoval";
    }

    @Override
    public List<Pedido> destruir(EstadoALNS estado, int q, RandomGenerator rnd) {
        List<Pedido> banco = new ArrayList<>();
        List<Ruta> candidatas = new ArrayList<>();
        for (Ruta r : estado.rutasModificables()) {
            if (r.tieneVisitasNoCongeladas()) {
                candidatas.add(r);
            }
        }
        while (banco.size() < q && !candidatas.isEmpty()) {
            Ruta ruta = candidatas.remove(rnd.nextInt(candidatas.size()));
            for (VisitaCliente v : ruta.visitasNoCongeladas()) {
                Pedido p = estado.remover(ruta, v);
                if (p != null) {
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
