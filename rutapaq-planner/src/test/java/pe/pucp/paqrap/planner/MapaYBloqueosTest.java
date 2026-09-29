package pe.pucp.paqrap.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import pe.pucp.paqrap.planner.core.MapaReticula;
import pe.pucp.paqrap.planner.ien.LectorBloqueos;

/** Requisitos de la Tabla 9 del IEN: mapa oficial y bloqueos de nodo. */
class MapaYBloqueosTest {

    private MapaReticula mapa() {
        return new MapaReticula(70, 50);
    }

    @Test
    void laReticulaOficialTieneLosNodosEsperados() {
        MapaReticula m = mapa();
        assertEquals(71 * 51, m.getNumNodos(), "71 x 51 = 3 621 nodos");
    }

    @Test
    void sinBloqueosLaDistanciaEsLaManhattan() {
        MapaReticula m = mapa();
        int[] d = m.distanciasDesde(m.indice(27, 14));
        assertEquals(MapaReticula.manhattan(27, 14, 57, 27), d[m.indice(57, 27)]);
        assertEquals(MapaReticula.manhattan(27, 14, 0, 0), d[m.indice(0, 0)]);
        assertEquals(MapaReticula.manhattan(27, 14, 70, 50), d[m.indice(70, 50)]);
    }

    @Test
    void unBloqueoNoSeAtraviesaYObligaAlDesvio() {
        MapaReticula m = mapa();
        // Muro vertical en x = 5, de y = 0 a y = 50: para cruzarlo no hay camino.
        for (int y = 0; y <= 50; y++) {
            m.bloquear(m.indice(5, y));
        }
        assertEquals(-1, m.distancia(m.indice(4, 10), m.indice(6, 10)), "el muro aisla ambos lados");

        MapaReticula m2 = mapa();
        for (int y = 0; y <= 20; y++) {
            m2.bloquear(m2.indice(5, y));
        }
        int desvio = m2.distancia(m2.indice(4, 10), m2.indice(6, 10));
        assertTrue(desvio > 2, "el desvio debe ser mas largo que la distancia directa: " + desvio);
    }

    @Test
    void unClienteSobreUnNodoBloqueadoSigueSiendoAlcanzable() {
        MapaReticula m = mapa();
        int cliente = m.indice(10, 10);
        m.bloquear(cliente);
        assertEquals(2, m.distancia(m.indice(8, 10), cliente), "se llega al nodo bloqueado y se regresa");
        // Pero no puede usarse como paso intermedio.
        for (int n : new int[]{m.indice(10, 9), m.indice(10, 11)}) {
            m.bloquear(n);
        }
        assertEquals(6, m.distancia(m.indice(9, 10), m.indice(11, 10)),
                "el trayecto rodea los tres nodos bloqueados de la columna");
    }

    @Test
    void laPoligonalAbiertaBloqueaTodosLosNodosDeSusTramos() {
        MapaReticula m = mapa();
        int[] nodos = LectorBloqueos.nodosDePoligonal(new int[]{15, 10, 15, 13, 18, 13}, m);
        assertEquals(7, nodos.length, "4 nodos del tramo vertical + 3 nuevos del horizontal");
        for (int n : nodos) {
            m.bloquear(n);
        }
        assertTrue(m.estaBloqueado(m.indice(15, 10)));
        assertTrue(m.estaBloqueado(m.indice(15, 13)), "el vertice queda bloqueado");
        assertTrue(m.estaBloqueado(m.indice(18, 13)), "el extremo queda bloqueado");
    }
}
