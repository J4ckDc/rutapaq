package pe.pucp.paqrap.planner.ien;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import pe.pucp.paqrap.planner.core.CalendarioBloqueos;
import pe.pucp.paqrap.planner.core.MapaReticula;
import pe.pucp.paqrap.planner.model.Pedido;

/**
 * Lector de bloques: carga los pedidos del historial registrados en
 * [t0, t0 + H) y los bloqueos planificados vigentes en esa ventana. Es comun a
 * ambos algoritmos, de modo que las dos corridas de un bloque parten
 * exactamente de los mismos datos.
 */
public final class CargadorBloque {

    private CargadorBloque() {
    }

    public static Bloque cargar(Path directorio, DefinicionBloque def, int horizonteHoras,
                                MapaReticula mapa, int maximoPorEntrega) {
        LocalDateTime t0 = def.inicio().atStartOfDay();
        LocalDateTime fin = t0.plusHours(horizonteHoras);

        List<Pedido> pedidos = new ArrayList<>();
        CalendarioBloqueos calendario = new CalendarioBloqueos();
        LocalDateTime cursor = t0;
        int anioPrevio = -1;
        int mesPrevio = -1;
        while (!cursor.isAfter(fin)) {
            int anio = cursor.getYear();
            int mes = cursor.getMonthValue();
            if (anio != anioPrevio || mes != mesPrevio) {
                for (Pedido p : LectorVentas.leer(
                        directorio.resolve(LectorVentas.nombreArchivo(anio, mes)), anio, mes, mapa)) {
                    if (!p.getRegistro().isBefore(t0) && p.getRegistro().isBefore(fin)) {
                        pedidos.addAll(LectorVentas.fraccionar(p, maximoPorEntrega));
                    }
                }
                for (CalendarioBloqueos.Bloqueo b : LectorBloqueos.leer(
                        directorio.resolve(LectorBloqueos.nombreArchivo(anio, mes)), anio, mes, mapa)
                        .enVentana(t0, fin).getBloqueos()) {
                    calendario.agregar(b);
                }
                anioPrevio = anio;
                mesPrevio = mes;
            }
            cursor = cursor.plusDays(1);
        }
        pedidos.sort(Comparator.comparing(Pedido::getRegistro).thenComparing(Pedido::getId));
        int productos = pedidos.stream().mapToInt(Pedido::getCantidad).sum();
        return new Bloque(def, t0, fin, pedidos, calendario, productos);
    }
}
