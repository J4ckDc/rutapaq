package pe.pucp.paqrap.app;

import pe.pucp.paqrap.app.web.Json;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v0.5: parámetros que se editan desde la pantalla Configuración (rúbrica B01, B02, B04, B06 a B09). Todos viven en
 * config.properties: la pantalla los lee y los guarda allí. Cada campo indica cuándo se aplica:
 *   AHORA  - en la simulación en curso, al guardar (semáforo y reglas de las averías siguientes);
 *   CICLO  - en el siguiente ciclo de planificación (velocidad por tipo, P&amp;R preguntas 15 y 16);
 *   INICIO - al iniciar la próxima simulación (mapa, almacenes, cantidad, capacidad y costo de la flota, mantenimiento;
 *            P&amp;R pregunta 9: la flota solo cambia al inicio de una ejecución). S13v1: en la operación día a día,
 *            al reabrir las operaciones.
 */
public final class CatalogoConfiguracion {

    public enum Tipo { ENTERO, DECIMAL, PUNTO, HORA, BOOLEANO }

    public enum Aplica {
        AHORA("Se aplica ahora"), CICLO("En el siguiente ciclo de planificación"), INICIO("Al iniciar la próxima simulación o al reabrir las operaciones");
        public final String texto;

        Aplica(String texto) {
            this.texto = texto;
        }
    }

    public record Campo(String clave, String seccion, String grupo, String etiqueta, Tipo tipo, double min, double max,
                        double paso, String unidad, Aplica aplica, String ayuda) {
    }

    public record Seccion(String id, String titulo, String item, String nota) {
    }

    public static final List<Seccion> SECCIONES = List.of(
            new Seccion("mapa", "Mapa de la ciudad", "B01", "Retícula con calles cada 1 km, de doble sentido y sin diagonales; el origen (0, 0) está abajo a la izquierda (P&R 4: 70 x 50 km). Los almacenes deben quedar dentro; los pedidos y bloqueos que queden fuera se ignoran."),
            new Seccion("almacenes", "Almacenes", "B02", "Ubicación en el mapa (x, y en km) y capacidad de los almacenes intermedios. El central tiene abastecimiento permanente."),
            new Seccion("flota", "Flota por tipo de unidad", "B04", "Todas las unidades salen del almacén central; los códigos (TA01, TM01, TB01…) se generan según la cantidad."),
            new Seccion("averia1", "Avería tipo 1 · menor", "B07", "La unidad queda detenida en su posición con su carga y luego sigue operando."),
            new Seccion("averia2", "Avería tipo 2 · intermedia", "B08", "Permanece en el lugar y luego pasa de forma instantánea al almacén central. Vuelve a estar disponible al final del turno siguiente al de la avería."),
            new Seccion("averia3", "Avería tipo 3 · mayor", "B09", "Permanece en el lugar las mismas horas que el tipo 2 y luego pasa al almacén central."),
            new Seccion("trasvase", "Trasvase (averías 2 y 3)", "B08 · B09", "Mientras la unidad averiada sigue en el lugar, otra puede recoger sus paquetes."),
            new Seccion("mantenimiento", "Mantenimiento preventivo", "P&R 19", "Duración por tipo, desde las 00:00 del día indicado en el plan (archivo mant.preventivo). P&R 19 (03/11/2026): 48 h para todos los tipos."),
            new Seccion("diario", "Operación día a día", "Tiempo real", "Planificación programada cada N minutos, alineada al reloj de Lima (K = 1, así que Sa = Sc). Además, registrar un pedido o una avería replanifica de inmediato."),
            new Seccion("semaforo", "Semáforo", "B06", "El rojo siempre es peligro; vacío (gris) cuando no hay nada. Verde desde el primer valor, ámbar desde el segundo, rojo por debajo."));

    public static final List<Campo> CAMPOS;

