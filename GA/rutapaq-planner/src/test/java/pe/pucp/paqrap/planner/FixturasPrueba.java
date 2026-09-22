package pe.pucp.paqrap.planner;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import pe.pucp.paqrap.planner.core.Configuracion;
import pe.pucp.paqrap.planner.core.ContextoPlanificacion;
import pe.pucp.paqrap.planner.core.RedVial;
import pe.pucp.paqrap.planner.model.Almacen;
import pe.pucp.paqrap.planner.model.NodoRed;
import pe.pucp.paqrap.planner.model.Pedido;
import pe.pucp.paqrap.planner.model.TipoAlmacen;
import pe.pucp.paqrap.planner.model.TipoNodo;
import pe.pucp.paqrap.planner.model.TipoVehiculo;
import pe.pucp.paqrap.planner.model.TurnoConductor;
import pe.pucp.paqrap.planner.model.Vehiculo;

/** Instancias minimas y deterministas compartidas por las pruebas unitarias. */
public final class FixturasPrueba {

    public static final LocalDateTime INICIO_TURNO =
            LocalDate.of(2026, 9, 21).atTime(LocalTime.of(7, 0));

    /** Grado de longitud equivalente a 1 km a la latitud de Lima. */
    private static final double GRADO_KM = 0.009184;

    private FixturasPrueba() {
    }

    /** Cadena A - B - C - D con calles de doble sentido de 1 km cada una. */
    public static RedVial redLineal() {
        RedVial red = new RedVial();
        red.agregarNodo(new NodoRed("A", TipoNodo.CENTRAL, -12.0, -77.0, 0.0));
        red.agregarNodo(new NodoRed("B", TipoNodo.CLIENTE, -12.0, -77.0 + GRADO_KM, 1.0));
        red.agregarNodo(new NodoRed("C", TipoNodo.CLIENTE, -12.0, -77.0 + 2 * GRADO_KM, 1.0));
        red.agregarNodo(new NodoRed("D", TipoNodo.CLIENTE, -12.0, -77.0 + 3 * GRADO_KM, 1.0));
        red.agregarCalle("A", "B");
        red.agregarCalle("B", "C");
        red.agregarCalle("C", "D");
        return red;
    }

    public static Almacen almacenCentral(RedVial red) {
        return new Almacen("ALM-CENTRAL", TipoAlmacen.CENTRAL, red.nodo("A"), 0, 0, INICIO_TURNO);
    }

    public static Almacen almacenIntermedio(RedVial red, int saldo) {
        return new Almacen("ALM-INT-1", TipoAlmacen.INTERMEDIO, red.nodo("A"), 1000, saldo, INICIO_TURNO);
    }

    public static Vehiculo unidad(String id, TipoVehiculo tipo, Almacen base) {
        Vehiculo v = new Vehiculo(id, tipo, base);
        v.setTurnoActual(new TurnoConductor("T-" + id, "C-" + id, id, INICIO_TURNO));
        return v;
    }

    public static Pedido pedido(String id, NodoRed destino, int paquetes, int ventanaHoras) {
        return new Pedido(id, "CLI-" + id, destino, paquetes, INICIO_TURNO, ventanaHoras, null);
    }

    public static ContextoPlanificacion contexto(RedVial red, List<Almacen> almacenes,
                                                 List<Vehiculo> flota, Configuracion cfg) {
        return new ContextoPlanificacion(red, almacenes, flota, INICIO_TURNO, cfg);
    }
}
