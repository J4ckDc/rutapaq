package pe.pucp.paqrap.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import pe.pucp.paqrap.planner.alns.ALNS_PAQRAP;
import pe.pucp.paqrap.planner.core.Configuracion;
import pe.pucp.paqrap.planner.core.InstanciaPlanificacion;
import pe.pucp.paqrap.planner.core.MapaReticula;
import pe.pucp.paqrap.planner.core.Planificador;
import pe.pucp.paqrap.planner.hgs.HGS_PAQRAP;
import pe.pucp.paqrap.planner.ien.AuditorPlan;
import pe.pucp.paqrap.planner.ien.Bloque;
import pe.pucp.paqrap.planner.ien.CargadorBloque;
import pe.pucp.paqrap.planner.ien.DefinicionBloque;
import pe.pucp.paqrap.planner.ien.GeneradorDatosSinteticos;
import pe.pucp.paqrap.planner.ien.LectorVentas;
import pe.pucp.paqrap.planner.ien.ResultadoCorrida;
import pe.pucp.paqrap.planner.ien.SimuladorColapso;
import pe.pucp.paqrap.planner.model.Almacen;
import pe.pucp.paqrap.planner.model.Pedido;
import pe.pucp.paqrap.planner.model.PlanDistribucion;
import pe.pucp.paqrap.planner.model.TipoAlmacen;
import pe.pucp.paqrap.planner.model.TipoVehiculo;
import pe.pucp.paqrap.planner.model.TurnoConductor;
import pe.pucp.paqrap.planner.model.Vehiculo;

/** Requisitos del banco de pruebas: lector de bloques, ciclo programado y detector de colapso. */
class BancoDePruebasIENTest {

    private static final MapaReticula MAPA = new MapaReticula(70, 50);

    private Path datosDePrueba() throws Exception {
        Path dir = Files.createTempDirectory("rutapaq-datos");
        new GeneradorDatosSinteticos(7L).generar(dir, List.of(DefinicionBloque.bloquesOficiales().get(0)), 48);
        return dir;
    }

    @Test
    void elLectorDeBloquesCargaSoloLaVentanaDeCuarentaYOchoHoras() throws Exception {
        DefinicionBloque def = DefinicionBloque.bloquesOficiales().get(0);
        Bloque b = CargadorBloque.cargar(datosDePrueba(), def, 48, MAPA, 24);
        LocalDateTime t0 = def.inicio().atStartOfDay();
        assertTrue(b.pedidos().size() > 0);
        for (Pedido p : b.pedidos()) {
            assertTrue(!p.getRegistro().isBefore(t0) && p.getRegistro().isBefore(t0.plusHours(48)),
                    "pedido fuera de la ventana: " + p.getRegistro());
        }
        assertEquals(b.productos(), b.pedidos().stream().mapToInt(Pedido::getCantidad).sum());
    }

    @Test
    void unPedidoMayorQueLaCapacidadSeEntregaEnPartes() {
        Pedido grande = new Pedido("P1", "cli", MAPA.indice(30, 20), 30, 20, 50,
                LocalDate.of(2026, 9, 21).atTime(8, 0), 12, null);
        List<Pedido> partes = LectorVentas.fraccionar(grande, 24);
        assertEquals(3, partes.size(), "50 productos en partes de a lo mas 24");
        assertEquals(50, partes.stream().mapToInt(Pedido::getCantidad).sum());
        for (Pedido p : partes) {
            assertEquals("P1", p.getIdPedidoPadre());
            assertEquals(grande.getLimite(), p.getLimite(), "cada parte hereda la fecha limite");
        }
    }

    @Test
    void elCicloProgramadoNoExcedeElNumeroDeEjecucionesYDetectaElColapso() throws Exception {
        Configuracion cfg = Configuracion.porDefecto()
                .con("planificador.presupuestoSegundos", 0.3)
                .con("ien.horizonteHoras", 12);
        Bloque bloque = CargadorBloque.cargar(datosDePrueba(),
                DefinicionBloque.bloquesOficiales().get(0), 12, MAPA, 24);
        ResultadoCorrida r = new SimuladorColapso(cfg, MAPA).correr(bloque, new ALNS_PAQRAP(cfg, 1L));

        assertTrue(r.ejecuciones() <= 12, "H / Sc ejecuciones como maximo: " + r.ejecuciones());
        assertTrue(r.tcHoras() > 0 && r.tcHoras() <= 12);
        assertTrue(List.of("P", "C", "N").contains(r.causa()));
        assertTrue(r.taMaxMs() >= 0);
    }

