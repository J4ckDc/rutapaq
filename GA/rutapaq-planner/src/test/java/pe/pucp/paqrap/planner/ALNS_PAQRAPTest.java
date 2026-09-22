package pe.pucp.paqrap.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import pe.pucp.paqrap.planner.alns.ALNS_PAQRAP;
import pe.pucp.paqrap.planner.core.Configuracion;
import pe.pucp.paqrap.planner.core.ContextoPlanificacion;
import pe.pucp.paqrap.planner.core.InstanciaEscenario;
import pe.pucp.paqrap.planner.core.TipoEscenario;
import pe.pucp.paqrap.planner.core.ValidadorFactibilidad;
import pe.pucp.paqrap.planner.experimento.GeneradorInstancias;
import pe.pucp.paqrap.planner.experimento.VerificadorPlan;
import pe.pucp.paqrap.planner.model.Incidencia;
import pe.pucp.paqrap.planner.model.NodoRed;
import pe.pucp.paqrap.planner.model.Pedido;
import pe.pucp.paqrap.planner.model.PlanDistribucion;
import pe.pucp.paqrap.planner.model.Ruta;
import pe.pucp.paqrap.planner.model.TipoVehiculo;

/** Pruebas de integracion del planificador reactivo ALNS. */
class ALNS_PAQRAPTest {

    private Configuracion configuracionBreve() {
        return Configuracion.porDefecto()
                .con("escenario.maxIteraciones", 400)
                .con("escenario.limiteTiempoSegundos", 5)
                .con("instancia.TIEMPO_REAL.pedidos", 25);
    }

    @Test
    void elPlanProducidoEsFactibleYNoExtraviaPedidos() {
        Configuracion cfg = configuracionBreve();
        GeneradorInstancias generador = new GeneradorInstancias(cfg, 1234L);
        GeneradorInstancias.Instancia inst = generador.generar(TipoEscenario.TIEMPO_REAL);
        ValidadorFactibilidad validador = new ValidadorFactibilidad(cfg);
        ALNS_PAQRAP alns = new ALNS_PAQRAP(cfg, validador, 1234L);

        PlanDistribucion plan = alns.resolver(new InstanciaEscenario(
                "t1", inst.contexto(), inst.pedidos(), List.of(), null));

        assertTrue(VerificadorPlan.verificar(plan, inst.contexto(), validador, inst.pedidos()).isEmpty(),
                "el plan no debe violar ninguna restriccion dura");
        assertEquals(inst.pedidos().size(),
                plan.getPedidosAtendidos().size() + plan.getPedidosNoAtendidos().size(),
                "todo pedido debe quedar atendido o declarado no atendido");
        assertTrue(plan.getCostoTotalGlobal() > 0.0);
    }

    @Test
    void laCorridaEsReproducibleBajoLaMismaSemilla() {
        Configuracion cfg = configuracionBreve();
        double primera = costoDeUnaCorrida(cfg, 77L);
        double segunda = costoDeUnaCorrida(cfg, 77L);
        assertEquals(primera, segunda, 1.0E-9);
    }

    private double costoDeUnaCorrida(Configuracion cfg, long semilla) {
        GeneradorInstancias generador = new GeneradorInstancias(cfg, semilla);
        GeneradorInstancias.Instancia inst = generador.generar(TipoEscenario.TIEMPO_REAL);
        ALNS_PAQRAP alns = new ALNS_PAQRAP(cfg, new ValidadorFactibilidad(cfg), semilla);
        return alns.resolver(new InstanciaEscenario("t", inst.contexto(), inst.pedidos(), List.of(), null))
                .getCostoTotalGlobal();
    }

    @Test
    void laReoptimizacionAnteIncidenciasDevuelveUnPlanFactible() {
        Configuracion cfg = configuracionBreve();
        GeneradorInstancias generador = new GeneradorInstancias(cfg, 99L);
        GeneradorInstancias.Instancia inst = generador.generar(TipoEscenario.TIEMPO_REAL);
        ContextoPlanificacion ctx = inst.contexto();
        ValidadorFactibilidad validador = new ValidadorFactibilidad(cfg);
        ALNS_PAQRAP alns = new ALNS_PAQRAP(cfg, validador, 99L);

        PlanDistribucion plan = alns.resolver(new InstanciaEscenario(
                "t2", ctx, inst.pedidos(), List.of(), null));
        ctx.setInstante(ctx.getInstante().plusMinutes(90));
        List<Incidencia> incidencias = generador.generarIncidencias(
                ctx, 2, 1, ctx.getInstante());

        PlanDistribucion replanificado = alns.resolver(new InstanciaEscenario(
                "t2-reopt", ctx, List.of(), incidencias, plan));

        assertTrue(VerificadorPlan.verificar(replanificado, ctx, validador, inst.pedidos()).isEmpty(),
                "la reoptimizacion rodante debe preservar la factibilidad del plan");
        for (Ruta r : replanificado.getListaRutas()) {
            assertTrue(r.getCarga() <= r.getVehiculo().getCapacidadMaxima());
        }
    }

    @Test
    void unPedidoMayorAVeinticuatroPaquetesSeFraccionaEnSubpedidos() {
        NodoRed destino = FixturasPrueba.redLineal().nodo("B");
        List<Pedido> partes = GeneradorInstancias.fraccionar(
                "P9999", "CLI-1", destino, 50, FixturasPrueba.INICIO_TURNO, 12);

        assertEquals(3, partes.size());
        int total = partes.stream().mapToInt(Pedido::getCantidadPaquetes).sum();
        assertEquals(50, total);
        for (Pedido p : partes) {
            assertTrue(p.getCantidadPaquetes() <= TipoVehiculo.AUTO.getCapacidadMaxima());
            assertEquals("P9999", p.getIdPedidoPadre());
            assertEquals(partes.get(0).getFechaHoraLimite(), p.getFechaHoraLimite(),
                    "cada subpedido hereda la fecha limite del pedido original");
        }
    }
}
