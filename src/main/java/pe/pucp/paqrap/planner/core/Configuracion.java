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
 * Parametros del planificador y del experimento, cargados de planner.properties.
 * Ningun valor del caso esta codificado en los algoritmos: mapa, flota, plazos,
 * presupuesto de computo y parametros de calibracion se fijan por configuracion.
 */
public final class Configuracion {

    private final Properties props = new Properties();

    private Configuracion() {
    }

    public static Configuracion porDefecto() {
        Configuracion c = new Configuracion();
        try (InputStream in = Configuracion.class.getResourceAsStream("/planner.properties")) {
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
        c.props.putAll(props);
        return c;
    }

    public Configuracion con(String clave, Object valor) {
        props.setProperty(clave, String.valueOf(valor));
        return this;
    }

    public Map<String, String> comoMapa() {
        Map<String, String> m = new LinkedHashMap<>();
        for (String k : props.stringPropertyNames()) {
            m.put(k, props.getProperty(k));
        }
        return m;
    }

    public String texto(String clave, String pordefecto) {
        return props.getProperty(clave, pordefecto);
    }

    public double numero(String clave, double pordefecto) {
        return Double.parseDouble(props.getProperty(clave, String.valueOf(pordefecto)).trim());
    }

    public int entero(String clave, int pordefecto) {
        return Integer.parseInt(props.getProperty(clave, String.valueOf(pordefecto)).trim());
    }

    public long largo(String clave, long pordefecto) {
        return Long.parseLong(props.getProperty(clave, String.valueOf(pordefecto)).trim());
    }

    public boolean bandera(String clave, boolean pordefecto) {
        return Boolean.parseBoolean(props.getProperty(clave, String.valueOf(pordefecto)).trim());
    }

    // --- Presupuesto de computo (P) y semilla -----------------------------
    public double presupuestoSegundos() {
        return numero("planificador.presupuestoSegundos", 2.0);
    }

    public long semilla() {
        return largo("planificador.semilla", 20260900L);
    }

    /** Penalizacion por producto no planificado en la ejecucion (domina el costo por km). */
    public double omegaNoPlanificado() {
        return numero("planificador.omegaNoPlanificado", 10000.0);
    }

    /** Numero de pares (unidad, posicion) que se evaluan de forma exacta por insercion. */
    public int candidatosInsercion() {
        return entero("planificador.candidatosInsercion", 8);
    }

    /** Horizonte de planificacion: no se planifican entregas mas alla del turno vigente. */
    public int vecinosGranulares() {
        return entero("planificador.vecinosGranulares", 20);
    }

    public double umbralSemaforoVerde() {
        return numero("semaforo.verde", 0.95);
    }

    public double umbralSemaforoAmbar() {
        return numero("semaforo.ambar", 0.85);
    }
}
