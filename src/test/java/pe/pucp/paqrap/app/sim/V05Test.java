package pe.pucp.paqrap.app.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import pe.pucp.paqrap.app.Config;
import pe.pucp.paqrap.app.datos.PlanMantenimiento;
import pe.pucp.paqrap.app.modelo.Nodo;
import pe.pucp.paqrap.app.modelo.Parada;
import pe.pucp.paqrap.app.modelo.Pedido;
import pe.pucp.paqrap.app.modelo.TipoUnidad;
import pe.pucp.paqrap.app.modelo.Unidad;
import pe.pucp.paqrap.planner.core.Configuracion;
import pe.pucp.paqrap.planner.model.TipoVehiculo;

/**
 * v0.5: flota configurable en un solo lugar, mantenimiento por tipo, semáforo con vacío, recarga configurable y la
 * información nueva del estado (envíos con productos, entregas de la ventana y última planificación estable).
 */
class V05Test {

    private static final LocalDateTime INICIO = LocalDateTime.of(2026, 1, 12, 8, 0);

    private static Path carpeta(String extra) throws IOException {
        Path c = Files.createTempDirectory("rutapaq-v05");
        Files.writeString(c.resolve("config.properties"), "datos.carpeta=" + c.toString().replace('\\', '/') + "\n"
                + "planificador.presupuestoSegundos=0.3\n" + extra);
        return c;
    }

    private static Simulacion nueva(String extra) throws IOException {
        Config cfg = new Config(carpeta(extra).resolve("config.properties"));
        return new Simulacion(cfg, Simulacion.Escenario.SEMANAL, INICIO, Planificadores.crear(cfg.planificador()));
    }

    private static String[] codigos() {
        String[] r = new String[37];
        int k = 0;
        for (int i = 1; i <= 10; i++) r[k++] = String.format("TA%02d", i);
        for (int i = 1; i <= 15; i++) r[k++] = String.format("TM%02d", i);
        for (int i = 1; i <= 12; i++) r[k++] = String.format("TB%02d", i);
        return r;
    }

    /** Unidad que lleva el pedido en su itinerario (null si ninguna). */
    private static Unidad quienLleva(Simulacion s, String pedido) {
        for (String id : codigos()) {
            Unidad u = s.unidad(id);
            for (Parada p : u.paradas) if (p.tipo() == Parada.Tipo.ENTREGA && pedido.equals(p.pedidoId())) return u;
        }
        return null;
    }

    @Test
    void laFlotaSeConfiguraEnUnSoloLugarParaElPlanificadorYLaSimulacion() throws IOException {
        try {
            new Config(carpeta("flota.moto.capacidad=10\nflota.moto.velocidadKmh=30\nflota.moto.costoKm=5\n").resolve("config.properties"));
            assertEquals(10, TipoVehiculo.MOTO.getCapacidad());
            assertEquals(10, TipoUnidad.MOTO.capacidad(), "la simulación lee el mismo valor que el planificador");
            assertEquals(30.0, TipoUnidad.MOTO.velocidadKmh(), 1e-9);
            assertEquals(5.0, TipoUnidad.MOTO.costoKm(), 1e-9);
            assertEquals(24, TipoUnidad.AUTO.capacidad(), "los tipos sin claves conservan sus valores");
            boolean rechazado = false;
            try {
                new Config(carpeta("flota.bici.capacidad=0\n").resolve("config.properties"));
            } catch (IllegalArgumentException e) {
                rechazado = true;
            }
            assertTrue(rechazado, "una capacidad no positiva se rechaza");
        } finally {
            TipoVehiculo.configurar(Configuracion.porDefecto());   // deja los valores del enunciado para las demás pruebas
        }
        assertEquals(8, TipoUnidad.MOTO.capacidad());
        assertEquals(4, TipoUnidad.BICI.capacidad());
    }

