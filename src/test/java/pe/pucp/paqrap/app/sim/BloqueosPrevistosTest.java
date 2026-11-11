package pe.pucp.paqrap.app.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import pe.pucp.paqrap.app.Config;
import pe.pucp.paqrap.app.modelo.Nodo;
import pe.pucp.paqrap.app.modelo.Parada;
import pe.pucp.paqrap.app.modelo.Unidad;

/**
 * S13v1 (G07): el planificador y las unidades consideran los bloqueos planificados que empiezan dentro de
 * planificador.anticipacionBloqueosHoras (1 h por defecto), no solo los vigentes.
 */
class BloqueosPrevistosTest {

    /** 10:02; el bloqueo de prueba empieza a las 10:30, dentro de la hora de anticipación. */
    private static final LocalDateTime INICIO = LocalDateTime.of(2026, 11, 6, 10, 2);

    private static Simulacion operacion(String bloqueo, String extra) throws IOException {
        Path c = Files.createTempDirectory("rutapaq-g07");
        Files.writeString(c.resolve("config.properties"), "datos.carpeta=" + c.toString().replace('\\', '/') + "\n"
                + "planificador.presupuestoSegundos=0.2\n" + extra);
        Files.write(c.resolve("bloqueo.2611.txt"), List.of(bloqueo));
        Config cfg = new Config(c.resolve("config.properties"));
        return new Simulacion(cfg, Simulacion.Escenario.DIA_A_DIA, INICIO, Planificadores.crear(cfg.planificador()));
    }

    /**
     * Avanza minuto a minuto hasta que la unidad que lleva el pedido sale hacia él (puede tomar antes su refrigerio) y
     * devuelve su camino, posición actual incluida; null si no sale en 4 h.
     */
    private static List<Nodo> caminoAlSalir(Simulacion s, String pedido) throws IOException {
        for (int i = 0; i < 240; i++) {
            List<Nodo> c = caminoHacia(s, pedido);
            if (c != null && c.size() > 1) return c;
            s.avanzar(60);
        }
        return null;
    }

    /** Unidad que lleva el pedido y su camino hacia él (posición actual incluida). */
    private static List<Nodo> caminoHacia(Simulacion s, String pedido) {
        for (int k = 1; k <= 15; k++) {
            for (String pre : new String[]{"TA", "TM", "TB"}) {
                Unidad u = s.unidad(String.format("%s%02d", pre, k));
                if (u == null) continue;
                for (Parada p : u.paradas) {
                    if (p.tipo() == Parada.Tipo.ENTREGA && pedido.equals(p.pedidoId())) {
                        List<Nodo> r = new ArrayList<>();
                        r.add(u.nodo);
                        r.addAll(u.camino);
                        return r;
                    }
                }
            }
        }
        return null;
    }

    private static boolean cruzaFila18(List<Nodo> camino) {
        for (Nodo n : camino) if (n.y() == 18 && n.x() >= 22 && n.x() <= 32) return true;
        return false;
    }

    @Test
    void laUnidadEvitaUnaCalleQueSeVaACerrarPronto() throws IOException {
        // calle y = 18 entre x = 22 y 32, cerrada de 11:15 a 13:00; el cliente está justo al norte del central (27,14).
        // La unidad sale cerca de las 11:02 (tras su refrigerio): el bloqueo empieza dentro de la hora de anticipación.
        Simulacion s = operacion("06d11h15m-06d13h00m:22,18,32,18", "");
        s.avanzar(5);
        s.registrarPedido("c1", new Nodo(27, 24), 4, 36);
        List<Nodo> c = caminoAlSalir(s, "P1");
        assertNotNull(c, "el pedido se asigna y la unidad sale");
        assertTrue(s.ahora().isBefore(LocalDateTime.of(2026, 11, 6, 11, 15)), "sale antes de que empiece el bloqueo");
        assertFalse(cruzaFila18(c), "el camino rodea la calle que se cierra a las 11:15: " + c);
        assertTrue(s.snapshotJson().contains("\"bloqueosPrevistos\":1"), "el bloqueo de las 11:15 se conoce de antemano");
        assertTrue(s.snapshotJson().contains("\"anticipacionBloqueosHoras\":1"));
        assertFalse(s.snapshotJson().contains("\"vertices\":[[22,18],[32,18]]"), "en el mapa solo se dibujan los vigentes");
        // a las 11:15 el bloqueo ya es vigente y se dibuja en el mapa
        while (s.ahora().isBefore(LocalDateTime.of(2026, 11, 6, 11, 16))) s.avanzar(60);
        String json = s.snapshotJson();
        assertTrue(json.contains("\"vertices\":[[22,18],[32,18]]"));
        assertTrue(json.contains("\"bloqueosPrevistos\":0"));
    }

