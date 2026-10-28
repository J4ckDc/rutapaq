package pe.pucp.paqrap.app.modelo;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Avería de una unidad de transporte (v03), según las respuestas del curso (P&R, pregunta 3):
 *
 *   Tipo 1 (menor):      la unidad no está disponible por 2 h; se queda en el lugar con su carga y luego sigue.
 *   Tipo 2 (intermedia): permanece en el lugar 4 h y luego pasa de forma instantánea al almacén central con los
 *                        paquetes que no se trasvasaron. Vuelve a estar disponible al final del turno siguiente
 *                        al de la avería.
 *   Tipo 3 (mayor):      igual que el tipo 2 en el lugar; vuelve tras al menos 2 días, en el turno de 15:00 a 23:00.
 *
 * Los tiempos son segundos simulados desde el inicio de la simulación, como en Pedido. Las reglas que calculan
 * finEnLugar y disponible están en app.sim.ReglasAveria.
 */
public final class Averia {

    /** EN_LUGAR: detenida donde se averió. EN_CENTRAL: tipos 2 y 3 ya llevados al almacén central. FINALIZADA: vuelve a operar. */
    public enum Fase { EN_LUGAR, EN_CENTRAL, FINALIZADA }

    public static final String VISUALIZADOR = "VISUALIZADOR";   // v0.4.1: las averías solo se registran de forma manual

    public final String id;
    public final String unidad;
    public final TipoUnidad tipoUnidad;
    public final int tipo;
    public final String origen;          // VISUALIZADOR
    public final double inicio;          // momento de ocurrencia
    public final double x;               // posición exacta en la que quedó detenida
    public final double y;
    public final Nodo nodo;              // esquina más cercana: allí se hace el trasvase
    public final double finEnLugar;      // tipo 1: fin de las 2 h; tipos 2 y 3: traslado al almacén central
    public final double disponible;      // vuelve a estar disponible para el planificador
    public final int carga;              // paquetes a bordo al averiarse
    public final List<String> pedidos;   // pedidos que tenía asignados y que pasaron a replanificarse

    public Fase fase = Fase.EN_LUGAR;
    public int trasvasados;              // paquetes que recogieron otras unidades (trasvase)
    public int devueltos;                // paquetes no trasvasados que se llevaron al almacén central
    public double fin = -1;              // instante en que terminó (vuelve a operar)
    public final Set<String> receptoras = new LinkedHashSet<>();   // unidades que hicieron un trasvase

    public Averia(String id, String unidad, TipoUnidad tipoUnidad, int tipo, String origen, double inicio,
                  double x, double y, Nodo nodo, double finEnLugar, double disponible, int carga, List<String> pedidos) {
        this.id = id;
        this.unidad = unidad;
        this.tipoUnidad = tipoUnidad;
        this.tipo = tipo;
        this.origen = origen;
        this.inicio = inicio;
        this.x = x;
        this.y = y;
        this.nodo = nodo;
        this.finEnLugar = finEnLugar;
        this.disponible = disponible;
        this.carga = carga;
        this.pedidos = List.copyOf(pedidos);
    }

    public boolean activa() {
        return fase != Fase.FINALIZADA;
    }

    /** Tipos 2 y 3: la unidad se lleva al almacén central y sus paquetes se pueden trasvasar mientras sigue en el lugar. */
    public boolean seTraslada() {
        return tipo >= 2;
    }

    /** Nombre del tipo según el curso: menor, intermedia o mayor. */
    public static String nombreTipo(int tipo) {
        return switch (tipo) {
            case 1 -> "menor";
            case 2 -> "intermedia";
            case 3 -> "mayor";
            default -> "desconocida";
        };
    }
}