    @Test
    void laDuracionDelMantenimientoDependeDelTipoDeUnidad() throws IOException {
        Path c = carpeta("");
        Files.writeString(c.resolve("mant.preventivo.09.10.txt"), "20260913:TA01\n20260913:TB01\n20260913:TM01\n");
        // setiembre (impar) vale para enero: el 13/01/2026 entran las tres a las 00:00
        PlanMantenimiento plan = new PlanMantenimiento(c, Map.of(TipoUnidad.AUTO, 48.0, TipoUnidad.BICI, 7.0));
        LocalDateTime d13 = LocalDateTime.of(2026, 1, 13, 0, 0);
        assertFalse(plan.enMantenimiento("TA01", d13.minusMinutes(1)));
        assertTrue(plan.enMantenimiento("TA01", d13.plusHours(40)), "auto: 48 h");
        assertEquals(d13.plusHours(48), plan.finMantenimiento("TA01", d13.plusHours(1)));
        assertTrue(plan.enMantenimiento("TB01", d13.plusHours(6).plusMinutes(59)), "bicicleta: hasta las 07:00");
        assertFalse(plan.enMantenimiento("TB01", d13.plusHours(7)));
        // S13v1: el tipo sin duración indicada usa el valor por defecto, 48 h (P&R 19, 03/11/2026)
        assertTrue(plan.enMantenimiento("TM01", d13.plusHours(47)), "moto: 48 h por defecto");
        assertFalse(plan.enMantenimiento("TM01", d13.plusHours(48)));

        // S13v1: por defecto (sin claves) son 48 h para todos los tipos (P&R 19, 03/11/2026)
        Config cfg = new Config(carpeta("").resolve("config.properties"));
        Map<TipoUnidad, Double> horas = Simulacion.horasMantenimiento(cfg);
        for (TipoUnidad t : TipoUnidad.values()) assertEquals(48.0, horas.get(t), 1e-9);
        Config cfg2 = new Config(carpeta("mantenimiento.horas.auto=24\n").resolve("config.properties"));
        assertEquals(24.0, Simulacion.horasMantenimiento(cfg2).get(TipoUnidad.AUTO), 1e-9);
    }

    @Test
    void elSemaforoTieneVacioYElRojoEsMuyPoco() throws IOException {
        Indicadores ind = new Indicadores(new Config(carpeta("").resolve("config.properties")));
        assertEquals(Indicadores.Nivel.VACIO, ind.stock("almacen", 0, 30, 10));
        assertEquals(Indicadores.Nivel.ROJO, ind.stock("almacen", 5, 30, 10));
        assertEquals(Indicadores.Nivel.AMBAR, ind.stock("almacen", 15, 30, 10));
        assertEquals(Indicadores.Nivel.VERDE, ind.stock("almacen", 80, 30, 10));
        assertEquals(Indicadores.Nivel.VACIO, ind.stock("carga", 0, 50, 20));
        assertEquals(Indicadores.Nivel.VERDE, ind.stock("carga", 100, 50, 20), "una unidad llena ya no es roja");
    }

    @Test
    void porDefectoLaUnidadSeLlenaYConElParametroCargaSoloLoNecesario() throws IOException {
        for (boolean justo : new boolean[]{false, true}) {
            Simulacion s = nueva("recarga.soloLoNecesario=" + justo + "\n");
            s.avanzar(60);
            String id = s.registrarPedido("c-cerca", new Nodo(30, 14), 3, 36);
            Unidad u = null;
            for (int i = 0; i < 240 && (u == null || u.carga == 0); i++) {   // hasta 4 h: puede tomar antes su refrigerio
                s.avanzar(60);
                if (u == null) u = quienLleva(s, id);
            }
            assertTrue(u != null && u.carga > 0, "una unidad recargó para llevar el pedido");
            if (justo) assertEquals(3, u.carga, "carga solo los 3 paquetes que va a entregar");
            else assertEquals(u.tipo.capacidad(), u.carga, "se llena hasta su capacidad (decisión del equipo)");
        }
    }

