package pe.pucp.paqrap.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import pe.pucp.paqrap.planner.core.Configuracion;
import pe.pucp.paqrap.planner.core.ContextoPlanificacion;
import pe.pucp.paqrap.planner.core.RedVial;
import pe.pucp.paqrap.planner.core.ResultadoFactibilidad;
import pe.pucp.paqrap.planner.core.ValidadorFactibilidad;
import pe.pucp.paqrap.planner.model.Almacen;
import pe.pucp.paqrap.planner.model.Arco;
import pe.pucp.paqrap.planner.model.Pedido;
import pe.pucp.paqrap.planner.model.Ruta;
import pe.pucp.paqrap.planner.model.TipoVehiculo;
import pe.pucp.paqrap.planner.model.Vehiculo;
import pe.pucp.paqrap.planner.model.VisitaCliente;

/** Verificacion de las restricciones duras R1 a R7 del caso PaqRap. */
class ValidadorFactibilidadTest {

    private final Configuracion cfg = Configuracion.porDefecto();
    private final ValidadorFactibilidad validador = new ValidadorFactibilidad(cfg);

    private Ruta rutaCon(Vehiculo v, Almacen origen, Pedido... pedidos) {
        Ruta ruta = new Ruta("R1", v, origen, FixturasPrueba.INICIO_TURNO);
        int pos = 0;
        for (Pedido p : pedidos) {
            ruta.insertar(pos++, new VisitaCliente(p, p.getCantidadPaquetes()));
        }
        return ruta;
    }

    @Test
    void r1RechazaCargaSuperiorALaCapacidadDeLaUnidad() {
        RedVial red = FixturasPrueba.redLineal();
        Almacen central = FixturasPrueba.almacenCentral(red);
        Vehiculo bici = FixturasPrueba.unidad("B001", TipoVehiculo.BICI, central);
        Vehiculo moto = FixturasPrueba.unidad("M001", TipoVehiculo.MOTO, central);
        ContextoPlanificacion ctx = FixturasPrueba.contexto(red, List.of(central),
                List.of(bici, moto), cfg);
        Pedido p = FixturasPrueba.pedido("P1", red.nodo("B"), 5, 8);

        assertFalse(validador.evaluarRuta(rutaCon(bici, central, p), ctx, 0).factible(),
                "5 paquetes exceden la capacidad de 4 de la bicicleta");
        assertTrue(validador.evaluarRuta(rutaCon(moto, central, p), ctx, 0).factible(),
                "5 paquetes caben en la capacidad de 8 de la moto");
    }

    @Test
    void r2RechazaSobreasignacionDeUnAlmacenIntermedio() {
        RedVial red = FixturasPrueba.redLineal();
        Almacen intermedio = FixturasPrueba.almacenIntermedio(red, 3);
        Vehiculo auto = FixturasPrueba.unidad("A001", TipoVehiculo.AUTO, intermedio);
        ContextoPlanificacion ctx = FixturasPrueba.contexto(red, List.of(intermedio), List.of(auto), cfg);
        Pedido p = FixturasPrueba.pedido("P1", red.nodo("B"), 4, 8);

        assertFalse(validador.evaluarRuta(rutaCon(auto, intermedio, p), ctx, 0).factible(),
                "el saldo de 3 unidades no cubre un pedido de 4 paquetes");
    }

    @Test
    void r3UnBloqueoInvalidaElArcoEnAmbosSentidos() {
        RedVial red = FixturasPrueba.redLineal();
        assertEquals(2.0, red.distanciaKm("A", "C"), 0.05);
        red.bloquear(Arco.de("B", "A"));
        red.invalidarCaminos();
        assertFalse(red.hayCamino("A", "C"),
                "al ser la calle de doble sentido, (B,A) invalida tambien (A,B)");
    }

    @Test
    void r5RechazaUnaEntregaFueraDeLaVentanaComprometida() {
        RedVial red = FixturasPrueba.redLineal();
        Almacen central = FixturasPrueba.almacenCentral(red);
        Vehiculo bici = FixturasPrueba.unidad("B001", TipoVehiculo.BICI, central);
        ContextoPlanificacion ctx = FixturasPrueba.contexto(red, List.of(central), List.of(bici), cfg);
        // Cuatro entregas de 1 h de servicio mas la hora de refrigerio situan la
        // ultima llegada alrededor de las 11:25, fuera de la ventana de 4 h que
        // vence a las 11:00.
        Pedido p1 = FixturasPrueba.pedido("P1", red.nodo("B"), 1, 8);
        Pedido p2 = FixturasPrueba.pedido("P2", red.nodo("C"), 1, 8);
        Pedido p3 = FixturasPrueba.pedido("P3", red.nodo("D"), 1, 8);
        Pedido p4 = FixturasPrueba.pedido("P4", red.nodo("B"), 1, 4);

        ResultadoFactibilidad rf = validador.evaluarRuta(rutaCon(bici, central, p1, p2, p3, p4), ctx, 0);
        assertFalse(rf.factible(), "la ultima visita vence su plazo de 4 horas");

        // Con la misma secuencia pero ventana regular de 36 h la ruta es factible.
        Pedido p4Regular = FixturasPrueba.pedido("P4", red.nodo("B"), 1, 36);
        assertTrue(validador.evaluarRuta(rutaCon(bici, central, p1, p2, p3, p4Regular), ctx, 0).factible(),
                "la restriccion que se incumple es el plazo, no la jornada");
    }

    @Test
    void r6yR7JornadaDeOchoHorasConRefrigerioDentroDeLaVentana() {
        RedVial red = FixturasPrueba.redLineal();
        Almacen central = FixturasPrueba.almacenCentral(red);
        Vehiculo auto = FixturasPrueba.unidad("A001", TipoVehiculo.AUTO, central);
        ContextoPlanificacion ctx = FixturasPrueba.contexto(red, List.of(central), List.of(auto), cfg);

        Ruta corta = rutaCon(auto, central,
                FixturasPrueba.pedido("P1", red.nodo("B"), 1, 12),
                FixturasPrueba.pedido("P2", red.nodo("C"), 1, 12));
        ResultadoFactibilidad rf = validador.evaluarRuta(corta, ctx, 0);
        assertTrue(rf.factible());
        assertTrue(!rf.inicioRefrigerio().isBefore(auto.getTurnoActual().getVentanaRefrigerioDesde())
                        && !rf.inicioRefrigerio().isAfter(auto.getTurnoActual().getVentanaRefrigerioHasta()),
                "el refrigerio debe ubicarse dentro de [inicio + 1 h, fin - 2 h]");
        assertTrue(!rf.fechaHoraFin().isAfter(auto.getTurnoActual().getHoraFin()));

        Ruta larga = new Ruta("R2", auto, central, FixturasPrueba.INICIO_TURNO);
        for (int i = 0; i < 9; i++) {
            Pedido p = FixturasPrueba.pedido("PL" + i, red.nodo("B"), 1, 36);
            larga.insertar(i, new VisitaCliente(p, 1));
        }
        assertFalse(validador.evaluarRuta(larga, ctx, 0).factible(),
                "nueve entregas de 1 h mas el refrigerio no caben en un turno de 8 h");
    }
}
