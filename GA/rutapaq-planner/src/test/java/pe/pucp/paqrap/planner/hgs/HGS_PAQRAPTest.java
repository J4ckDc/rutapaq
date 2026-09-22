package pe.pucp.paqrap.planner.hgs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;

import org.junit.jupiter.api.Test;

import pe.pucp.paqrap.planner.FixturasPrueba;
import pe.pucp.paqrap.planner.core.Configuracion;
import pe.pucp.paqrap.planner.core.ContextoPlanificacion;
import pe.pucp.paqrap.planner.core.InstanciaEscenario;
import pe.pucp.paqrap.planner.core.RedVial;
import pe.pucp.paqrap.planner.core.ResultadoFactibilidad;
import pe.pucp.paqrap.planner.core.TipoEscenario;
import pe.pucp.paqrap.planner.core.ValidadorFactibilidad;
import pe.pucp.paqrap.planner.experimento.GeneradorInstancias;
import pe.pucp.paqrap.planner.experimento.VerificadorPlan;
import pe.pucp.paqrap.planner.model.Almacen;
import pe.pucp.paqrap.planner.model.Incidencia;
import pe.pucp.paqrap.planner.model.Pedido;
import pe.pucp.paqrap.planner.model.PlanDistribucion;
import pe.pucp.paqrap.planner.model.Ruta;
import pe.pucp.paqrap.planner.model.TipoVehiculo;
import pe.pucp.paqrap.planner.model.Vehiculo;
import pe.pucp.paqrap.planner.model.VisitaCliente;

/** Pruebas del Algoritmo Genetico Hibrido (HGS/GA). */
class HGS_PAQRAPTest {

    private Configuracion configuracionBreve() {
        return Configuracion.porDefecto()
                .con("escenario.limiteTiempoSegundos", 5)
                .con("hgs.tamanoPoblacion", 20)
                .con("hgs.lambdaOffspring", 10)
                .con("hgs.maxGeneraciones", 150)
                .con("instancia.TIEMPO_REAL.pedidos", 25);
    }

    @Test
    void elPlanProducidoEsFactibleYNoExtraviaPedidos() {
        Configuracion cfg = configuracionBreve();
        GeneradorInstancias.Instancia inst = new GeneradorInstancias(cfg, 1234L).generar(TipoEscenario.TIEMPO_REAL);
        ValidadorFactibilidad validador = new ValidadorFactibilidad(cfg);
        PlanDistribucion plan = new HGS_PAQRAP(cfg, validador, 1234L).resolver(
                new InstanciaEscenario("t1", inst.contexto(), inst.pedidos(), List.of(), null));

        assertTrue(VerificadorPlan.verificar(plan, inst.contexto(), validador, inst.pedidos()).isEmpty());
        assertEquals(inst.pedidos().size(),
                plan.getPedidosAtendidos().size() + plan.getPedidosNoAtendidos().size());
    }

    @Test
    void laCorridaParalelaEsReproducibleBajoLaMismaSemilla() {
        Configuracion cfg = configuracionBreve();
        assertEquals(costo(cfg, 77L), costo(cfg, 77L), 1.0E-9,
                "el lote paralelo es fijo y cada tarea usa un generador derivado por split()");
    }

    private double costo(Configuracion cfg, long semilla) {
        GeneradorInstancias.Instancia inst = new GeneradorInstancias(cfg, semilla).generar(TipoEscenario.TIEMPO_REAL);
        return new HGS_PAQRAP(cfg, new ValidadorFactibilidad(cfg), semilla)
                .resolver(new InstanciaEscenario("t", inst.contexto(), inst.pedidos(), List.of(), null))
                .getCostoTotalGlobal();
    }

    @Test
    void laReoptimizacionConservaLasVisitasCongeladasYEsFactible() {
        Configuracion cfg = configuracionBreve();
        GeneradorInstancias generador = new GeneradorInstancias(cfg, 99L);
        GeneradorInstancias.Instancia inst = generador.generar(TipoEscenario.TIEMPO_REAL);
        ContextoPlanificacion ctx = inst.contexto();
        ValidadorFactibilidad validador = new ValidadorFactibilidad(cfg);
        HGS_PAQRAP hgs = new HGS_PAQRAP(cfg, validador, 99L);

        PlanDistribucion plan = hgs.resolver(new InstanciaEscenario("t", ctx, inst.pedidos(), List.of(), null));
        ctx.setInstante(ctx.getInstante().plusMinutes(90));
        List<Incidencia> incidencias = generador.generarIncidencias(ctx, 2, 1, ctx.getInstante());
        PlanDistribucion replan = hgs.resolver(new InstanciaEscenario("t-r", ctx, List.of(), incidencias, plan));

        assertTrue(VerificadorPlan.verificar(replan, ctx, validador, inst.pedidos()).isEmpty());
        long congeladas = replan.getListaRutas().stream()
                .flatMap(r -> r.getListaParadas().stream()).filter(VisitaCliente::isCongelada).count();
        assertTrue(congeladas > 0, "las entregas ejecutadas o inminentes deben conservarse");
    }