    @Test
    void elEstadoTraeEnviosConProductosEntregasDeLaVentanaYElUltimoPlan() throws IOException {
        Simulacion s = nueva("");
        assertEquals("{\"plan\":null}", s.planJson(), "sin planificación todavía");
        s.avanzar(60);
        String id = s.registrarPedido("c-cerca", new Nodo(30, 14), 3, 36);
        s.avanzar(60);
        String plan = s.planJson();
        assertTrue(plan.contains(id + "-1 a " + id + "-3"), "el plan identifica los productos del pedido: " + plan);
        assertTrue(s.planCsv().contains("Unidad;Tipo de unidad;Orden;Parada"), "el CSV tiene cabecera");
        String foto = s.snapshotJson();
        assertTrue(foto.contains("\"envios\":[{\"u\":"), "cada pedido trae sus envíos");
        assertTrue(foto.contains("\"pctFlota\":") && foto.contains("\"nivelAlmacenes\":"), "indicadores globales");
        assertTrue(foto.contains("\"nivel\":\"VACIO\""), "las unidades sin carga están vacías");

        Pedido p = s.pedido(id);
        for (int i = 0; i < 48 && p != null && !p.completo(); i++) s.avanzar(600);
        assertTrue(p.completo(), "el pedido se entrega");
        foto = s.snapshotJson();
        assertTrue(Pattern.compile("\"id\":\"ALM-CENTRAL\".*?\"entregados\":\\{\"envios\":1,\"paquetes\":3").matcher(foto).find(),
                "el almacén central muestra la entrega de las últimas horas");
    }

    @Test
    void enElServidorElPlanificadorCorreEnSegundoPlanoYElEstadoNoSeBloquea() throws Exception {
        // 5 días en 30 s reales (K = 14 400, Sa = 0,25 s) con P = 0,3 s: el planificador corre casi todo el tiempo
        Config cfg = new Config(carpeta("escenario.semanal.minutosReales=0.5\n").resolve("config.properties"));
        Simulacion s = new Simulacion(cfg, Simulacion.Escenario.SEMANAL, INICIO, Planificadores.crear(cfg.planificador()));
        Thread hilo = new Thread(s, "simulacion-prueba");
        hilo.setDaemon(true);
        hilo.start();
        try {
            Thread.sleep(300);
            for (int i = 0; i < 12; i++) s.registrarPedido("c" + i, new Nodo(20 + i, 20), 3, 12);
            long peor = 0;
            for (int i = 0; i < 40; i++) {
                long ini = System.nanoTime();
                s.snapshotJson();
                peor = Math.max(peor, (System.nanoTime() - ini) / 1_000_000);
                Thread.sleep(50);
            }
            assertTrue(peor < 250, "el estado se publica sin esperar al planificador (peor: " + peor + " ms)");
            String foto = s.snapshotJson();
            assertTrue(Pattern.compile("\"ciclos\":([2-9]|\\d{2,})").matcher(foto).find(), "hubo varios ciclos de planificación: " + foto.substring(0, 400));
            assertFalse(foto.contains("\"entregados\":0,"), "ya se entregaron pedidos con los planes aplicados en segundo plano");
        } finally {
            s.cancelar();
            hilo.join(3000);
        }
    }

    @Test
    void elCriterioCUsaElSaRealDelColapso() throws Exception {
        // K = 200 000 → Sa = Sc / K = 3 600 / 200 000 = 0,018 s, mucho menor que lo que tarda el planificador
        Config cfg = new Config(carpeta("escenario.colapso.factor=200000\n").resolve("config.properties"));
        Simulacion s = new Simulacion(cfg, Simulacion.Escenario.COLAPSO, INICIO, Planificadores.crear(cfg.planificador()));
        Thread hilo = new Thread(s, "colapso-prueba");
        hilo.setDaemon(true);
        hilo.start();
        try {
            Thread.sleep(200);
            for (int i = 0; i < 20; i++) s.registrarPedido("c" + i, new Nodo(10 + 2 * i, 30), 4, 36);
            for (int i = 0; i < 100 && s.estado() != Simulacion.Estado.TERMINADA; i++) Thread.sleep(100);
            String foto = s.snapshotJson();
            assertEquals(Simulacion.Estado.TERMINADA, s.estado());
            assertTrue(foto.contains("\"criterio\":\"C\""), "colapsa por el criterio C (Ta >= Sa): " + foto.substring(0, 600));
            assertTrue(foto.contains("\"saSeg\":0.018"), "Sa = Sc / K");
        } finally {
            s.cancelar();
            hilo.join(3000);
        }
    }

