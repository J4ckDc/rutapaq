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
 * estado de la flota); si se acaban los datos sin que nada venza, termina sin colapso; y las averías programadas del
 * archivo no se usan en este escenario salvo averia.archivo.colapso=true.
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
    void lasAveriasDelArchivoNoSeUsanEnElColapsoSalvoQueSeConfigure() throws IOException {
        carpeta = Files.createTempDirectory("rutapaq-colapso");
        Files.writeString(carpeta.resolve("averias.202601.txt"), "01d01h00m:TA01,1\n");
        LocalDateTime inicio = LocalDateTime.of(2026, 1, 1, 0, 0);

        Simulacion colapso = nueva(Simulacion.Escenario.COLAPSO, inicio, "");
        colapso.avanzar(2 * 3600);
        assertEquals(0, colapso.averias().size(), "colapso: sin averías del archivo");

        Simulacion cincoDias = nueva(Simulacion.Escenario.SEMANAL, inicio, "");
        cincoDias.avanzar(2 * 3600);
        assertEquals(1, cincoDias.averias().size(), "5 días: con averías del archivo");

        Simulacion conArchivo = nueva(Simulacion.Escenario.COLAPSO, inicio, "averia.archivo.colapso=true\n");
        conArchivo.avanzar(2 * 3600);
        assertEquals(1, conArchivo.averias().size(), "colapso con averia.archivo.colapso=true");
    }
}
