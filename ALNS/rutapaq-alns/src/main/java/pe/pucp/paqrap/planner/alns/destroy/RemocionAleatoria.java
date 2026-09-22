package pe.pucp.paqrap.planner.alns.destroy;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

import pe.pucp.paqrap.planner.alns.EstadoALNS;
import pe.pucp.paqrap.planner.alns.OperadorDestroy;
import pe.pucp.paqrap.planner.alns.VisitaUbicada;
import pe.pucp.paqrap.planner.model.Pedido;

/** Remocion aleatoria: diversificacion pura del vecindario. */
public final class RemocionAleatoria implements OperadorDestroy {

    @Override
    public String nombre() {
        return "RandomRemoval";
    }

    @Override
    public List<Pedido> destruir(EstadoALNS estado, int q, RandomGenerator rnd) {
        List<Pedido> banco = new ArrayList<>();
        List<VisitaUbicada> libres = estado.visitasLibres();
        int limite = Math.min(q, libres.size());
        // Barajado parcial de Fisher-Yates con el generador del algoritmo:
        // conserva la reproducibilidad de la corrida bajo una misma semilla.
        for (int i = 0; i < limite; i++) {
            int j = i + rnd.nextInt(libres.size() - i);
            VisitaUbicada u = libres.get(j);
            libres.set(j, libres.get(i));
            libres.set(i, u);
            Pedido p = estado.remover(u.ruta(), u.visita());
            if (p != null) {
                banco.add(p);
            }
        }
        return banco;
    }

    @Override
    public String toString() {
        return nombre();
    }
}
