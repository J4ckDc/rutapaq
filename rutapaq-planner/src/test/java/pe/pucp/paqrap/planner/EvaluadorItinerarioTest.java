package pe.pucp.paqrap.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import pe.pucp.paqrap.planner.core.Configuracion;
import pe.pucp.paqrap.planner.core.DatosPlanificacion;
import pe.pucp.paqrap.planner.core.EvaluadorItinerario;
import pe.pucp.paqrap.planner.core.InstanciaPlanificacion;
import pe.pucp.paqrap.planner.core.MapaReticula;
import pe.pucp.paqrap.planner.core.ResultadoRuta;
import pe.pucp.paqrap.planner.model.Almacen;
import pe.pucp.paqrap.planner.model.ParadaRuta;
import pe.pucp.paqrap.planner.model.Pedido;
import pe.pucp.paqrap.planner.model.TipoAlmacen;
import pe.pucp.paqrap.planner.model.TipoParada;
import pe.pucp.paqrap.planner.model.TipoVehiculo;
import pe.pucp.paqrap.planner.model.TurnoConductor;
import pe.pucp.paqrap.planner.model.Vehiculo;

/** Viajes con recarga, plazos, jornada y refrigerio en el evaluador comun. */
class EvaluadorItinerarioTest {

    private final MapaReticula mapa = new MapaReticula(70, 50);
    private final Configuracion cfg = Configuracion.porDefecto();
    private final LocalDateTime t0 = LocalDate.of(2026, 9, 21).atTime(8, 0);

    private Almacen central() {
        return new Almacen("ALM-CENTRAL", TipoAlmacen.CENTRAL, mapa.indice(27, 14), 27, 14, 0);
    }

    private Almacen intermedio(int stock) {
        Almacen a = new Almacen("ALM-ESTE", TipoAlmacen.INTERMEDIO, mapa.indice(30, 14), 30, 14, 1000);
        a.setStock(stock);
        return a;
    }

    private Pedido pedido(String id, int x, int y, int cantidad, int plazo) {
        return new Pedido(id, "cli", mapa.indice(x, y), x, y, cantidad, t0, plazo, null);
    }

    private DatosPlanificacion datos(List<Almacen> almacenes, Vehiculo v, List<Pedido> pedidos) {
        v.setTurno(TurnoConductor.vigenteEn(t0));
        return DatosPlanificacion.construir(
                new InstanciaPlanificacion(mapa, almacenes, List.of(v), pedidos, t0, cfg));
    }

    @Test
    void unaUnidadRealizaVariosViajesConRecargaDentroDeSuTurno() {
        Vehiculo bici = new Vehiculo("B001", TipoVehiculo.BICI, mapa.indice(27, 14), t0);
        List<Pedido> pedidos = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            pedidos.add(pedido("P" + i, 28 + i, 14, 4, 12));      // 4 productos: una salida cada uno
        }
        DatosPlanificacion d = datos(List.of(central()), bici, pedidos);
        List<ParadaRuta> itinerario = new ArrayList<>();
        ResultadoRuta r = EvaluadorItinerario.evaluar(d, 0, new int[]{0, 1, 2}, d.saldo, itinerario);

