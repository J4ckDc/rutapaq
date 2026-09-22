package pe.pucp.paqrap.planner.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Parametros del planificador cargados desde alns.properties. Ningun valor
 * numerico del caso (capacidades, costos, plazos, presupuestos de computo,
 * umbrales del semaforo) esta codificado en el algoritmo: los tres escenarios se
 * resuelven por parametrizacion, sin modificar el codigo fuente.
 */
public final class Configuracion {

    private final Properties props = new Properties();

    private Configuracion() {
    }

    public static Configuracion porDefecto() {
        Configuracion c = new Configuracion();
        try (InputStream in = Configuracion.class.getResourceAsStream("/alns.properties")) {
            if (in != null) {
                c.props.load(in);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return c;
    }

    public static Configuracion desdeArchivo(Path archivo) {
        Configuracion c = porDefecto();
        try (InputStream in = Files.newInputStream(archivo)) {
            c.props.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return c;
    }

    public Configuracion copia() {
        Configuracion c = new Configuracion();
        c.props.putAll(this.props);
        return c;
    }

    /** Sobrescribe un parametro (usado por el barrido factorial del IEN). */
    public Configuracion con(String clave, Object valor) {
        props.setProperty(clave, String.valueOf(valor));
        return this;
    }

    public Map<String, String> comoMapa() {
        Map<String, String> mapa = new LinkedHashMap<>();
        for (String k : props.stringPropertyNames()) {
            mapa.put(k, props.getProperty(k));
        }
        return mapa;
    }

    public String texto(String clave, String pordefecto) {
        return props.getProperty(clave, pordefecto);
    }

    public double numero(String clave, double pordefecto) {
        return Double.parseDouble(props.getProperty(clave, String.valueOf(pordefecto)));
    }

    public int entero(String clave, int pordefecto) {
        return Integer.parseInt(props.getProperty(clave, String.valueOf(pordefecto)).trim());
    }

    public long largo(String clave, long pordefecto) {
        return Long.parseLong(props.getProperty(clave, String.valueOf(pordefecto)).trim());
    }

    // --- Escenario -------------------------------------------------------
    public TipoEscenario tipoEscenario() {
        return TipoEscenario.valueOf(texto("escenario.tipo", "SIMULACION_5D"));
    }

    public long semilla() {
        return largo("escenario.semilla", 20260919L);
    }

    public int maxIteraciones() {
        return entero("escenario.maxIteraciones", 25000);
    }

    public double limiteTiempoSegundos() {
        return numero("escenario.limiteTiempoSegundos", 10.0);
    }

    public int horizonteCongelamientoMinutos() {
        return entero("escenario.horizonteCongelamientoMinutos", 30);
    }

    public boolean retornoAlmacen() {
        return Boolean.parseBoolean(texto("escenario.retornoAlmacen", "false"));
    }

    // --- Recocido simulado y ruleta adaptativa ---------------------------
    public double factorT0() {
        return numero("alns.factorT0", 0.05);
    }

    public double alfa() {
        return numero("alns.alfa", 0.95);
    }

    public double temperaturaMinima() {
        return numero("alns.temperaturaMinima", 1.0E-3);
    }

    public int tamanoSegmento() {
        return entero("alns.tamanoSegmento", 100);
    }

    public double lambda() {
        return numero("alns.lambda", 0.80);
    }

    public double sigma1() {
        return numero("alns.sigma1", 33.0);
    }

    public double sigma2() {
        return numero("alns.sigma2", 20.0);
    }

    public double sigma3() {
        return numero("alns.sigma3", 12.0);
    }

    // --- Vecindario y operadores ----------------------------------------
    public double qMin() {
        return numero("alns.qMin", 0.15);
    }

    public double qMax() {
        return numero("alns.qMax", 0.35);
    }

    public int kRegret() {
        return entero("alns.kRegret", 3);
    }

    public double pDeterminismo() {
        return numero("alns.pDeterminismo", 5.0);
    }

    public double penalizacionSinAlternativa() {
        return numero("alns.penalizacionSinAlternativa", 1.0E6);
    }

    public double omegaUrgencia() {
        return numero("alns.omegaUrgencia", 10000.0);
    }

    public double phi1() {
        return numero("alns.shaw.phi1", 0.5);
    }

    public double phi2() {
        return numero("alns.shaw.phi2", 0.3);
    }

    public double phi3() {
        return numero("alns.shaw.phi3", 0.2);
    }

    public double horizonteShawHoras() {
        return numero("alns.shaw.horizonteHoras", 36.0);
    }

    // --- Semaforo operativo (requisito no funcional d) -------------------
    public double umbralSemaforoVerde() {
        return numero("semaforo.verde", 0.95);
    }

    public double umbralSemaforoAmbar() {
        return numero("semaforo.ambar", 0.85);
    }
}
