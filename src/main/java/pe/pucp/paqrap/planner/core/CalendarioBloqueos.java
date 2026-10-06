package pe.pucp.paqrap.planner.core;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Bloqueos planificados de la municipalidad. Cada bloqueo tiene un instante de
 * inicio, uno de fin (excluido) y el conjunto de nodos de su poligonal abierta.
 * El calendario aplica sobre la reticula los bloqueos vigentes en un instante.
 */
public final class CalendarioBloqueos {

    /** Bloqueo planificado: vigente en [inicio, fin). */
    public record Bloqueo(LocalDateTime inicio, LocalDateTime fin, int[] nodos) {
    }

    private final List<Bloqueo> bloqueos = new ArrayList<>();

    public void agregar(Bloqueo b) {
        bloqueos.add(b);
    }

    public List<Bloqueo> getBloqueos() {
        return bloqueos;
    }

    public int cantidad() {
        return bloqueos.size();
    }

    /** Bloqueos que se solapan con la ventana [desde, hasta). */
    public CalendarioBloqueos enVentana(LocalDateTime desde, LocalDateTime hasta) {
        CalendarioBloqueos c = new CalendarioBloqueos();
        for (Bloqueo b : bloqueos) {
            if (b.inicio().isBefore(hasta) && b.fin().isAfter(desde)) {
                c.agregar(b);
            }
        }
        return c;
    }

    /** Deja en el mapa exactamente los nodos bloqueados vigentes en el instante dado. */
    public int aplicarEn(MapaReticula mapa, LocalDateTime instante) {
        mapa.limpiarBloqueos();
        int vigentes = 0;
        for (Bloqueo b : bloqueos) {
            if (!instante.isBefore(b.inicio()) && instante.isBefore(b.fin())) {
                vigentes++;
                for (int nodo : b.nodos()) {
                    mapa.bloquear(nodo);
                }
            }
        }
        return vigentes;
    }

    /** Instante del proximo cambio de vigencia posterior al instante dado (null si no hay). */
    public LocalDateTime proximoCambio(LocalDateTime instante) {
        LocalDateTime mejor = null;
        for (Bloqueo b : bloqueos) {
            for (LocalDateTime t : new LocalDateTime[]{b.inicio(), b.fin()}) {
                if (t.isAfter(instante) && (mejor == null || t.isBefore(mejor))) {
                    mejor = t;
                }
            }
        }
        return mejor;
    }
}