        assertTrue(r.valida());
        assertEquals(3, r.recargas(), "la capacidad de 4 obliga a recargar antes de cada entrega");
        assertEquals(3, (int) itinerario.stream().filter(p -> p.tipo() == TipoParada.RECARGA).count());
        assertEquals(3, (int) itinerario.stream().filter(p -> p.tipo() == TipoParada.ENTREGA).count());
    }

    @Test
    void laRecargaUsaElAlmacenMasCercanoConSaldoSuficiente() {
        Vehiculo auto = new Vehiculo("A001", TipoVehiculo.AUTO, mapa.indice(31, 14), t0);
        List<Almacen> almacenes = List.of(central(), intermedio(1000));
        DatosPlanificacion d = datos(almacenes, auto, List.of(pedido("P0", 32, 14, 5, 12)));
        List<ParadaRuta> itinerario = new ArrayList<>();
        EvaluadorItinerario.evaluar(d, 0, new int[]{0}, d.saldo, itinerario);
        assertEquals("ALM-ESTE", primeraRecarga(itinerario), "el intermedio esta a 1 km y el central a 4");

        // Sin saldo en el intermedio, la recarga se hace en el central (inventario infinito).
        DatosPlanificacion d2 = datos(List.of(central(), intermedio(2)), auto,
                List.of(pedido("P0", 32, 14, 5, 12)));
        List<ParadaRuta> it2 = new ArrayList<>();
        EvaluadorItinerario.evaluar(d2, 0, new int[]{0}, d2.saldo, it2);
        assertEquals("ALM-CENTRAL", primeraRecarga(it2));
    }

    @Test
    void elPlazoSeEvaluaConLaHoraDeLlegadaYNoConLaDeAcondicionamiento() {
        Vehiculo bici = new Vehiculo("B001", TipoVehiculo.BICI, mapa.indice(27, 14), t0);
        // 24 km a 12 km/h = 2 h exactas de viaje; plazo de 4 h con 1 h de acondicionamiento.
        DatosPlanificacion d = datos(List.of(central()), bici, List.of(pedido("P0", 51, 14, 4, 4)));
        ResultadoRuta r = EvaluadorItinerario.evaluar(d, 0, new int[]{0}, d.saldo);
        assertTrue(r.valida());
        assertEquals(0.0, r.atrasoPonderadoH(), 1.0E-9, "llega dentro del plazo aunque termine despues");

        DatosPlanificacion d2 = datos(List.of(central()), bici, List.of(pedido("P1", 70, 14, 4, 4)));
        ResultadoRuta r2 = EvaluadorItinerario.evaluar(d2, 0, new int[]{0}, d2.saldo);
        assertTrue(r2.atrasoPonderadoH() > 0.0, "43 km a 12 km/h vencen el plazo de 4 h");
    }

    @Test
    void elRefrigerioSeUbicaDentroDeSuVentanaYElTurnoRota() {
        Vehiculo auto = new Vehiculo("A001", TipoVehiculo.AUTO, mapa.indice(27, 14), t0);
        List<Pedido> pedidos = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            pedidos.add(pedido("P" + i, 28 + i, 14, 4, 36));
        }
        DatosPlanificacion d = datos(List.of(central()), auto, pedidos);
        List<ParadaRuta> itinerario = new ArrayList<>();
        ResultadoRuta r = EvaluadorItinerario.evaluar(d, 0, new int[]{0, 1, 2, 3, 4, 5}, d.saldo, itinerario);

        assertTrue(r.valida());
        List<ParadaRuta> refrigerios = itinerario.stream()
                .filter(p -> p.tipo() == TipoParada.REFRIGERIO).toList();
        assertEquals(1, refrigerios.size(), "una hora de refrigerio en el turno de 07:00");
        LocalDateTime inicio = refrigerios.get(0).llegada();
        assertTrue(!inicio.isBefore(t0.toLocalDate().atTime(8, 0))
                        && !inicio.isAfter(t0.toLocalDate().atTime(13, 0)),
                "debe caer en [07:00 + 1 h, 15:00 - 2 h]: " + inicio);
        assertEquals(0.0, r.refrigerioFueraH(), 1.0E-9);
    }

    private static String primeraRecarga(List<ParadaRuta> itinerario) {
        for (ParadaRuta p : itinerario) {
            if (p.tipo() == TipoParada.RECARGA) {
                return p.referencia();
            }
        }
        return null;
    }

    @Test
    void unaEntregaQueExcedeLaCapacidadDeLaUnidadEsInvalida() {
        Vehiculo bici = new Vehiculo("B001", TipoVehiculo.BICI, mapa.indice(27, 14), t0);
        DatosPlanificacion d = datos(List.of(central()), bici, List.of(pedido("P0", 28, 14, 10, 36)));
        assertFalse(EvaluadorItinerario.evaluar(d, 0, new int[]{0}, d.saldo).valida(),
                "10 productos no caben en una bicicleta de 4");
    }
}
