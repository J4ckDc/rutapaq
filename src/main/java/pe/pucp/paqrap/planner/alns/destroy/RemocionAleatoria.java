package pe.pucp.paqrap.planner.alns.destroy;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

import pe.pucp.paqrap.planner.alns.OperadorDestroy;
import pe.pucp.paqrap.planner.core.SolucionRutas;

/** Remocion aleatoria: diversificacion pura del vecindario. */
public final class RemocionAleatoria implements OperadorDestroy {

    @Override
    public String nombre() {
        return "RandomRemoval";
    }

    @Override
    public List<Integer> destruir(SolucionRutas s, int q, RandomGenerator rnd) {
        List<Integer> asignados = new ArrayList<>();
        for (int p = 0; p < s.datos().n; p++) {
            if (s.asignado(p)) {
                asignados.add(p);
            }
        }
        List<Integer> banco = new ArrayList<>();
        int limite = Math.min(q, asignados.size());
        for (int i = 0; i < limite; i++) {
            int j = i + rnd.nextInt(asignados.size() - i);
            int p = asignados.get(j);
            asignados.set(j, asignados.get(i));
            asignados.set(i, p);
            if (s.remover(p)) {
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