    @Test
    void unPedidoImposibleDeEntregarATiempoProduceColapsoPorIncumplimiento() throws Exception {
        // Flota minima y un pedido lejano con plazo de 4 h: no hay forma de cumplirlo.
        Configuracion cfg = Configuracion.porDefecto()
                .con("planificador.presupuestoSegundos", 0.2)
                .con("ien.horizonteHoras", 12)
                .con("ien.autos", 0).con("ien.motos", 0).con("ien.bicicletas", 1);
        Path dir = Files.createTempDirectory("rutapaq-imposible");
        LocalDate dia = LocalDate.of(2026, 1, 24);
        Files.write(dir.resolve("ventas.202601.txt"), List.of("24D00H030:70,50,c-1,4,4"));
        Files.write(dir.resolve("bloqueo.2601.txt"), List.of());
        Bloque bloque = CargadorBloque.cargar(dir, new DefinicionBloque(1, dia), 12, MAPA, 24);

        ResultadoCorrida r = new SimuladorColapso(cfg, MAPA).correr(bloque, new ALNS_PAQRAP(cfg, 1L));
        assertEquals("P", r.causa(), "la bicicleta no alcanza (70,50) desde (27,14) en 4 h");
        assertEquals(4.5, r.tcHoras(), 0.01, "el colapso se registra al vencer el plazo (00:30 + 4 h)");
    }

    @Test
    void elPlanDeCadaAlgoritmoSuperaLaAuditoria() throws Exception {
        Configuracion cfg = Configuracion.porDefecto().con("planificador.presupuestoSegundos", 0.5);
        Path dir = datosDePrueba();
        Bloque bloque = CargadorBloque.cargar(dir, DefinicionBloque.bloquesOficiales().get(0), 48, MAPA, 24);
        List<Pedido> cartera = bloque.pedidos().subList(0, Math.min(40, bloque.pedidos().size()));
        LocalDateTime instante = bloque.t0().plusHours(24);
        List<Almacen> almacenes = List.of(
                new Almacen("ALM-CENTRAL", TipoAlmacen.CENTRAL, MAPA.indice(27, 14), 27, 14, 0),
                new Almacen("ALM-NOROESTE", TipoAlmacen.INTERMEDIO, MAPA.indice(12, 38), 12, 38, 1000),
                new Almacen("ALM-ESTE", TipoAlmacen.INTERMEDIO, MAPA.indice(57, 27), 57, 27, 1000));
        List<Vehiculo> flota = List.of(
                unidad("A001", TipoVehiculo.AUTO, instante),
                unidad("M001", TipoVehiculo.MOTO, instante),
                unidad("B001", TipoVehiculo.BICI, instante));

        for (Planificador p : List.of(new ALNS_PAQRAP(cfg, 3L), new HGS_PAQRAP(cfg, 3L))) {
            PlanDistribucion plan = p.planificar(
                    new InstanciaPlanificacion(MAPA, almacenes, flota, cartera, instante, cfg));
            assertTrue(AuditorPlan.verificar(plan, cartera).isEmpty(),
                    p.nombre() + ": " + AuditorPlan.verificar(plan, cartera));
            assertEquals(cartera.size(), plan.getPlanificados().size() + plan.getNoPlanificados().size(),
                    p.nombre() + " no debe extraviar pedidos");
        }
    }

    private Vehiculo unidad(String id, TipoVehiculo tipo, LocalDateTime instante) {
        Vehiculo v = new Vehiculo(id, tipo, MAPA.indice(27, 14), instante);
        v.setTurno(TurnoConductor.vigenteEn(instante));
        return v;
    }
}
