package pe.pucp.paqrap.app.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;

import org.junit.jupiter.api.Test;

import pe.pucp.paqrap.app.Config;
import pe.pucp.paqrap.app.datos.Formato;
import pe.pucp.paqrap.app.modelo.Averia;
import pe.pucp.paqrap.app.modelo.Nodo;
import pe.pucp.paqrap.app.modelo.Parada;
import pe.pucp.paqrap.app.modelo.Pedido;
import pe.pucp.paqrap.app.modelo.Unidad;

/**
 * v03: averías de las unidades de transporte según la P&R (pregunta 3) y la Lista de Exigencias v03
 * (RF05-04, RF05-05, RF05-09 y RF07-01). Cada prueba usa una carpeta de datos propia y vacía, de modo que solo
 * hay los pedidos y averías que la prueba registra.
 */
class AveriasTest {

    private static final Nodo CENTRAL = new Nodo(27, 14);

    private Path carpeta;

    /** Carpeta de datos vacía con su config.properties (presupuesto del planificador reducido para las pruebas). */
    private void prepararCarpeta() throws IOException {
        carpeta = Files.createTempDirectory("rutapaq-averias");
        Files.writeString(carpeta.resolve("config.properties"), "datos.carpeta="
                + carpeta.toString().replace('\\', '/') + "\n" + "planificador.presupuestoSegundos=0.3\n");
    }

    private Simulacion crear(LocalDateTime inicio) throws IOException {
        Config cfg = new Config(carpeta.resolve("config.properties"));
        return new Simulacion(cfg, Simulacion.Escenario.SEMANAL, inicio, Planificadores.crear(cfg.planificador()));
    }

    private Simulacion nueva(LocalDateTime inicio) throws IOException {
        prepararCarpeta();
        return crear(inicio);
    }

    private static void hasta(Simulacion s, LocalDateTime objetivo) throws IOException {
        double faltan = Duration.between(s.ahora(), objetivo).toMillis() / 1000.0;
        if (faltan > 0) s.avanzar(faltan);
    }

    /** Ubica una unidad sin tareas en un nodo, con cierta carga (como si hubiera llegado allí). */
    private static Unidad ubicar(Simulacion s, String id, int x, int y, int carga) {
        Unidad u = s.unidad(id);
        u.nodo = new Nodo(x, y);
        u.carga = carga;
        return u;
    }

    @Test
    void lasReglasDeDisponibilidadSiguenLaRespuestaDelCurso() {
        ReglasAveria r = new ReglasAveria(2, 4, 2, LocalTime.of(15, 0), 30, true, true);
        LocalDate dia = LocalDate.of(2026, 1, 12);
        LocalDateTime d = dia.atTime(10, 20);
        assertEquals(d.plusHours(2), r.disponible(1, d), "tipo 1: 2 h");
        assertEquals(d.plusHours(2), r.finEnLugar(1, d), "tipo 1: se queda en el lugar las 2 h");
        assertEquals(dia.atTime(23, 0), r.disponible(2, d), "tipo 2: turno de 07:00, el siguiente termina 23:00");
        assertEquals(d.plusHours(4), r.finEnLugar(2, d), "tipos 2 y 3: 4 h en el lugar");
        assertEquals(dia.plusDays(2).atTime(15, 0), r.disponible(3, d), "tipo 3: 2 días y turno de 15:00");
        assertEquals(d.plusHours(4), r.finEnLugar(3, d));

        assertEquals(dia.atTime(23, 0), r.disponible(2, dia.atTime(14, 59)));
        assertEquals(dia.plusDays(1).atTime(7, 0), r.disponible(2, dia.atTime(15, 0)), "a las 15:00 ya es el turno de 15:00");
        assertEquals(dia.plusDays(1).atTime(15, 0), r.disponible(2, dia.atTime(23, 30)));
        assertEquals(dia.atTime(15, 0), r.disponible(2, dia.atTime(6, 0)), "06:00 pertenece al turno de 23:00");
        assertEquals(dia.plusDays(3).atTime(15, 0), r.disponible(3, dia.atTime(16, 0)), "a las 15:00 del día 2 aún no pasan 2 días");
        assertEquals(dia.plusDays(2).atTime(15, 0), r.disponible(3, dia.atTime(15, 0)));
    }

