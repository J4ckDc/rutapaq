package pe.pucp.paqrap.planner.core;

import java.time.LocalDateTime;
import java.util.Collection;

import pe.pucp.paqrap.planner.model.Almacen;
import pe.pucp.paqrap.planner.model.EstadoPedido;
import pe.pucp.paqrap.planner.model.NivelSemaforo;
import pe.pucp.paqrap.planner.model.ParadaRuta;
import pe.pucp.paqrap.planner.model.Pedido;
import pe.pucp.paqrap.planner.model.PlanDistribucion;
import pe.pucp.paqrap.planner.model.Ruta;
import pe.pucp.paqrap.planner.model.TipoAlmacen;
import pe.pucp.paqrap.planner.model.TipoParada;

/**
 * Calculo unico de los indicadores del plan (costo, distancia, puntualidad,
 * consumo de almacenes y semaforo operativo), compartido por ambos algoritmos
 * para que las cifras registradas sean comparables.
 */
public final class IndicadoresPlan {

    private IndicadoresPlan() {
    }

    public static PlanDistribucion completar(PlanDistribucion plan, DatosPlanificacion d,
                                             Collection<Pedido> noPlanificados, LocalDateTime instante) {
        double costo = 0.0;
        double distancia = 0.0;
        int total = 0;
        int enPlazo = 0;
        for (Ruta ruta : plan.getRutas()) {
            costo += ruta.getCosto();
            distancia += ruta.getDistanciaKm();
            for (ParadaRuta parada : ruta.getItinerario()) {
                if (parada.tipo() == TipoParada.RECARGA) {
                    plan.getConsumoAlmacenes().merge(parada.referencia(), 0, Integer::sum);
                }
            }
            for (Pedido p : ruta.getPedidos()) {
                p.setEstado(EstadoPedido.PLANIFICADO);
                plan.getPlanificados().add(p);
                total++;
            }
            for (ParadaRuta parada : ruta.getItinerario()) {
                if (parada.tipo() == TipoParada.ENTREGA && parada.holguraHoras() >= 0.0) {
                    enPlazo++;
                }
            }
        }
        for (Pedido p : noPlanificados) {
            plan.getNoPlanificados().add(p);
            total++;
        }
        plan.setCostoTotal(costo);
        plan.setDistanciaTotalKm(distancia);
        double sla = total == 0 ? 1.0 : enPlazo / (double) total;
        plan.setIndicadorPuntualidadSLA(sla);

        double saldoCritico = 1.0;
        for (int a = 0; a < d.nAlm; a++) {
            Almacen alm = d.almacenes[a];
            if (alm.getTipo() == TipoAlmacen.INTERMEDIO) {
                int consumo = 0;
                for (Ruta ruta : plan.getRutas()) {
                    consumo += consumoDe(ruta, alm.getId());
                }
                plan.getConsumoAlmacenes().put(alm.getId(), consumo);
                if (alm.getCapacidadMaxima() > 0) {
                    saldoCritico = Math.min(saldoCritico,
                            (alm.getStock() - consumo) / (double) alm.getCapacidadMaxima());
                }
            }
        }
        double indicador = Math.min(sla, saldoCritico);
        Configuracion cfg = d.cfg;
        plan.setSemaforo(indicador >= cfg.umbralSemaforoVerde() ? NivelSemaforo.VERDE
                : indicador >= cfg.umbralSemaforoAmbar() ? NivelSemaforo.AMBAR : NivelSemaforo.ROJO);
        plan.setTimestamp(instante);
        return plan;
    }

    /** Productos retirados de un almacen por una ruta (entregas posteriores a su recarga). */
    private static int consumoDe(Ruta ruta, String idAlmacen) {
        int consumo = 0;
        String actual = null;
        for (ParadaRuta parada : ruta.getItinerario()) {
            if (parada.tipo() == TipoParada.RECARGA) {
                actual = parada.referencia();
            } else if (parada.tipo() == TipoParada.ENTREGA && idAlmacen.equals(actual)) {
                consumo += parada.cantidad();
            }
        }
        return consumo;
    }
}