    static {
        List<Campo> c = new ArrayList<>();
        // Mapa (B01)
        c.add(new Campo("ien.mapaAnchoKm", "mapa", null, "Largo (eje X)", Tipo.ENTERO, 10, 500, 1, "km", Aplica.INICIO, null));
        c.add(new Campo("ien.mapaAltoKm", "mapa", null, "Ancho (eje Y)", Tipo.ENTERO, 10, 500, 1, "km", Aplica.INICIO, null));
        // Almacenes (B02)
        c.add(new Campo("ien.almacenCentral", "almacenes", null, "Almacén central", Tipo.PUNTO, 0, 0, 1, "km", Aplica.INICIO, null));
        c.add(new Campo("ien.almacenNorOeste", "almacenes", null, "Almacén Nor-Oeste", Tipo.PUNTO, 0, 0, 1, "km", Aplica.INICIO, null));
        c.add(new Campo("ien.almacenEste", "almacenes", null, "Almacén Este", Tipo.PUNTO, 0, 0, 1, "km", Aplica.INICIO, null));
        c.add(new Campo("ien.capacidadIntermedio", "almacenes", null, "Capacidad de cada intermedio", Tipo.ENTERO, 1, 100000, 1, "paquetes", Aplica.INICIO, "Se recargan hasta esta cantidad a las 23:59:59"));
        // Flota por tipo (B04)
        for (String[] t : new String[][]{{"auto", "autos", "Auto"}, {"moto", "motos", "Moto"}, {"bici", "bicicletas", "Bicicleta"}}) {
            c.add(new Campo("ien." + t[1], "flota", t[2], "Cantidad", Tipo.ENTERO, 0, 99, 1, "unidades", Aplica.INICIO, null));
            c.add(new Campo("flota." + t[0] + ".capacidad", "flota", t[2], "Capacidad", Tipo.ENTERO, 1, 500, 1, "paquetes", Aplica.INICIO, null));
            c.add(new Campo("flota." + t[0] + ".velocidadKmh", "flota", t[2], "Velocidad", Tipo.DECIMAL, 1, 200, 0.5, "km/h", Aplica.CICLO, null));
            c.add(new Campo("flota." + t[0] + ".costoKm", "flota", t[2], "Costo", Tipo.DECIMAL, 0, 1000, 0.5, "S/ por km", Aplica.INICIO, null));
        }
        // Averías (B07 a B09)
        c.add(new Campo("averia.tipo1.horas", "averia1", null, "No disponible durante", Tipo.DECIMAL, 0.1, 72, 0.5, "horas", Aplica.AHORA, "Se aplica a las averías que se registren desde ahora"));
        c.add(new Campo("averia.enLugar.horas", "averia2", null, "Horas en el lugar (también tipo 3)", Tipo.DECIMAL, 0, 48, 0.5, "horas", Aplica.AHORA, "Luego pasa al almacén central con los paquetes no trasvasados"));
        c.add(new Campo("averia.tipo3.diasMinimos", "averia3", null, "No disponible al menos", Tipo.DECIMAL, 0, 30, 0.5, "días", Aplica.AHORA, null));
        c.add(new Campo("averia.tipo3.horaRetorno", "averia3", null, "Vuelve al inicio del turno de", Tipo.HORA, 0, 0, 1, "hh:mm", Aplica.AHORA, "Turnos: 07:00, 15:00 y 23:00"));
        c.add(new Campo("averia.trasvase.habilitado", "trasvase", null, "Permitir el trasvase", Tipo.BOOLEANO, 0, 0, 1, null, Aplica.AHORA, null));
        c.add(new Campo("averia.trasvase.minutos", "trasvase", null, "Duración del trasvase", Tipo.DECIMAL, 0, 240, 5, "minutos", Aplica.AHORA, null));
        // Mantenimiento por tipo
        c.add(new Campo("mantenimiento.horas.auto", "mantenimiento", null, "Auto", Tipo.DECIMAL, 1, 240, 1, "horas", Aplica.INICIO, null));
        c.add(new Campo("mantenimiento.horas.moto", "mantenimiento", null, "Moto", Tipo.DECIMAL, 1, 240, 1, "horas", Aplica.INICIO, null));
        c.add(new Campo("mantenimiento.horas.bici", "mantenimiento", null, "Bicicleta", Tipo.DECIMAL, 1, 240, 1, "horas", Aplica.INICIO, null));
        // S13v1: operación día a día
        c.add(new Campo("escenario.diario.saMinutos", "diario", null, "Planificar cada", Tipo.DECIMAL, 1, 60, 1, "minutos", Aplica.INICIO, "Sa = Sc, porque en la operación día a día K = 1"));
        // Semáforo (B06)
        for (String[] s : new String[][]{{"almacen", "Stock de un almacén intermedio", "%"}, {"carga", "Carga de una unidad", "%"},
                {"flota", "Ocupación de la flota", "%"}, {"plazo", "Pedidos entregados a tiempo", "%"}}) {
            c.add(new Campo("semaforo." + s[0] + ".verde", "semaforo", s[1], "Verde desde", Tipo.DECIMAL, 0, 100, 1, s[2], Aplica.AHORA, null));
            c.add(new Campo("semaforo." + s[0] + ".ambar", "semaforo", s[1], "Ámbar desde", Tipo.DECIMAL, 0, 100, 1, s[2], Aplica.AHORA, null));
        }
        c.add(new Campo("semaforo.pedido.verde", "semaforo", "Plazo que le queda a un pedido", "Verde desde", Tipo.DECIMAL, 0, 1, 0.05, "fracción", Aplica.AHORA, null));
        c.add(new Campo("semaforo.pedido.ambar", "semaforo", "Plazo que le queda a un pedido", "Ámbar desde", Tipo.DECIMAL, 0, 1, 0.05, "fracción", Aplica.AHORA, null));
        CAMPOS = List.copyOf(c);
    }