    @Test
    void conAnticipacionCeroSoloCuentanLosVigentesComoAntes() throws IOException {
        Simulacion s = operacion("06d11h15m-06d13h00m:22,18,32,18", "planificador.anticipacionBloqueosHoras=0\n");
        s.avanzar(5);
        s.registrarPedido("c1", new Nodo(27, 24), 4, 36);
        List<Nodo> c = caminoAlSalir(s, "P1");
        assertNotNull(c);
        assertTrue(s.ahora().isBefore(LocalDateTime.of(2026, 11, 6, 11, 15)));
        assertTrue(cruzaFila18(c), "sin anticipación toma el camino recto, que pasa por la calle que se cerrará");
        assertTrue(s.snapshotJson().contains("\"bloqueosPrevistos\":0"));
    }

    @Test
    void unBloqueoQueSeIniciaFueraDeLaAnticipacionNoSeConsideraTodavia() throws IOException {
        // empieza a las 13:30: más de 1 h después de que la unidad sale (cerca de las 11:02)
        Simulacion s = operacion("06d13h30m-06d15h00m:22,18,32,18", "");
        s.avanzar(5);
        s.registrarPedido("c1", new Nodo(27, 24), 4, 36);
        List<Nodo> c = caminoAlSalir(s, "P1");
        assertNotNull(c);
        assertTrue(s.ahora().isBefore(LocalDateTime.of(2026, 11, 6, 12, 30)));
        assertTrue(cruzaFila18(c), "un bloqueo que empieza en más de 1 h todavía no cambia el camino");
    }

    @Test
    void siLosBloqueosFuturosDejanSinCaminoUnDestinoSeUsanSoloLosVigentes() throws IOException {
        // anillo cerrado alrededor del cliente (40,40), desde las 10:30: con él, el cliente quedaría sin camino
        Simulacion s = operacion("06d10h30m-06d12h00m:39,39,41,39,41,41,39,41,39,39", "");   // dentro de la hora
        s.avanzar(5);
        s.registrarPedido("c1", new Nodo(40, 40), 4, 36);
        s.avanzar(5);
        assertNotNull(caminoHacia(s, "P1"), "el pedido igual se planifica");
        assertFalse(s.snapshotJson().contains("\"planesSoloVigentes\":0"), "ese plan usó solo los bloqueos vigentes");
        assertEquals(Simulacion.Estado.EJECUTANDO, s.estado());
    }

    @Test
    void ningunaUnidadAtraviesaUnNodoBloqueadoVigente() throws IOException {
        // durante el bloqueo, ninguna unidad pasa por sus nodos interiores (solo puede llegar a un cliente sobre él)
        Simulacion s = operacion("06d10h05m-06d14h00m:22,18,32,18", "");
        s.avanzar(5);
        for (int i = 0; i < 6; i++) s.registrarPedido("c" + i, new Nodo(20 + 3 * i, 30), 3, 8);
        for (int paso = 0; paso < 3 * 60; paso++) {
            s.avanzar(60);
            if (s.ahora().isBefore(LocalDateTime.of(2026, 11, 6, 10, 5))) continue;
            for (int k = 1; k <= 15; k++) {
                for (String pre : new String[]{"TA", "TM", "TB"}) {
                    Unidad u = s.unidad(String.format("%s%02d", pre, k));
                    if (u == null) continue;
                    for (Nodo n : u.camino) {
                        assertFalse(n.y() == 18 && n.x() >= 22 && n.x() <= 32,
                                u.id + " tiene en su camino el nodo bloqueado " + n + " a las " + s.ahora());
                    }
                }
            }
        }
    }
}
