package pe.pucp.paqrap.app.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import pe.pucp.paqrap.app.Config;
import pe.pucp.paqrap.app.modelo.Nodo;
import pe.pucp.paqrap.app.modelo.Parada;
import pe.pucp.paqrap.app.modelo.Pedido;
import pe.pucp.paqrap.app.modelo.Unidad;

/**
 * Correcciones de la v03: pedidos vencidos que se siguen entregando, pedidos que se parten cuando no hay una unidad
 * que los lleve enteros y reloj simulado según el tiempo real transcurrido. Cada prueba usa una carpeta de datos
 * propia y vacía, de modo que solo hay los pedidos y averías que la prueba registra.
 */
class CorreccionesTest {

    private static final LocalDateTime INICIO = LocalDateTime.of(2026, 1, 12, 8, 0);

    private static Simulacion nueva(Simulacion.Escenario escenario, String extra) throws IOException {
        Path carpeta = Files.createTempDirectory("rutapaq-correcciones");
        Files.writeString(carpeta.resolve("config.properties"), "datos.carpeta="
                + carpeta.toString().replace('\\', '/') + "\n" + "planificador.presupuestoSegundos=0.3\n" + extra);
        Config cfg = new Config(carpeta.resolve("config.properties"));
        return new Simulacion(cfg, escenario, INICIO, Planificadores.crear(cfg.planificador()));
    }

    private static void averiar(Simulacion s, String prefijo, int cantidad, int tipo) {
        for (int i = 1; i <= cantidad; i++) s.registrarAveria(String.format("%s%02d", prefijo, i), tipo);
    }

    /** Avanza de 10 en 10 minutos hasta que el pedido se completa o pasan las horas indicadas. */
    private static boolean entregadoAntesDe(Simulacion s, String id, double horas) throws IOException {
        for (double h = 0; h < horas; h += 1.0 / 6) {
            s.avanzar(600);
            Pedido p = s.pedido(id);
            if (p == null || p.completo()) return true;
        }
        return false;
    }

    @Test
    void unPedidoVencidoSeSigueEntregandoYEnColapsoTerminaLaSimulacion() throws IOException {
        // Autos y motos fuera hasta el final del turno siguiente; las bicis, 2 h. Un pedido de 4 h a 79 km del
        // central solo lo puede llevar una bici (6,6 h de viaje): vence sin remedio, pero debe llegar igual.
        Simulacion s = nueva(Simulacion.Escenario.SEMANAL, "");
        s.avanzar(60);
        averiar(s, "TA", 10, 2);
        averiar(s, "TM", 15, 2);
        averiar(s, "TB", 12, 1);
        String id = s.registrarPedido("c-lejano", new Nodo(70, 50), 2, 4);
        Pedido p = s.pedido(id);
        assertTrue(entregadoAntesDe(s, id, 24), "el pedido vencido debe entregarse aunque sea tarde");
        assertTrue(p.vencido, "sigue contando como vencido en los indicadores");
        assertEquals(2, p.entregado);

        // En el escenario de colapso, el mismo pedido termina la simulación en cuanto vence (criterio P).
        Simulacion c = nueva(Simulacion.Escenario.COLAPSO, "");
        c.avanzar(60);
        averiar(c, "TA", 10, 2);
        averiar(c, "TM", 15, 2);
        averiar(c, "TB", 12, 1);
        String idc = c.registrarPedido("c-lejano", new Nodo(70, 50), 2, 4);
        c.avanzar(6 * 3600);
        assertEquals(Simulacion.Estado.TERMINADA, c.estado());
        assertTrue(c.resumen().contains("Colapso P"), c.resumen());
        assertFalse(c.pedido(idc).completo());
    }

    @Test
    void sinAutosUnPedidoDeDiezSeReparteEntreMotosYBicis() throws IOException {
        // Con los 10 autos averiados (tipo 3), un pedido de 10 no cabe en una moto (8) ni en una bici (4).
        // Antes nunca se entregaba; ahora se parte en 8 + 2 y llega a tiempo.
        Simulacion s = nueva(Simulacion.Escenario.SEMANAL, "");
        s.avanzar(60);
        averiar(s, "TA", 10, 3);
        String id = s.registrarPedido("c-grande", new Nodo(30, 20), 10, 36);
        Pedido p = s.pedido(id);
        assertTrue(entregadoAntesDe(s, id, 36), "el pedido de 10 debe entregarse entre varias unidades");
        assertFalse(p.vencido, "y llegar dentro de su plazo");
        assertEquals(1, p.particion, "se partió según la capacidad de la moto, y no más");
    }

    @Test
    void unPedidoQueCabeEnUnAutoNoSeParte() throws IOException {
        Simulacion s = nueva(Simulacion.Escenario.SEMANAL, "");
        s.avanzar(60);
        String id = s.registrarPedido("c-grande", new Nodo(30, 20), 10, 36);
        s.avanzar(60);                                      // primer ciclo de planificación
        boolean entero = false;
        for (String u : new String[]{"TA01", "TA02", "TA03", "TA04", "TA05", "TA06", "TA07", "TA08", "TA09", "TA10"}) {
            Unidad x = s.unidad(u);
            for (Parada p : x.paradas) {
                if (p.tipo() == Parada.Tipo.ENTREGA && id.equals(p.pedidoId()) && p.cantidad() == 10) entero = true;
            }
        }
        assertTrue(entero, "con autos libres el pedido se asigna entero a un auto");
        assertEquals(0, s.pedido(id).particion);
    }

    @Test
    void elRelojSimuladoIncluyeElTiempoQueLaSimulacionEstuvoOcupada() throws Exception {
        // 5 días en 1 minuto real: 7 200 s simulados por segundo real. Durante 1 s la simulación queda ocupada
        // (como cuando el planificador tarda); antes ese segundo no se simulaba y el reloj se atrasaba.
        Simulacion s = nueva(Simulacion.Escenario.SEMANAL, "escenario.semanal.minutosReales=1\n");
        Thread hilo = new Thread(s, "simulacion-prueba");
        hilo.setDaemon(true);
        long t0 = System.nanoTime();
        hilo.start();
        Thread.sleep(500);
        synchronized (s) {
            Thread.sleep(1000);
        }
        Thread.sleep(1500);
        double realSeg = (System.nanoTime() - t0) / 1e9;
        double simuladoSeg = Duration.between(INICIO, s.ahora()).toMillis() / 1000.0;
        s.cancelar();
        hilo.join(2000);
        double esperado = realSeg * 7200;
        assertTrue(simuladoSeg > 0.85 * esperado && simuladoSeg < 1.05 * esperado,
                String.format("simulado %.0f s, esperado unos %.0f s", simuladoSeg, esperado));
    }
}