    private CatalogoConfiguracion() {
    }

    public static Campo campo(String clave) {
        for (Campo c : CAMPOS) if (c.clave().equals(clave)) return c;
        return null;
    }

    /** Valor normalizado de un campo, o lanza IllegalArgumentException con un mensaje para el usuario. */
    public static String normalizar(Campo c, String valor, int anchoMapa, int altoMapa) {
        String v = valor == null ? "" : valor.trim();
        try {
            switch (c.tipo()) {
                case ENTERO -> {
                    long n = Long.parseLong(v);
                    if (n < c.min() || n > c.max()) throw new IllegalArgumentException(rango(c));
                    return String.valueOf(n);
                }
                case DECIMAL -> {
                    double d = Double.parseDouble(v.replace(',', '.'));
                    if (Double.isNaN(d) || d < c.min() || d > c.max()) throw new IllegalArgumentException(rango(c));
                    return java.math.BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();   // 42.5, no 42.500
                }
                case PUNTO -> {
                    String[] p = v.split(",");
                    if (p.length != 2) throw new IllegalArgumentException("Indique x, y");
                    int x = Integer.parseInt(p[0].trim()), y = Integer.parseInt(p[1].trim());
                    if (x < 0 || y < 0 || x > anchoMapa || y > altoMapa) {
                        throw new IllegalArgumentException("Debe estar dentro del mapa: x de 0 a " + anchoMapa + ", y de 0 a " + altoMapa);
                    }
                    return x + "," + y;
                }
                case HORA -> {
                    LocalTime t = LocalTime.parse(v.length() == 4 ? "0" + v : v);
                    return t.toString();
                }
                case BOOLEANO -> {
                    if (!v.equalsIgnoreCase("true") && !v.equalsIgnoreCase("false")) throw new IllegalArgumentException("Debe ser sí o no");
                    return v.toLowerCase();
                }
            }
        } catch (NumberFormatException | java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException(c.tipo() == Tipo.HORA ? "Hora inválida (hh:mm)" : "Número inválido");
        }
        throw new IllegalArgumentException("Tipo desconocido");
    }

    private static String rango(Campo c) {
        return "Debe estar entre " + Json.num(c.min()) + " y " + Json.num(c.max());
    }