    @Test
    void laPantallaDeConfiguracionGuardaEnElArchivoSinPerderComentariosYAplicaCadaCosaASuTiempo() throws Exception {
        Path c = carpeta("# comentario del equipo\nflota.moto.velocidadKmh=25\naveria.tipo1.horas=2\n");
        Path archivo = c.resolve("config.properties");
        Config cfg = new Config(archivo);
        try {
            Simulacion s = new Simulacion(cfg, Simulacion.Escenario.SEMANAL, INICIO, Planificadores.crear(cfg.planificador()));
            s.avanzar(60);
            int version = cfg.version();
            cfg.guardar(new java.util.LinkedHashMap<>(Map.of("flota.moto.velocidadKmh", "30", "averia.tipo1.horas", "3",
                    "ien.autos", "8")));
            String texto = Files.readString(archivo);
            assertTrue(texto.contains("# comentario del equipo"), "conserva los comentarios");
            assertTrue(texto.contains("flota.moto.velocidadKmh=30") && texto.contains("averia.tipo1.horas=3"), "reemplaza las líneas");
            assertTrue(texto.contains("ien.autos=8"), "agrega las claves que no estaban");
            assertFalse(texto.contains("velocidadKmh=25"), "no deja la línea anterior");
            assertEquals(version + 1, cfg.version());
            assertEquals(8, cfg.entero("ien.autos", 10), "el valor en memoria cambia");

            // averías: rigen para la siguiente que se registre
            s.recargarReglasAveria();
            pe.pucp.paqrap.app.modelo.Averia av = s.registrarAveria("TB01", 1);
            assertEquals(3 * 3600.0, av.disponible - av.inicio, 1, "la avería tipo 1 usa las 3 h nuevas");

            // velocidad: rige desde el siguiente ciclo de planificación (P&R 16)
            s.programarVelocidades(Map.of(TipoVehiculo.MOTO, 30.0));
            assertEquals(25.0, TipoVehiculo.MOTO.getVelocidadKmH(), 1e-9, "todavía no");
            s.registrarPedido("c-1", new Nodo(30, 20), 2, 36);    // pide un ciclo
            s.avanzar(60);
            assertEquals(30.0, TipoVehiculo.MOTO.getVelocidadKmH(), 1e-9, "desde el siguiente ciclo");

            // la cantidad de autos rige al iniciar la próxima simulación
            int autos = 0;
            for (String id : codigos()) if (id.startsWith("TA") && s.unidad(id) != null) autos++;
            assertEquals(10, autos, "la simulación en curso conserva su flota");
            Simulacion nueva = new Simulacion(cfg, Simulacion.Escenario.SEMANAL, INICIO, Planificadores.crear(cfg.planificador()));
            assertTrue(nueva.unidad("TA08") != null && nueva.unidad("TA09") == null, "la próxima simulación tiene 8 autos");
        } finally {
            TipoVehiculo.configurar(Configuracion.porDefecto());
        }
    }

    @Test
    void laConfiguracionSeValidaAntesDeGuardar() {
        var central = pe.pucp.paqrap.app.CatalogoConfiguracion.campo("ien.almacenCentral");
        assertEquals("27,14", pe.pucp.paqrap.app.CatalogoConfiguracion.normalizar(central, " 27 , 14 ", 70, 50));
        boolean fuera = false;
        try {
            pe.pucp.paqrap.app.CatalogoConfiguracion.normalizar(central, "71,14", 70, 50);
        } catch (IllegalArgumentException e) {
            fuera = true;
        }
        assertTrue(fuera, "un almacén fuera del mapa se rechaza");
        var vel = pe.pucp.paqrap.app.CatalogoConfiguracion.campo("flota.auto.velocidadKmh");
        assertEquals("42.5", pe.pucp.paqrap.app.CatalogoConfiguracion.normalizar(vel, "42,5", 70, 50));
        assertEquals("40", pe.pucp.paqrap.app.CatalogoConfiguracion.normalizar(vel, "40.0", 70, 50));
        Map<String, String> v = new java.util.HashMap<>();
        for (var c : pe.pucp.paqrap.app.CatalogoConfiguracion.CAMPOS) v.put(c.clave(), "1");
        v.put("ien.mapaAnchoKm", "70"); v.put("ien.mapaAltoKm", "50");
        v.put("ien.almacenCentral", "1,1"); v.put("ien.almacenNorOeste", "1,1"); v.put("ien.almacenEste", "2,2");
        for (String k : new String[]{"almacen", "carga", "flota", "plazo", "pedido"}) {
            v.put("semaforo." + k + ".verde", "0.5"); v.put("semaforo." + k + ".ambar", "0.2");
        }
        v.put("semaforo.carga.ambar", "0.7");
        v.put("ien.autos", "0"); v.put("ien.motos", "0"); v.put("ien.bicicletas", "0");
        Map<String, String> err = pe.pucp.paqrap.app.CatalogoConfiguracion.validarConjunto(v);
        assertTrue(err.containsKey("semaforo.carga.ambar"), "ámbar debe ser menor que verde");
        assertTrue(err.containsKey("ien.almacenNorOeste"), "dos almacenes en el mismo lugar");
        assertTrue(err.containsKey("ien.autos"), "flota vacía");
        assertEquals(3, err.size());
        // B01: si el mapa se achica, el almacén que queda fuera se rechaza
        v.put("ien.autos", "1"); v.put("ien.almacenNorOeste", "12,38"); v.put("semaforo.carga.ambar", "0.2");
        v.put("ien.mapaAnchoKm", "70"); v.put("ien.mapaAltoKm", "30");
        err = pe.pucp.paqrap.app.CatalogoConfiguracion.validarConjunto(v);
        assertEquals(Map.of("ien.almacenNorOeste", "Queda fuera del mapa de 70 x 30 km"), err);
    }

