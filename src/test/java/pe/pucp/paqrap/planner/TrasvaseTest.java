package pe.pucp.paqrap.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import pe.pucp.paqrap.planner.alns.ALNS_PAQRAP;
import pe.pucp.paqrap.planner.hgs.HGS_PAQRAP;
import pe.pucp.paqrap.planner.core.Configuracion;
import pe.pucp.paqrap.planner.core.DatosPlanificacion;
import pe.pucp.paqrap.planner.core.EvaluadorItinerario;
import pe.pucp.paqrap.planner.core.InstanciaPlanificacion;
import pe.pucp.paqrap.planner.core.MapaReticula;
import pe.pucp.paqrap.planner.core.ResultadoRuta;
import pe.pucp.paqrap.planner.model.Almacen;
import pe.pucp.paqrap.planner.model.ParadaRuta;
import pe.pucp.paqrap.planner.model.Pedido;
import pe.pucp.paqrap.planner.model.PlanDistribucion;
import pe.pucp.paqrap.planner.model.Ruta;
import pe.pucp.paqrap.planner.model.TipoAlmacen;
import pe.pucp.paqrap.planner.model.TipoParada;
import pe.pucp.paqrap.planner.model.TipoVehiculo;
import pe.pucp.paqrap.planner.model.TurnoConductor;
import pe.pucp.paqrap.planner.model.Vehiculo;

/**
 * v03: deposito temporal de una unidad averiada (trasvase) en el evaluador comun. Una unidad con averia
 * tipo 2 o 3 que sigue en el lugar ofrece sus paquetes hasta que la trasladan al almacen central, y cada
 * recogida toma 30 min.
 */
class TrasvaseTest {

    private static final long MEDIA_HORA = 1800L;

    private final MapaReticula mapa = new MapaReticula(70, 50);
    private final Configuracion cfg = Configuracion.porDefecto();
    private final LocalDateTime t0 = LocalDate.of(2026, 9, 21).atTime(8, 0);

    private Almacen central() {
        return new Almacen("ALM-CENTRAL", TipoAlmacen.CENTRAL, mapa.indice(27, 14), 27, 14, 0);
    }

    private Almacen trasvase(int x, int y, int saldo, LocalDateTime cierre) {
        Almacen a = new Almacen("TRV-TA01", TipoAlmacen.TRASVASE, mapa.indice(x, y), x, y, saldo);
        a.setDisponibleHasta(cierre);
        a.setSegundosAtencion(MEDIA_HORA);
        return a;
    }

    private Pedido pedido(String id, int x, int y, int cantidad, int plazo) {
        return new Pedido(id, "cli", mapa.indice(x, y), x, y, cantidad, t0, plazo, null);
    }

    /** Unidad que ya tomo su refrigerio en el turno de 07:00, para que el itinerario empiece por la recogida. */
    private Vehiculo unidad(String id, TipoVehiculo tipo, int x, int y, int carga) {
        Vehiculo v = new Vehiculo(id, tipo, mapa.indice(x, y), t0);
        v.setCarga(carga);
        TurnoConductor turno = TurnoConductor.vigenteEn(t0);
        turno.setRefrigerioConsumido(true);
        v.setTurno(turno);
        return v;
    }

    private DatosPlanificacion datos(List<Almacen> almacenes, Vehiculo v, List<Pedido> pedidos) {
        return DatosPlanificacion.construir(new InstanciaPlanificacion(mapa, almacenes, List.of(v), pedidos, t0, cfg));
    }

    @Test
    void laUnidadCercanaRecogeLosPaquetesDeLaAveriadaYTardaMediaHora() {
        Vehiculo moto = unidad("TM01", TipoVehiculo.MOTO, 40, 40, 0);
        DatosPlanificacion d = datos(List.of(central(), trasvase(41, 40, 6, t0.plusHours(4))), moto,
                List.of(pedido("P0", 42, 40, 5, 12)));
        List<ParadaRuta> itinerario = new ArrayList<>();
        ResultadoRuta r = EvaluadorItinerario.evaluar(d, 0, new int[]{0}, d.saldo, itinerario);

        assertTrue(r.factible());
        ParadaRuta recogida = itinerario.get(0);
        assertEquals(TipoParada.RECARGA, recogida.tipo());
        assertEquals("TRV-TA01", recogida.referencia(), "la averiada esta a 1 km y el central a 39 km");
        assertEquals(6, recogida.cantidad(), "recoge todo lo que tiene la averiada (le caben 8)");
        assertEquals(MEDIA_HORA, java.time.Duration.between(recogida.llegada(), recogida.salida()).getSeconds());
        assertEquals(6, r.consumo()[1], "el saldo del deposito temporal se descuenta al recoger");
        assertEquals(2.0, r.km(), 1.0E-9);
    }