    /** Validaciones entre campos sobre los valores que quedarían; devuelve clave → mensaje (vacío si todo está bien). */
    public static Map<String, String> validarConjunto(Map<String, String> valores) {
        Map<String, String> err = new LinkedHashMap<>();
        for (String s : new String[]{"almacen", "carga", "flota", "plazo", "pedido"}) {
            double v = Double.parseDouble(valores.get("semaforo." + s + ".verde"));
            double a = Double.parseDouble(valores.get("semaforo." + s + ".ambar"));
            if (a >= v) err.put("semaforo." + s + ".ambar", "Debe ser menor que el valor de verde (" + Json.num(v) + ")");
        }
        String[] alm = {"ien.almacenCentral", "ien.almacenNorOeste", "ien.almacenEste"};
        // v0.5 (B01): con el tamaño del mapa que quedaría, cada almacén debe estar dentro
        int ancho = Integer.parseInt(valores.get("ien.mapaAnchoKm")), alto = Integer.parseInt(valores.get("ien.mapaAltoKm"));
        for (String a : alm) {
            String[] p = valores.get(a).split(",");
            if (Integer.parseInt(p[0]) > ancho || Integer.parseInt(p[1]) > alto) {
                err.put(a, "Queda fuera del mapa de " + ancho + " x " + alto + " km");
            }
        }
        for (int i = 0; i < alm.length; i++) {
            for (int j = 0; j < i; j++) {
                if (valores.get(alm[i]).equals(valores.get(alm[j]))) err.put(alm[i], "Coincide con la ubicación de otro almacén");
            }
        }
        long total = Long.parseLong(valores.get("ien.autos")) + Long.parseLong(valores.get("ien.motos"))
                + Long.parseLong(valores.get("ien.bicicletas"));
        if (total == 0) err.put("ien.autos", "La flota debe tener al menos una unidad");
        return err;
    }

    /** Catálogo con los valores vigentes y los de fábrica, en JSON (GET /api/configuracion). */
    public static String json(Map<String, String> vigentes, Map<String, String> defecto, int version, String estadoSimulacion) {
        StringBuilder b = new StringBuilder(8192);
        b.append("{\"version\":").append(version).append(",\"simulacion\":").append(Json.str(estadoSimulacion)).append(",\"secciones\":[");
        for (int i = 0; i < SECCIONES.size(); i++) {
            Seccion s = SECCIONES.get(i);
            if (i > 0) b.append(',');
            b.append("{\"id\":").append(Json.str(s.id())).append(",\"titulo\":").append(Json.str(s.titulo()))
                    .append(",\"item\":").append(Json.str(s.item())).append(",\"nota\":").append(Json.str(s.nota()))
                    .append(",\"campos\":[");
            boolean primero = true;
            for (Campo c : CAMPOS) {
                if (!c.seccion().equals(s.id())) continue;
                if (!primero) b.append(',');
                primero = false;
                b.append("{\"clave\":").append(Json.str(c.clave()))
                        .append(",\"grupo\":").append(c.grupo() == null ? "null" : Json.str(c.grupo()))
                        .append(",\"etiqueta\":").append(Json.str(c.etiqueta()))
                        .append(",\"tipo\":").append(Json.str(c.tipo().name()))
                        .append(",\"min\":").append(Json.num(c.min())).append(",\"max\":").append(Json.num(c.max()))
                        .append(",\"paso\":").append(Json.num(c.paso()))
                        .append(",\"unidad\":").append(c.unidad() == null ? "null" : Json.str(c.unidad()))
                        .append(",\"aplica\":").append(Json.str(c.aplica().name()))
                        .append(",\"aplicaTexto\":").append(Json.str(c.aplica().texto))
                        .append(",\"ayuda\":").append(c.ayuda() == null ? "null" : Json.str(c.ayuda()))
                        .append(",\"valor\":").append(Json.str(vigentes.get(c.clave())))
                        .append(",\"defecto\":").append(Json.str(defecto.get(c.clave()))).append('}');
            }
            b.append("]}");
        }
        return b.append("]}").toString();
    }
}
