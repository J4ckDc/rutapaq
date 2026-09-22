package pe.pucp.paqrap.planner.core;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import pe.pucp.paqrap.planner.model.Almacen;
import pe.pucp.paqrap.planner.model.EstadoPedido;
import pe.pucp.paqrap.planner.model.NivelSemaforo;
import pe.pucp.paqrap.planner.model.Pedido;
import pe.pucp.paqrap.planner.model.PlanDistribucion;
import pe.pucp.paqrap.planner.model.Ruta;
import pe.pucp.paqrap.planner.model.TipoAlmacen;
import pe.pucp.paqrap.planner.model.VisitaCliente;

/**
 * Calculo unico de los indicadores del plan (costo, distancia, puntualidad SLA,
 * saldo de almacenes y semaforo operativo). Lo invocan el ALNS y el HGS/GA, de
 * modo que las variables de respuesta del IEN se miden con la misma regla para
 * ambas soluciones algoritmicas.
 */
public final class IndicadoresPlan {

    private IndicadoresPlan() {
    }

    /** Completa un plan cuyas rutas ya fueron validadas por ValidadorFactibilidad. */
    public static PlanDistribucion completar(PlanDistribucion plan, ContextoPlanificacion ctx,
                                             Collection<Pedido> noAtendidos) {
        Configuracion cfg = ctx.getConfiguracion();
        Map<String, Integer> consumo = new HashMap<>();
        double costo = 0.0;
        double distancia = 0.0;
        int total = 0;
        int enPlazo = 0;
        for (Ruta ruta : plan.getListaRutas()) {
            costo += ruta.getCostoTotalRuta();
            distancia += ruta.getDistanciaTotalKm();
            consumo.merge(ruta.getAlmacenOrigen().getIdAlmacen(), ruta.getCarga(), Integer::sum);
            for (VisitaCliente v : ruta.getListaParadas()) {
                Pedido p = v.getPedido();
                p.setEstado(EstadoPedido.ASIGNADO);
                p.setIdVehiculoAsignado(ruta.getVehiculo().getIdVehiculo());
                plan.getPedidosAtendidos().add(p);
                total++;
                if (v.getHolguraHoras() >= 0.0) {
                    enPlazo++;
                }
            }
        }
        for (Pedido p : noAtendidos) {
            p.setEstado(EstadoPedido.NO_ATENDIDO);
            plan.getPedidosNoAtendidos().add(p);
            total++;
        }
        plan.setCostoTotalGlobal(costo);
        plan.setDistanciaTotalGlobalKm(distancia);
        double sla = total == 0 ? 1.0 : enPlazo / (double) total;
        plan.setIndicadorPuntualidadSLA(sla);

        double saldoCritico = 1.0;
        for (Almacen a : ctx.getAlmacenes()) {
            if (a.getTipoAlmacen() == TipoAlmacen.INTERMEDIO) {
                int saldo = a.getCapacidadActual() - consumo.getOrDefault(a.getIdAlmacen(), 0);
                plan.getEstadoAlmacenes().put(a.getIdAlmacen(), saldo);
                if (a.getCapacidadMaxima() > 0) {
                    saldoCritico = Math.min(saldoCritico, saldo / (double) a.getCapacidadMaxima());
                }
            }
        }
        double indicador = Math.min(sla, saldoCritico);
        NivelSemaforo semaforo = indicador >= cfg.umbralSemaforoVerde()
                ? NivelSemaforo.VERDE
                : indicador >= cfg.umbralSemaforoAmbar() ? NivelSemaforo.AMBAR : NivelSemaforo.ROJO;
        plan.setSemaforoOperativo(semaforo);
        plan.setTimestampGeneracion(ctx.getInstante());
        return plan;
    }
}