    @Test
    void elArchivoDeAveriasProgramadasTieneUnFormatoSimple() {
        YearMonth m = YearMonth.of(2026, 3);
        assertEquals("averias.202603.txt", Formato.archivoAverias(m));
        Formato.LineaAveria a = Formato.averia(m, "05d10h20m:TA03,2");
        assertEquals(LocalDateTime.of(2026, 3, 5, 10, 20), a.instante());
        assertEquals("TA03", a.unidad());
        assertEquals(2, a.tipo());
        assertEquals("TM12", Formato.averia(m, " 01d00h05m : tm12 , TI3 ").unidad());
        assertEquals(3, Formato.averia(m, "01d00h05m:TM12,TI3").tipo());
        assertEquals(1, Formato.averia(m, "01d00h05m:TB01,T1").tipo());
        for (String mala : new String[]{"01d00h05m:TA01,4", "TA01,2", "01d00h05m:TA01"}) {
            boolean rechazada = false;
            try {
                Formato.averia(m, mala);
            } catch (IllegalArgumentException e) {
                rechazada = true;
            }
            assertTrue(rechazada, "debe rechazar: " + mala);
        }
    }

    @Test
    void tipo1DetieneLaUnidadDosHorasEnSuPosicionYLuegoTerminaLaCuadra() throws IOException {
        Simulacion s = nueva(LocalDate.of(2026, 3, 2).atTime(8, 0));
        Unidad u = ubicar(s, "TA01", 30, 14, 10);
        u.camino.add(new Nodo(31, 14));               // va a mitad de la cuadra de (30,14) a (31,14)
        u.progreso = 0.4;
        Averia av = s.registrarAveria("TA01", 1);

        assertEquals("AVERIADA", estado(s, "TA01"));
        assertEquals(30.4, av.x, 1.0E-9);
        assertEquals(2 * 3600.0, av.disponible, 1.0E-9);
        s.avanzar(3600);
        assertEquals(30.4, u.x(), 1.0E-9, "durante la avería no se mueve");
        assertEquals(10, u.carga, "conserva su carga");

        s.avanzar(3600 + 60);
        assertEquals(null, u.averia);
        assertEquals(Averia.Fase.FINALIZADA, av.fase);
        assertEquals(new Nodo(31, 14), u.nodo, "al terminar la avería completa la cuadra hasta la esquina");
        assertEquals(10, u.carga);
    }

    @Test
    void laAveriaLiberaSusPedidosYSeReplanificaDeInmediato() throws IOException {
        Simulacion s = nueva(LocalDate.of(2026, 3, 2).atTime(8, 0));
        s.avanzar(30);
        String p1 = s.registrarPedido("c1", new Nodo(50, 30), 4, 36);
        s.avanzar(60);
        Unidad u = unidadQueAtiende(s, p1);
        assertTrue(u != null, "el pedido debe quedar asignado");
        s.avanzar(600);                                // ya salió hacia el cliente (39 km)
        Pedido p = s.pedido(p1);
        assertEquals(4, p.asignado);

        Averia av = s.registrarAveria(u.id, 2);
        assertTrue(av.pedidos.contains(p1));
        assertTrue(u.paradas.isEmpty());
        assertEquals(0, p.asignado, "el pedido vuelve al planificador");

        s.avanzar(30);                                 // ciclo extraordinario en el siguiente paso
        assertEquals(4, p.asignado, "el pedido se reasigna de inmediato");
        Unidad otra = unidadQueAtiende(s, p1);
        assertTrue(otra != null && !otra.id.equals(u.id), "lo atiende otra unidad: una avería tipo 2 no recibe rutas");
    }

    @Test
    void tipo2SeQuedaCuatroHorasYPasaAlCentralConLosPaquetesNoTrasvasados() throws IOException {
        LocalDate dia = LocalDate.of(2026, 3, 2);
        Simulacion s = nueva(dia.atTime(10, 0));
        Unidad u = ubicar(s, "TA01", 40, 40, 10);
        Averia av = s.registrarAveria("TA01", 2);

        hasta(s, dia.atTime(13, 59));
        assertEquals(Averia.Fase.EN_LUGAR, av.fase);
        assertEquals(new Nodo(40, 40), u.nodo);
        s.registrarPedido("c1", new Nodo(28, 14), 2, 36);    // al lado del central: no debe tocarle a TA01
        s.avanzar(60);
        assertTrue(u.paradas.isEmpty(), "una unidad con avería tipo 2 no recibe rutas");

        hasta(s, dia.atTime(14, 1));
        assertEquals(Averia.Fase.EN_CENTRAL, av.fase);
        assertEquals(CENTRAL, u.nodo, "pasa de forma instantánea al almacén central");
        assertEquals(0, u.carga);
        assertEquals(10, av.devueltos, "los paquetes no trasvasados se llevan al almacén central");

        hasta(s, dia.atTime(22, 59));
        assertEquals("AVERIADA", estado(s, "TA01"));
        hasta(s, dia.atTime(23, 1));
        assertEquals(null, u.averia, "vuelve al final del turno siguiente (15:00 a 23:00)");
        assertEquals(Averia.Fase.FINALIZADA, av.fase);
    }

