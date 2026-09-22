package pe.pucp.paqrap.planner.alns;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import pe.pucp.paqrap.planner.core.RedVial;
import pe.pucp.paqrap.planner.model.EstadoVehiculo;
import pe.pucp.paqrap.planner.model.Incidencia;
import pe.pucp.paqrap.planner.model.NodoRed;
import pe.pucp.paqrap.planner.model.Pedido;
import pe.pucp.paqrap.planner.model.Ruta;
import pe.pucp.paqrap.planner.model.Vehiculo;
import pe.pucp.paqrap.planner.model.VisitaCliente;

/**
 * Traduccion de los eventos disruptivos del enunciado a operaciones de
 * destruccion dirigida (Destroy_StreetBlockRemoval y Destroy_BreakdownRemoval).
 * A diferencia de los operadores de la ruleta, estos no se invocan por azar sino
 * ante la llegada de una incidencia, antes de iniciar el bucle de busqueda.
 */
public final class ManejadorIncidencias {

    private ManejadorIncidencias() {
    }

    /** Aplica las incidencias al estado y devuelve el banco de pedidos afectados. */
    public static List<Pedido> aplicar(EstadoALNS estado, List<Incidencia> incidencias) {
        List<Pedido> banco = new ArrayList<>();
        if (incidencias == null || incidencias.isEmpty()) {
            return banco;
        }
        for (Incidencia inc : incidencias) {
            switch (inc.getTipoIncidencia()) {
                case BLOQUEO_CALLE -> banco.addAll(bloqueoCalle(estado, inc));
                case FALLA_MECANICA -> banco.addAll(fallaMecanica(estado, inc));
                default -> throw new IllegalStateException("Incidencia no soportada: " + inc);
            }
        }
        return banco;
    }

    /**
     * Destroy_StreetBlockRemoval. El arco se invalida en ambos sentidos por
     * tratarse de calles de doble sentido; los caminos minimos se recalculan bajo
     * demanda y cada ruta afectada se reevalua: si el desvio respeta el plazo de
     * todas sus visitas pendientes, la ruta se conserva con el recorrido
     * alternativo; en caso contrario se extraen sus visitas no congeladas.
     */
    private static List<Pedido> bloqueoCalle(EstadoALNS estado, Incidencia inc) {
        List<Pedido> banco = new ArrayList<>();
        RedVial red = estado.getContexto().getRedVial();
        red.bloquear(inc.getArcoAfectado());
        red.invalidarCaminos();
        for (Ruta ruta : new ArrayList<>(estado.getRutas())) {
            if (!estado.revalidar(ruta).factible()) {
                banco.addAll(extraerHastaFactible(estado, ruta));
            }
        }
        return banco;
    }

    /**
     * Destroy_BreakdownRemoval. La unidad inmovilizada pasa a AVERIADO y su
     * posicion se modela como deposito temporal cuyo stock equivale a los
     * paquetes a bordo; los pedidos que transportaba ingresan al banco ordenados
     * por fecha limite ascendente, de modo que las ventanas de 4 y 8 horas
     * encabezan la reparacion.
     */
    private static List<Pedido> fallaMecanica(EstadoALNS estado, Incidencia inc) {
        List<Pedido> banco = new ArrayList<>();
        Vehiculo averiado = estado.getContexto().vehiculo(inc.getIdVehiculoAfectado());
        if (averiado == null) {
            return banco;
        }
        Ruta ruta = estado.rutaDe(averiado.getIdVehiculo());
        int aBordo = ruta == null ? averiado.getCargaActual() : ruta.getCarga();
        NodoRed posicion = averiado.getUbicacionActual();
        NodoRed deposito = NodoRed.depositoTemporal("DT-" + averiado.getIdVehiculo(), posicion, aBordo);
        estado.getContexto().getRedVial().agregarNodoEnPosicionDe(deposito, posicion.getIdNodo());
        averiado.setEstado(EstadoVehiculo.AVERIADO);

        if (ruta != null) {
            for (VisitaCliente v : ruta.visitasNoCongeladas()) {
                Pedido p = estado.remover(ruta, v);
                if (p != null) {
                    banco.add(p);
                }
            }
        }
        banco.sort(Comparator.comparing(Pedido::getFechaHoraLimite));
        return banco;
    }

    /** Extrae visitas no congeladas de la peor holgura hasta restituir la factibilidad. */
    private static List<Pedido> extraerHastaFactible(EstadoALNS estado, Ruta ruta) {
        List<Pedido> banco = new ArrayList<>();
        while (!ruta.isFactible() && ruta.tieneVisitasNoCongeladas()) {
            List<VisitaCliente> libres = ruta.visitasNoCongeladas();
            libres.sort(Comparator.comparingDouble(VisitaCliente::getHolguraHoras));
            Pedido p = estado.remover(ruta, libres.get(0));
            if (p == null) {
                break;
            }
            banco.add(p);
            if (ruta.getListaParadas().isEmpty()) {
                break;
            }
            estado.revalidar(ruta);
        }
        return banco;
    }
}
