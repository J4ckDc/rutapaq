package pe.pucp.paqrap.planner.ien;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import pe.pucp.paqrap.planner.model.ParadaRuta;
import pe.pucp.paqrap.planner.model.Pedido;
import pe.pucp.paqrap.planner.model.PlanDistribucion;
import pe.pucp.paqrap.planner.model.Ruta;
import pe.pucp.paqrap.planner.model.TipoParada;

/**
 * Auditoria independiente del plan que entrega cada ejecucion: ninguna unidad
 * debe exceder su capacidad entre recargas, ningun pedido debe aparecer dos
 * veces y ningun pedido de la cartera puede desaparecer del plan.
 */
public final class AuditorPlan {

    private AuditorPlan() {
    }

    public static List<String> verificar(PlanDistribucion plan, List<Pedido> cartera) {
        List<String> hallazgos = new ArrayList<>();
        Set<String> planificados = new HashSet<>();
        for (Ruta ruta : plan.getRutas()) {
            int carga = 0;
            for (ParadaRuta parada : ruta.getItinerario()) {
                if (parada.tipo() == TipoParada.RECARGA) {
                    carga = parada.cantidad();
                } else if (parada.tipo() == TipoParada.ENTREGA) {
                    carga -= parada.cantidad();
                    if (carga < 0) {
                        hallazgos.add("Carga negativa en " + ruta.getId() + " (" + parada.referencia() + ")");
                    }
                    if (!planificados.add(parada.referencia())) {
                        hallazgos.add("Pedido duplicado en el plan: " + parada.referencia());
                    }
                }
            }
            if (!ruta.isFactible()) {
                hallazgos.add("Ruta infactible en el plan: " + ruta.getId());
            }
        }
        Set<String> sinPlanificar = new HashSet<>();
        for (Pedido p : plan.getNoPlanificados()) {
            sinPlanificar.add(p.getId());
        }
        for (Pedido p : cartera) {
            if (!planificados.contains(p.getId()) && !sinPlanificar.contains(p.getId())) {
                hallazgos.add("Pedido extraviado: " + p.getId());
            }
        }
        return hallazgos;
    }
}