    @Test
    void tipo3VuelveTrasDosDiasEnElTurnoDeLasQuince() throws IOException {
        LocalDate dia = LocalDate.of(2026, 3, 2);
        Simulacion s = nueva(dia.atTime(16, 0));
        ubicar(s, "TM05", 33, 20, 3);
        Averia av = s.registrarAveria("TM05", 3);
        assertEquals(dia.plusDays(3).atTime(15, 0), dia.atTime(16, 0).plusSeconds(Math.round(av.disponible)),
                "a las 15:00 del 04/03 aún no pasan 2 días: vuelve el 05/03 a las 15:00");

        hasta(s, dia.plusDays(3).atTime(14, 59));
        assertEquals(Averia.Fase.EN_CENTRAL, av.fase);
        hasta(s, dia.plusDays(3).atTime(15, 1));
        assertEquals(Averia.Fase.FINALIZADA, av.fase);
    }

    @Test
    void elTrasvaseLlevaLosPaquetesDeLaAveriadaAOtraUnidad() throws IOException {
        LocalDate dia = LocalDate.of(2026, 3, 2);
        Simulacion s = nueva(dia.atTime(9, 0));
        Unidad averiada = ubicar(s, "TA01", 40, 40, 10);
        Averia av = s.registrarAveria("TA01", 2);
        Unidad cercana = ubicar(s, "TA02", 41, 40, 0);
        cercana.turnoConRefrigerio = dia.atTime(7, 0);          // ya tomó su refrigerio en este turno
        String p1 = s.registrarPedido("c1", new Nodo(42, 40), 8, 36);
        s.avanzar(60);

        Parada primera = cercana.paradas.peekFirst();
        assertTrue(primera != null && primera.tipo() == Parada.Tipo.RECARGA, "la unidad cercana va a recoger");
        assertEquals("TRV-TA01", primera.almacenId());
        assertEquals(10, primera.cantidad(), "le caben los 10 paquetes de la averiada");

        s.avanzar(2 * 3600);
        assertEquals(10, av.trasvasados, "recoge todo lo que le cabe de la averiada");
        assertTrue(av.receptoras.contains("TA02"));
        assertEquals(0, averiada.carga);
        Pedido p = s.pedido(p1);
        assertTrue(p == null || p.completo(), "el pedido se entrega con los paquetes trasvasados");

        hasta(s, dia.atTime(13, 1));
        assertEquals(Averia.Fase.EN_CENTRAL, av.fase);
        assertEquals(0, av.devueltos, "no queda nada por devolver al almacén central");
    }

    @Test
    void noSeOfreceElTrasvaseSiNoAlcanzaATerminarAntesDelTraslado() throws IOException {
        LocalDate dia = LocalDate.of(2026, 3, 2);
        Simulacion s = nueva(dia.atTime(9, 0));
        ubicar(s, "TA01", 40, 40, 10);
        s.registrarAveria("TA01", 2);
        hasta(s, dia.atTime(12, 40));                  // la llevan al central a las 13:00: no caben 30 min
        Unidad cercana = ubicar(s, "TA02", 41, 40, 0);
        s.registrarPedido("c1", new Nodo(42, 40), 8, 36);
        s.avanzar(60);
        for (Parada p : cercana.paradas) {
            assertFalse(p.almacenId() != null && p.almacenId().startsWith("TRV-"), "no debe ir a trasvasar");
        }
    }

    @Test
    void noSePuedeRegistrarUnaAveriaInvalida() throws IOException {
        Simulacion s = nueva(LocalDate.of(2026, 3, 2).atTime(8, 0));
        s.avanzar(30);
        s.registrarAveria("TB01", 1);
        assertTrue(lanza(() -> s.registrarAveria("TB01", 2)) instanceof IllegalStateException, "ya está averiada");
        assertTrue(lanza(() -> s.registrarAveria("TB02", 4)) instanceof IllegalArgumentException, "tipo inválido");
        assertTrue(lanza(() -> s.registrarAveria("TX99", 1)) instanceof IllegalArgumentException, "unidad inexistente");
        assertEquals(1, s.averias().size());
    }

