package pe.pucp.paqrap.app.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;

import org.junit.jupiter.api.Test;

import pe.pucp.paqrap.app.Config;
import pe.pucp.paqrap.app.datos.Formato;
import pe.pucp.paqrap.app.datos.RegistroPedidos;
import pe.pucp.paqrap.app.modelo.Nodo;
import pe.pucp.paqrap.app.modelo.Parada;
import pe.pucp.paqrap.app.modelo.Unidad;

/**
 * S13v1: operación día a día (escenario DIA_A_DIA, menú Tiempo real). Las pruebas avanzan el reloj de forma síncrona
 * (avanzar), igual que las demás; en el servidor el reloj sigue a la hora actual de Lima.
 */
class DiaADiaTest {

    /** 10:02 de un día sin mantenimientos en el archivo de prueba. */
    private static final LocalDateTime INICIO = LocalDateTime.of(2026, 11, 6, 10, 2);

    private static Path carpeta(String extra) throws IOException {
        Path c = Files.createTempDirectory("rutapaq-diario");
        Files.writeString(c.resolve("config.properties"), "datos.carpeta=" + c.toString().replace('\\', '/') + "\n"
                + "planificador.presupuestoSegundos=0.2\n" + extra);
        return c;
    }

    private static Simulacion operacion(Path c) throws IOException {
        Config cfg = new Config(c.resolve("config.properties"));
        return new Simulacion(cfg, Simulacion.Escenario.DIA_A_DIA, INICIO, Planificadores.crear(cfg.planificador()));
    }

    private static boolean asignado(Simulacion s, String pedido) {
        for (int k = 1; k <= 15; k++) {
            for (String pre : new String[]{"TA", "TM", "TB"}) {
                Unidad u = s.unidad(String.format("%s%02d", pre, k));
                if (u == null) continue;
                for (Parada p : u.paradas) if (p.tipo() == Parada.Tipo.ENTREGA && pedido.equals(p.pedidoId())) return true;
            }
        }
        return false;
    }

    @Test
    void noLeeLosArchivosDeVentasSoloElRegistroEnLinea() throws IOException {
        Path c = carpeta("");
        // pedidos del archivo de ventas del mismo mes: en la operación día a día no deben entrar
        Files.write(c.resolve("ventas.202611.txt"), List.of("06d10h05m:30,20,c1,3,36", "06d10h30m:40,25,c2,2,8"));
        Simulacion s = operacion(c);
        s.avanzar(3600);
        assertEquals(0, s.recibidos(), "los pedidos del archivo de ventas no se leen");

        String id = s.registrarPedido("c9167", new Nodo(30, 20), 3, 8);
        assertEquals("P1", id);
        assertEquals(1, s.recibidos());
        s.avanzar(1);                                    // replanificación inmediata, sin esperar el siguiente Sa
        assertTrue(asignado(s, id), "el pedido registrado se asigna a una unidad");
    }

    @Test
    void planificaCadaSaAlineadoAlRelojYAdemasAlRegistrarUnPedido() throws IOException {
        Simulacion s = operacion(carpeta("escenario.diario.saMinutos=5\n"));
        s.avanzar(1);
        assertEquals(1, s.ciclos(), "primer plan al abrir (10:02)");
        s.avanzar(170);                                  // 10:04:51
        assertEquals(1, s.ciclos());
        s.avanzar(20);                                   // 10:05:11: ejecución programada de las 10:05
        assertEquals(2, s.ciclos(), "siguiente ejecución alineada al reloj: 10:05");
        s.registrarPedido("c1", new Nodo(10, 10), 2, 36);
        s.avanzar(1);
        assertEquals(3, s.ciclos(), "registrar un pedido replanifica de inmediato");
        s.avanzar(270);                                  // 10:09:42: la programada sigue siendo la de las 10:10
        assertEquals(3, s.ciclos());
        s.avanzar(30);                                   // 10:10:12
        assertEquals(4, s.ciclos(), "la replanificación por el pedido no corre la programada de las 10:10");
        assertTrue(s.snapshotJson().contains("\"saSeg\":300"), "Sa = Sc = 5 min con K = 1");
    }

    @Test
    void usaLosBloqueosDelArchivoDelMes() throws IOException {
        Path c = carpeta("");
        Files.write(c.resolve("bloqueo.2611.txt"), List.of("06d09h00m-06d12h00m:20,10,25,10", "06d14h00m-06d15h00m:5,5,5,9"));
        Simulacion s = operacion(c);
        s.avanzar(60);
        String json = s.snapshotJson();
        int i = json.indexOf("\"bloqueos\":[");
        assertTrue(i >= 0 && json.charAt(i + "\"bloqueos\":[".length()) != ']', "el bloqueo vigente a las 10:02 se aplica");
        assertTrue(json.contains("\"vertices\":[[20,10],[25,10]]"), "con los vértices del archivo");
        assertFalse(json.contains("[[5,5],[5,9]]"), "el bloqueo de las 14:00 aún no está vigente");
    }

