package pe.pucp.paqrap.app.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import pe.pucp.paqrap.app.Config;
import pe.pucp.paqrap.app.modelo.Nodo;

/**
 * v04: simulación hasta el colapso. Al colapsar se guarda el reporte (criterio, instante, Tc, pedido involucrado y
 * estado de la flota); si se acaban los datos sin que nada venza, termina sin colapso. v0.4.1: las averías no se
 * leen de archivo en ningún escenario, solo se registran desde el visualizador (también en el colapso).
 */
class ColapsoTest {

    private Path carpeta;

    private Simulacion nueva(Simulacion.Escenario escenario, LocalDateTime inicio, String extra) throws IOException {
        Files.writeString(carpeta.resolve("config.properties"), "datos.carpeta="
                + carpeta.toString().replace('\\', '/') + "\n" + "planificador.presupuestoSegundos=0.3\n" + extra);
        Config cfg = new Config(carpeta.resolve("config.properties"));
        return new Simulacion(cfg, escenario, inicio, Planificadores.crear(cfg.planificador()));
    }

    @Test
    void alColapsarSeGuardaElReporteConElPedidoYLaFlota() throws IOException {
        carpeta = Files.createTempDirectory("rutapaq-colapso");
        Simulacion s = nueva(Simulacion.Escenario.COLAPSO, LocalDateTime.of(2026, 1, 12, 8, 0), "");
        s.avanzar(60);
        for (int i = 1; i <= 10; i++) s.registrarAveria(String.format("TA%02d", i), 2);
        for (int i = 1; i <= 15; i++) s.registrarAveria(String.format("TM%02d", i), 2);
        for (int i = 1; i <= 12; i++) s.registrarAveria(String.format("TB%02d", i), 1);
        String id = s.registrarPedido("c-lejano", new Nodo(70, 50), 2, 4);   // solo una bici, 6,6 h: vence
        s.avanzar(8 * 3600);

        assertEquals(Simulacion.Estado.TERMINADA, s.estado());
        String json = s.snapshotJson();
        assertTrue(json.contains("\"tipoFin\":\"COLAPSO\""), json);
        assertTrue(json.contains("\"colapso\":{\"criterio\":\"P\""), "reporte con el criterio P");
        assertTrue(json.contains("\"pedido\":{\"id\":\"" + id + "\",\"cliente\":\"c-lejano\",\"x\":70,\"y\":50"),
                "reporte con el pedido involucrado");
        assertTrue(json.contains("\"hora\":\"2026-01-12T12:01"), "el colapso ocurre al vencer el plazo de 4 h (pasos de 30 s)");
        assertTrue(json.contains("\"flota\":{\"total\":37,"), "reporte con el estado de la flota");
        assertTrue(json.contains("\"averiadasTipo2\":25"), "autos y motos siguen averiados en ese instante");
    }

    @Test
    void sinColapsoTerminaCuandoSeAcabanLosDatosYSeEntregoTodo() throws IOException {
        carpeta = Files.createTempDirectory("rutapaq-colapso");
        // un solo mes de datos (enero de 2026) con un pedido fácil el último día
        Files.writeString(carpeta.resolve("ventas.202601.txt"), "31d10h00m:30,15,c1,02,36\n");
        Simulacion s = nueva(Simulacion.Escenario.COLAPSO, LocalDateTime.of(2026, 1, 31, 0, 0), "");
        s.avanzar(23.9 * 3600);
        assertEquals(Simulacion.Estado.EJECUTANDO, s.estado(), "aún no se acaban los datos");
        s.avanzar(2 * 3600);
        assertEquals(Simulacion.Estado.TERMINADA, s.estado());
        String json = s.snapshotJson();
        assertTrue(json.contains("\"tipoFin\":\"SIN_COLAPSO\""), json);
        assertTrue(json.contains("\"colapso\":null"));
        assertTrue(json.contains("\"entregados\":1"));
    }

    @Test
    void enElColapsoLasAveriasSoloSeRegistranDesdeElVisualizador() throws IOException {
        carpeta = Files.createTempDirectory("rutapaq-colapso");
        Files.writeString(carpeta.resolve("averias.202601.txt"), "01d01h00m:TA01,1\n");   // ya no se lee
        LocalDateTime inicio = LocalDateTime.of(2026, 1, 1, 0, 0);
        for (Simulacion.Escenario e : new Simulacion.Escenario[]{Simulacion.Escenario.COLAPSO, Simulacion.Escenario.SEMANAL}) {
            Simulacion s = nueva(e, inicio, "averia.archivo.colapso=true\naveria.archivo.habilitado=true\n");
            s.avanzar(2 * 3600);
            assertEquals(0, s.averias().size(), e + ": el archivo no genera averías");
        }
        Simulacion colapso = nueva(Simulacion.Escenario.COLAPSO, inicio, "");
        colapso.avanzar(60);
        colapso.registrarAveria("TA01", 2);
        assertEquals(1, colapso.averias().size(), "el registro desde el visualizador funciona en el colapso");
    }
}
