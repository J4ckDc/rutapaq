package pe.pucp.paqrap.app;

import pe.pucp.paqrap.planner.core.Configuracion;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Configuración única del sistema. Se arma en tres capas, cada una sobrescribe a la anterior:
 *   1. planner.properties   (valores de fábrica del caso y parámetros de los algoritmos: Sc, P, ALNS, GA)
 *   2. config.properties    empaquetado (valores de fábrica de todo lo editable: mapa, almacenes, flota, averías,
 *                           mantenimiento, semáforo, escenarios y servidor)
 *   3. config.properties    junto al .jar: el que se edita (a mano o desde la pantalla Configuración)
 * El planificador recibe la misma configuración, así que un parámetro tiene un solo valor en todo el sistema.
 *
 * v0.5: la pantalla Configuración del visualizador cambia los valores en memoria y los escribe en el
 * config.properties junto al .jar (guardar), conservando sus comentarios. Los valores de fábrica (capas 1 y 2)
 * se usan para "Restaurar valores por defecto".
 */
public final class Config {

    private final Configuracion cfg;
    private final Path archivo;
    private final Map<String, String> fabrica = new LinkedHashMap<>();
    private volatile int version;

    public Config(Path archivo) throws IOException {
        Configuracion c = Configuracion.porDefecto();
        cargar(c, Config.class.getResourceAsStream("/config.properties"));
        fabrica.putAll(c.comoMapa());
        if (archivo != null && Files.exists(archivo)) cargar(c, Files.newInputStream(archivo));
        this.cfg = c;
        this.archivo = archivo;
        // v0.5: capacidad, velocidad y costo de cada tipo de unidad (claves flota.*), una sola fuente para todo el sistema
        pe.pucp.paqrap.planner.model.TipoVehiculo.configurar(c);
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

    /** v0.5: cuántas veces se guardó la configuración desde la pantalla (los navegadores la vuelven a leer). */
    public int version() {
        return version;
    }

    /** v0.5: valor de fábrica de una clave (planner.properties y config.properties empaquetados). */
    public String deFabrica(String clave) {
        String v = fabrica.get(clave);
        return v == null ? null : v.trim();
    }

    /** v0.5: archivo donde se guardan los cambios (config.properties junto al .jar). */
    public Path archivo() {
        return archivo;
    }

    /**
     * v0.5: aplica los cambios en memoria y los escribe en config.properties. En el archivo se reemplaza la línea de
     * cada clave (conservando comentarios y orden); las claves que no estaban se agregan al final. Si el archivo no
     * existe, se crea a partir del config.properties empaquetado. Se escribe en un temporal y luego se reemplaza.
     */
    public synchronized void guardar(Map<String, String> cambios) throws IOException {
        if (cambios.isEmpty()) return;
        if (archivo != null) {
            List<String> lineas = new ArrayList<>();
            if (Files.exists(archivo)) {
                lineas.addAll(Files.readAllLines(archivo, StandardCharsets.UTF_8));
            } else {
                try (InputStream in = Config.class.getResourceAsStream("/config.properties")) {
                    if (in != null) lineas.addAll(List.of(new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\r?\n", -1)));
                }
            }
            Set<String> pendientes = new LinkedHashSet<>(cambios.keySet());
            for (int i = 0; i < lineas.size(); i++) {
                String l = lineas.get(i);
                for (String k : pendientes) {
                    if (Pattern.compile("^\\s*" + Pattern.quote(k) + "\\s*[=:]").matcher(l).find()) {
                        lineas.set(i, k + "=" + cambios.get(k));
                        pendientes.remove(k);
                        break;
                    }
                }
            }
            if (!pendientes.isEmpty()) {
                lineas.add("");
                lineas.add("# Guardado desde la pantalla Configuración");
                for (String k : pendientes) lineas.add(k + "=" + cambios.get(k));
            }
            Path dir = archivo.toAbsolutePath().getParent();
            Path tmp = Files.createTempFile(dir, "config", ".tmp");
            Files.write(tmp, lineas, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, archivo, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, archivo, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        for (Map.Entry<String, String> e : cambios.entrySet()) cfg.con(e.getKey(), e.getValue());
        version++;
    }
}