    @Test
    void guardaLosPedidosRegistradosEnElFormatoDelProfesor() throws IOException {
        Path c = carpeta("");
        Simulacion s = operacion(c);
        s.avanzar(60);                                   // 10:03
        s.registrarPedido("c9167", new Nodo(45, 43), 12, 36);
        Path archivo = c.resolve("registrados").resolve("ventas.202611.txt");
        assertTrue(Files.exists(archivo), "se crea datos/registrados/ventas.aaaamm.txt");
        List<String> lineas = Files.readAllLines(archivo);
        assertEquals(List.of("06d10h03m:45,43,c9167,12,36"), lineas);
        Formato.LineaPedido l = Formato.pedido(YearMonth.of(2026, 11), lineas.get(0));
        assertEquals(LocalDateTime.of(2026, 11, 6, 10, 3), l.registro());
        assertEquals(new Nodo(45, 43), l.nodo());
        assertEquals(12, l.cantidad());
        assertEquals(36, l.plazoHoras());
        assertEquals("06d10h03m:45,43,c9167,12,36", RegistroPedidos.linea(LocalDateTime.of(2026, 11, 6, 10, 3), 45, 43, "c9167", 12, 36));
        // no se mezcla con los archivos de ventas del profesor
        assertFalse(Files.exists(c.resolve("ventas.202611.txt")));
    }

    @Test
    void unPedidoVencidoNoDetieneLaOperacionYSeSigueEntregando() throws IOException {
        // una sola bicicleta y un pedido que no puede llegar a tiempo: en el colapso terminaría; aquí sigue
        Simulacion s = operacion(carpeta("ien.autos=0\nien.motos=0\nien.bicicletas=1\nescenario.diario.saMinutos=60\n"));
        s.avanzar(60);
        s.registrarPedido("c1", new Nodo(55, 40), 4, 4);   // 54 km desde el central: 4,5 h en bicicleta
        s.avanzar(6 * 3600);
        assertEquals(Simulacion.Estado.EJECUTANDO, s.estado(), "no hay colapso en la operación día a día");
        assertTrue(s.vencidos() >= 1, "el pedido cuenta como vencido");
        assertTrue(s.pedido("P1") != null && s.pedido("P1").asignado + s.pedido("P1").entregado > 0,
                "el pedido vencido se sigue planificando para entregarlo");
    }

    @Test
    void cerrarOperacionesDejaElReporteYNoSePuedePausar() throws IOException {
        Simulacion s = operacion(carpeta(""));
        s.avanzar(60);
        assertFalse(s.pausar(), "la operación día a día no se pausa");
        s.registrarPedido("c1", new Nodo(30, 20), 4, 12);
        s.avanzar(5);
        assertTrue(s.cerrarOperaciones());
        assertEquals(Simulacion.Estado.TERMINADA, s.estado());
        assertEquals("CIERRE", s.tipoFin());
        assertFalse(s.cerrarOperaciones(), "ya estaba cerrada");
        String plan = s.planJson();
        assertTrue(plan.contains("\"escenario\":\"DIA_A_DIA\"") && plan.contains("\"ref\":\"P1\""),
                "reporte de la última planificación estable con el pedido (G09)");
        assertTrue(s.planCsv().startsWith("﻿Última planificación estable;"));
        boolean rechazado = false;
        try {
            s.registrarPedido("c2", new Nodo(1, 1), 1, 36);
        } catch (IllegalStateException e) {
            rechazado = true;
        }
        assertTrue(rechazado, "con las operaciones cerradas no se registran pedidos");
    }

    @Test
    void elPanelDeAlmacenesMuestraLoQueVieneEnCamino() throws IOException {
        // S13v1 (E20): las unidades que van a recargar a un almacén aparecen con la hora de llegada y lo que traen
        Simulacion s = operacion(carpeta(""));
        s.avanzar(60);
        s.registrarPedido("c1", new Nodo(60, 45), 40, 36);
        s.avanzar(1);
        String json = s.snapshotJson();
        assertEquals(3, json.split("\"llegan\":\\{\"unidades\":", -1).length - 1, "los 3 almacenes informan lo que llega");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"llegan\":\\{\"unidades\":(\\d+)").matcher(json);
        int total = 0;
        while (m.find()) total += Integer.parseInt(m.group(1));
        assertTrue(total >= 1, "al menos una unidad va a recargar para atender 40 productos");
        assertTrue(json.contains("\"trae\":") && json.contains("\"recarga\":"));
    }

    @Test
    void laSimulacionNoCierraOperacionesYElMantenimientoDura48Horas() throws IOException {
        Path c = carpeta("");
        Config cfg = new Config(c.resolve("config.properties"));
        Simulacion sem = new Simulacion(cfg, Simulacion.Escenario.SEMANAL, INICIO, Planificadores.crear(cfg.planificador()));
        assertFalse(sem.cerrarOperaciones(), "Cerrar operaciones es solo de la operación día a día");
        for (var h : Simulacion.horasMantenimiento(cfg).values()) assertEquals(48.0, h, 1e-9);
        assertEquals("America/Lima", Simulacion.zona(cfg).getId());
        Config otra = new Config(carpeta("escenario.diario.zona=Zona/Inexistente\n").resolve("config.properties"));
        assertEquals("America/Lima", Simulacion.zona(otra).getId(), "una zona inválida usa la de Lima");
    }
}