    @Test
    void lasAveriasProgramadasSeAplicanEnSuInstanteYSeOmitenLasQueNoCorresponden() throws IOException {
        LocalDate dia = LocalDate.of(2026, 3, 2);
        Simulacion s = nueva(dia.atTime(8, 0));
        Files.writeString(carpeta.resolve("averias.202603.txt"), String.join("\n",
                "# averías de prueba",
                "01d09h00m:TM02,3",            // antes del inicio de la simulación: no se usa
                "02d09h00m:TM01,1",
                "02d09h30m:TM01,2",            // TM01 sigue averiada hasta las 11:00: se omite
                "02d10h00m:TX99,2",            // no existe en la flota: se omite
                "02d10h00m:esto no es una avería") + "\n");
        hasta(s, dia.atTime(9, 1));
        Averia av = s.unidad("TM01").averia;
        assertTrue(av != null && av.tipo == 1);
        assertEquals(Averia.ARCHIVO, av.origen);
        assertEquals(3600.0, av.inicio, 1.0E-9, "ocurre en el instante del archivo");

        hasta(s, dia.atTime(10, 1));
        assertEquals(1, s.averias().size());
        String json = s.snapshotJson();
        assertTrue(json.contains("\"averiasOmitidas\":2"), json.substring(0, 400));
        assertTrue(json.contains("\"lineasConError\":1"));
        assertTrue(json.contains("\"estado\":\"AVERIADA\""));
        assertEquals(null, s.unidad("TM02").averia);
    }

    @Test
    void elMantenimientoLlevaAlCentralAUnaUnidadAveriadaQueSigueEnElLugar() throws IOException {
        LocalDate dia = LocalDate.of(2026, 3, 2);
        prepararCarpeta();
        Files.writeString(carpeta.resolve("mant.preventivo.09.10.txt"), "20260903:TA01\n");   // día 3 de los meses impares
        Simulacion s = crear(dia.atTime(21, 0));
        Unidad u = ubicar(s, "TA01", 40, 40, 6);
        Averia av = s.registrarAveria("TA01", 2);     // turno de 15:00: vuelve a las 07:00 del día siguiente

        hasta(s, dia.plusDays(1).atTime(0, 1));
        assertTrue(u.enMantenimiento);
        assertEquals(Averia.Fase.EN_CENTRAL, av.fase, "el mantenimiento la lleva al central antes de las 4 h");
        assertEquals(6, av.devueltos);
        assertEquals(CENTRAL, u.nodo);

        hasta(s, dia.plusDays(1).atTime(7, 1));
        assertEquals(null, u.averia);
        assertEquals("MANTENIMIENTO", estado(s, "TA01"), "la avería terminó pero sigue el día de mantenimiento");
    }

    // ------------------------------------------------------------------ utilidades

    private static Unidad unidadQueAtiende(Simulacion s, String pedido) {
        for (String id : new String[]{"TA01", "TA02", "TA03", "TA04", "TA05", "TA06", "TA07", "TA08", "TA09", "TA10",
                "TM01", "TM02", "TM03", "TM04", "TM05", "TM06", "TM07", "TM08", "TM09", "TM10", "TM11", "TM12", "TM13",
                "TM14", "TM15", "TB01", "TB02", "TB03", "TB04", "TB05", "TB06", "TB07", "TB08", "TB09", "TB10", "TB11",
                "TB12"}) {
            Unidad u = s.unidad(id);
            for (Parada p : u.paradas) if (p.tipo() == Parada.Tipo.ENTREGA && pedido.equals(p.pedidoId())) return u;
        }
        return null;
    }

    private static String estado(Simulacion s, String id) {
        String json = s.snapshotJson();
        int i = json.indexOf("{\"id\":\"" + id + "\",\"tipo\"");
        int e = json.indexOf("\"estado\":\"", i) + 10;
        return json.substring(e, json.indexOf('"', e));
    }

    private interface Accion {
        void ejecutar() throws Exception;
    }

    private static Exception lanza(Accion a) {
        try {
            a.ejecutar();
        } catch (Exception e) {
            return e;
        }
        return null;
    }
}