    @Test
    void splitEligeLaUnidadDeMenorCostoCuandoElPlazoLoPermite() {
        Configuracion cfg = Configuracion.porDefecto();
        RedVial red = FixturasPrueba.redLineal();
        Almacen central = FixturasPrueba.almacenCentral(red);
        Vehiculo auto = FixturasPrueba.unidad("A001", TipoVehiculo.AUTO, central);
        Vehiculo bici = FixturasPrueba.unidad("B001", TipoVehiculo.BICI, central);
        ContextoPlanificacion ctx = FixturasPrueba.contexto(red, List.of(central), List.of(auto, bici), cfg);
        Pedido p = FixturasPrueba.pedido("P1", red.nodo("C"), 2, 12);

        DatosHGS d = DatosHGS.construir(ctx, null, List.of(p), List.of(), cfg, 20);
        Penalizaciones pen = new Penalizaciones(ParametrosHGS.desde(cfg));
        int kAuto = d.slots[0].vehiculo == auto ? 0 : 1;
        Individuo ind = new Individuo(new int[]{0}, new int[]{0}, new int[]{kAuto, 1 - kAuto});
        new SplitHeterogeneo(d, 15).decodificar(ind, pen);

        assertEquals(1, ind.rutas.size());
        assertEquals(TipoVehiculo.BICI, d.slots[ind.rutas.get(0).slot()].vehiculo.getTipoVehiculo(),
                "a igual cobertura, S/ 3.00 por km de la bicicleta domina a S/ 8.00 del auto");
    }

    @Test
    void elCruceOXProducePermutacionesValidas() {
        SplittableRandom r = new SplittableRandom(5);
        int[] p1 = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
        int[] p2 = {9, 3, 7, 1, 5, 0, 8, 2, 6, 4};
        for (int i = 0; i < 200; i++) {
            int[] h = OperadoresGeneticos.cruceOX(p1, p2, r).hijo().clone();
            Arrays.sort(h);
            assertTrue(Arrays.equals(h, p1));
        }
    }

    @Test
    void laEvaluacionPenalizadaCoincideConElValidadorCentral() {
        Configuracion cfg = configuracionBreve();
        GeneradorInstancias.Instancia inst = new GeneradorInstancias(cfg, 7L).generar(TipoEscenario.TIEMPO_REAL);
        ContextoPlanificacion ctx = inst.contexto();
        ValidadorFactibilidad validador = new ValidadorFactibilidad(cfg);
        DatosHGS d = DatosHGS.construir(ctx, null, inst.pedidos(), List.of(), cfg, 20);
        SplittableRandom r = new SplittableRandom(11);
        int coincidencias = 0;
        for (int caso = 0; caso < 300; caso++) {
            int k = r.nextInt(d.K);
            int a = r.nextInt(d.nAlm);
            int largo = 1 + r.nextInt(5);
            int[] paradas = new int[largo];
            for (int i = 0; i < largo; i++) {
                paradas[i] = r.nextInt(d.n);
            }
            RutaEval e = EvaluadorRuta.evaluar(d, d.slots[k], a, paradas);
            if (!e.valida()) {
                continue;                                    // excede la capacidad de la unidad
            }
            Ruta ruta = new Ruta("T", d.slots[k].vehiculo, d.almacenes[a], d.slots[k].inicioModelo);
            for (int p : paradas) {
                ruta.getListaParadas().add(new VisitaCliente(d.pedidos[p], d.paq[p]));
            }
            ResultadoFactibilidad rf = validador.evaluarRuta(ruta, ctx, 0);
            boolean sinPenalizacion = e.atrasoPonderadoH() == 0.0 && e.violacionTurno() == 0.0;
            assertEquals(rf.factible(), sinPenalizacion, "caso " + caso);
            if (rf.factible()) {
                assertEquals(rf.costoSoles(), e.costoKm(), 1.0E-9);
            }
            coincidencias++;
        }
        assertTrue(coincidencias > 100);
    }
}
