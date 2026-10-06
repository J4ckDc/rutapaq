package pe.pucp.paqrap.planner.ien;

/**
 * Resultado de una corrida (un bloque resuelto por un algoritmo).
 * La causa es P (incumplimiento de plazo), C (caida por Ta >= Sa) o N (no colapso).
 */
public record ResultadoCorrida(int bloque,
                               String algoritmo,
                               double productosPorDia,
                               double tcHoras,
                               String causa,
                               long taMaxMs,
                               double taPromedioMs,
                               int ejecuciones,
                               int productosEntregados,
                               int pedidosEntregados,
                               int pedidosTotales) {
}