    /** B01: el tamaño del mapa se configura (ien.mapaAnchoKm, ien.mapaAltoKm) y lo usan la simulación y el visualizador. */
    @Test
    void elTamanoDelMapaSeConfiguraYSePublicaEnElEstado() throws IOException {
        Simulacion normal = nueva("");
        assertEquals(70, normal.anchoMapa());
        assertEquals(50, normal.altoMapa());
        assertTrue(normal.snapshotJson().contains("\"mapa\":{\"ancho\":70,\"alto\":50}"), "el estado lleva el mapa (70 x 50 por defecto)");

        Simulacion grande = nueva("ien.mapaAnchoKm=100\nien.mapaAltoKm=80\n");
        assertEquals(100, grande.anchoMapa());
        assertTrue(grande.snapshotJson().contains("\"mapa\":{\"ancho\":100,\"alto\":80}"));
        grande.registrarPedido("c1", new Nodo(95, 75), 2, 36);
        assertTrue(grande.snapshotJson().contains("\"x\":95,\"y\":75"), "un pedido fuera del 70 x 50 se acepta en un mapa más grande");

        boolean rechazado = false;
        try {
            nueva("ien.mapaAnchoKm=40\n");                  // el almacén Este (57, 27) queda fuera
        } catch (IllegalArgumentException e) {
            rechazado = e.getMessage().contains("fuera del mapa");
        }
        assertTrue(rechazado, "un almacén fuera del mapa impide iniciar con un mensaje claro");
    }

    /** B01: los bloqueos ya no dependen de una retícula fija de 70 x 50; los del profesor dan los mismos nodos. */
    @Test
    void losBloqueosSeExpandenSinDependerDeUnMapaFijo() {
        var b = pe.pucp.paqrap.app.datos.Formato.bloqueo(java.time.YearMonth.of(2026, 1), "01d02h22m-01d04h42m:25,45,45,45,45,40");
        assertEquals(26, b.nodos().size(), "21 nodos del primer tramo y 5 del segundo, sin repetir la esquina");
        assertEquals(new Nodo(25, 45), b.nodos().get(0));
        assertTrue(b.nodos().contains(new Nodo(45, 40)) && b.nodos().contains(new Nodo(45, 45)), "extremos incluidos");
        var fuera = pe.pucp.paqrap.app.datos.Formato.bloqueo(java.time.YearMonth.of(2026, 1), "01d00h00m-01d01h00m:68,10,80,10");
        assertEquals(13, fuera.nodos().size(), "fuera del 70 x 50 se lee; la simulación ignora lo que no cabe en su mapa");
        boolean rechazado = false;
        try {
            pe.pucp.paqrap.app.datos.Formato.bloqueo(java.time.YearMonth.of(2026, 1), "01d00h00m-01d01h00m:1,1,2,2");
        } catch (IllegalArgumentException e) {
            rechazado = true;
        }
        assertTrue(rechazado, "un tramo diagonal sigue siendo inválido");
    }
}
