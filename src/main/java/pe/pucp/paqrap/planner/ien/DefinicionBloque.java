package pe.pucp.paqrap.planner.ien;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Bloque del experimento: ventana de operacion real de 48 horas que comienza a
 * las 00:00 de una fecha t0. Las veinte fechas son las de la Tabla 7 del IEN.
 */
public record DefinicionBloque(int numero, LocalDate inicio) {

    public static List<DefinicionBloque> bloquesOficiales() {
        int[][] fechas = {
                {2026, 1, 24}, {2026, 3, 7}, {2026, 6, 12}, {2026, 7, 21}, {2026, 9, 2},
                {2026, 10, 23}, {2026, 12, 6}, {2027, 3, 13}, {2027, 4, 1}, {2027, 6, 8},
                {2027, 7, 3}, {2027, 10, 1}, {2027, 11, 4}, {2028, 2, 4}, {2028, 3, 6},
                {2028, 5, 2}, {2028, 6, 3}, {2028, 9, 4}, {2028, 10, 1}, {2028, 12, 3}};
        List<DefinicionBloque> bloques = new ArrayList<>();
        for (int i = 0; i < fechas.length; i++) {
            bloques.add(new DefinicionBloque(i + 1, LocalDate.of(fechas[i][0], fechas[i][1], fechas[i][2])));
        }
        return bloques;
    }
}
