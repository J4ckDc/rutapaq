package pe.pucp.paqrap.planner.experimento;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import pe.pucp.paqrap.planner.core.ContextoPlanificacion;
import pe.pucp.paqrap.planner.core.ResultadoFactibilidad;
import pe.pucp.paqrap.planner.core.ValidadorFactibilidad;
import pe.pucp.paqrap.planner.model.Almacen;
import pe.pucp.paqrap.planner.model.Pedido;
import pe.pucp.paqrap.planner.model.PlanDistribucion;
import pe.pucp.paqrap.planner.model.Ruta;
import pe.pucp.paqrap.planner.model.VisitaCliente;

/**
 * Verificacion independiente del plan producido por el planificador. No forma
 * parte del algoritmo: su proposito es auditar cada corrida del IEN y descartar
 * que una mejora de costo provenga de una solucion invalida.
 */
public final class VerificadorPlan {

    private VerificadorPlan() {
    }

    public static List<String> verificar(PlanDistribucion plan, ContextoPlanificacion ctx,
                                         ValidadorFactibilidad validador, List<Pedido> pedidosEsperados) {
        List<String> hallazgos = new ArrayList<>();
        Set<String> atendidos = new HashSet<>();
        Map<String, Integer> consumo = new HashMap<>();

        for (Ruta ruta : plan.getListaRutas()) {
            consumo.merge(ruta.getAlmacenOrigen().getIdAlmacen(), ruta.getCarga(), Integer::sum);
            for (VisitaCliente v : ruta.getListaParadas()) {
                if (!atendidos.add(v.getIdPedido())) {
                    hallazgos.add("Pedido duplicado en el plan: " + v.getIdPedido());
                }
            }
            if (ruta.getCarga() > ruta.getVehiculo().getCapacidadMaxima()) {
                hallazgos.add("R1 violada en " + ruta.getIdRuta());
            }
        }

        for (Ruta ruta : plan.getListaRutas()) {
            if (ruta.getVehiculo().getEstado() == pe.pucp.paqrap.planner.model.EstadoVehiculo.AVERIADO) {
                // La unidad esta inmovilizada: su ruta solo puede conservar visitas
                // ya ejecutadas o inminentes (congeladas), no decisiones pendientes.
                if (ruta.tieneVisitasNoCongeladas()) {
                    hallazgos.add("Unidad averiada con visitas pendientes: " + ruta.getIdRuta());
                }
                continue;
            }
            int otras = consumo.getOrDefault(ruta.getAlmacenOrigen().getIdAlmacen(), 0) - ruta.getCarga();
            ResultadoFactibilidad rf = validador.evaluarRuta(ruta.copia(), ctx, otras);
            if (!rf.factible()) {
                hallazgos.add("Ruta infactible " + ruta.getIdRuta() + ": " + rf.motivo());
            }
        }

        for (Almacen a : ctx.getAlmacenes()) {
            if (!a.isEsInventarioInfinito()
                    && consumo.getOrDefault(a.getIdAlmacen(), 0) > a.getCapacidadActual()) {
                hallazgos.add("R2 violada en " + a.getIdAlmacen() + ": consumo "
                        + consumo.get(a.getIdAlmacen()) + " > saldo " + a.getCapacidadActual());
            }
        }

        Set<String> noAtendidos = new HashSet<>();
        for (Pedido p : plan.getPedidosNoAtendidos()) {
            noAtendidos.add(p.getIdPedido());
        }
        for (Pedido p : pedidosEsperados) {
            if (!atendidos.contains(p.getIdPedido()) && !noAtendidos.contains(p.getIdPedido())) {
                hallazgos.add("Pedido extraviado (ni atendido ni declarado no atendido): " + p.getIdPedido());
            }
        }
        return hallazgos;
    }
}
