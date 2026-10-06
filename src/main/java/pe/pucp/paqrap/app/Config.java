package pe.pucp.paqrap.app;

import pe.pucp.paqrap.planner.core.Configuracion;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Configuración única del sistema. Se arma en tres capas, cada una sobrescribe a la anterior:
 *   1. planner.properties   (parámetros del planificador y del caso: almacenes, flota, Sc, P, ALNS, GA)
 *   2. config.properties    empaquetado (servidor, escenarios, visualizador, semáforo)
 *   3. config.properties    junto al .jar (opcional, para cambiar cualquier valor sin recompilar)
 * El planificador recibe la misma configuración, así que un parámetro tiene un solo valor en todo el sistema.
 */
public final class Config {

    private final Configuracion cfg;

    public Config(Path archivo) throws IOException {
        Configuracion c = Configuracion.porDefecto();
        cargar(c, Config.class.getResourceAsStream("/config.properties"));
        if (archivo != null && Files.exists(archivo)) cargar(c, Files.newInputStream(archivo));
        this.cfg = c;
    }

    private static void cargar(Configuracion c, InputStream in) throws IOException {
        if (in == null) return;
        try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            Properties p = new Properties();
            p.load(r);
            for (String k : p.stringPropertyNames()) c.con(k, p.getProperty(k).trim());
        }
    }

    /** La misma configuración, en el tipo que espera rutapaq-planner. */
    public Configuracion planificador() {
        return cfg;
    }

    public String texto(String clave, String defecto) {
        return cfg.texto(clave, defecto).trim();
    }

    public int entero(String clave, int defecto) {
        return cfg.entero(clave, defecto);
    }

    public double decimal(String clave, double defecto) {
        return cfg.numero(clave, defecto);
    }
}