    @Test
    void laRecogidaDebeTerminarAntesDeQueSeLlevenALaAveriada() {
        Vehiculo moto = unidad("TM01", TipoVehiculo.MOTO, 40, 40, 0);
        // la averiada se va al central en 20 min: no alcanza a terminar los 30 min de trasvase
        DatosPlanificacion d = datos(List.of(central(), trasvase(41, 40, 6, t0.plusMinutes(20))), moto,
                List.of(pedido("P0", 42, 40, 5, 12)));
        List<ParadaRuta> itinerario = new ArrayList<>();
        EvaluadorItinerario.evaluar(d, 0, new int[]{0}, d.saldo, itinerario);
        assertEquals("ALM-CENTRAL", itinerario.get(0).referencia());
    }

    @Test
    void elRefrigerioPendienteCuentaParaElCierreDelTrasvase() {
        Vehiculo moto = new Vehiculo("TM01", TipoVehiculo.MOTO, mapa.indice(40, 40), t0);
        moto.setTurno(TurnoConductor.vigenteEn(t0));          // refrigerio pendiente: se toma al llegar
        // sin refrigerio terminaria a las 08:32; con la hora de refrigerio, a las 09:32 (cierre 08:45)
        DatosPlanificacion d = datos(List.of(central(), trasvase(41, 40, 6, t0.plusMinutes(45))), moto,
                List.of(pedido("P0", 42, 40, 5, 12)));
        List<ParadaRuta> itinerario = new ArrayList<>();
        EvaluadorItinerario.evaluar(d, 0, new int[]{0}, d.saldo, itinerario);
        assertEquals("ALM-CENTRAL", itinerario.stream().filter(p -> p.tipo() == TipoParada.RECARGA)
                .findFirst().orElseThrow().referencia());
    }

    @Test
    void elTrasvaseCompletaLaCargaQueLaUnidadYaLleva() {
        // lleva 3 y necesita 8: la averiada tiene 5, que con lo que lleva alcanzan
        Vehiculo auto = unidad("TA02", TipoVehiculo.AUTO, 40, 40, 3);
        DatosPlanificacion d = datos(List.of(central(), trasvase(41, 40, 5, t0.plusHours(4))), auto,
                List.of(pedido("P0", 42, 40, 8, 12)));
        List<ParadaRuta> itinerario = new ArrayList<>();
        ResultadoRuta r = EvaluadorItinerario.evaluar(d, 0, new int[]{0}, d.saldo, itinerario);
        assertTrue(r.factible());
        assertEquals("TRV-TA01", itinerario.get(0).referencia());
        assertEquals(5, itinerario.get(0).cantidad());
        assertEquals(8, itinerario.get(1).cantidad());
    }

    @Test
    void sinSaldoEnLaAveriadaLaRecargaSeHaceEnUnAlmacen() {
        Vehiculo moto = unidad("TM01", TipoVehiculo.MOTO, 40, 40, 0);
        DatosPlanificacion d = datos(List.of(central(), trasvase(41, 40, 6, t0.plusHours(4))), moto,
                List.of(pedido("P0", 42, 40, 5, 12)));
        int[] disponible = d.saldo.clone();
        disponible[1] = 0;                      // otra unidad ya reservo todos sus paquetes
        List<ParadaRuta> itinerario = new ArrayList<>();
        EvaluadorItinerario.evaluar(d, 0, new int[]{0}, disponible, itinerario);
        assertEquals("ALM-CENTRAL", itinerario.get(0).referencia());
    }

    @Test
    void elPlanificadorUsaElTrasvaseCuandoEsLaRecargaMasCercana() {
        for (String algoritmo : new String[]{"ALNS", "GA"}) {
            planConTrasvase(algoritmo);
        }
    }

    private void planConTrasvase(String algoritmo) {
        Vehiculo cerca = unidad("TM01", TipoVehiculo.MOTO, 40, 40, 0);
        Vehiculo lejos = unidad("TM02", TipoVehiculo.MOTO, 27, 14, 0);
        InstanciaPlanificacion inst = new InstanciaPlanificacion(mapa,
                List.of(central(), trasvase(41, 40, 6, t0.plusHours(4))), List.of(cerca, lejos),
                List.of(pedido("P0", 42, 40, 5, 12)), t0, cfg.copia().con("planificador.presupuestoSegundos", "0.3"));
        PlanDistribucion plan = algoritmo.equals("ALNS")
                ? new ALNS_PAQRAP(inst.getConfiguracion(), 7L).planificar(inst)
                : new HGS_PAQRAP(inst.getConfiguracion(), 7L).planificar(inst);

        assertTrue(plan.getNoPlanificados().isEmpty(), algoritmo);
        assertEquals(1, plan.getRutas().size());
        Ruta ruta = plan.getRutas().get(0);
        assertEquals("TM01", ruta.getVehiculo().getId(), algoritmo + ": la moto cercana es la de menor costo");
        assertEquals("TRV-TA01", ruta.getItinerario().get(0).referencia());
        assertFalse(ruta.getItinerario().stream().anyMatch(p -> "ALM-CENTRAL".equals(p.referencia())));
    }
}
