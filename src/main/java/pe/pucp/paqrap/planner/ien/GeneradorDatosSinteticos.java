package pe.pucp.paqrap.planner.ien;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Generador de un historial sintetico con las caracteristicas documentadas en
 * las Tablas 5 y 6 del IEN (crecimiento sostenido de la demanda, productos por
 * pedido de 1 a 10, plazos 4/8/12/18/36 h con proporciones 15/15/15/15/40 %,
 * quince poligonales de bloqueo que se repiten, unas 19.8 al dia y duraciones de
 * 1 minuto a 4 horas). Sirve para verificar el banco de pruebas de extremo a
 * extremo mientras no se dispone de los archivos oficiales; basta reemplazar los
 * archivos del directorio de datos por los entregados por el curso.
 */
public final class GeneradorDatosSinteticos {

    private static final int[] PLAZOS = {4, 8, 12, 18, 36};
    private static final double[] PESOS = {0.150, 0.151, 0.151, 0.148, 0.400};

    private final SplittableRandom rnd;
    private final int[][][] poligonales;

    public GeneradorDatosSinteticos(long semilla) {
        this.rnd = new SplittableRandom(semilla);
        this.poligonales = construirPoligonales();
    }

    /** Genera los archivos mensuales necesarios para los bloques indicados. */
    public void generar(Path directorio, List<DefinicionBloque> bloques, int horizonteHoras) {
        try {
            Files.createDirectories(directorio);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<YearMonth> meses = new ArrayList<>();
        for (DefinicionBloque b : bloques) {
            LocalDate inicio = b.inicio();
            LocalDate fin = inicio.plusDays(horizonteHoras / 24 + 1L);
            for (LocalDate d = inicio; !d.isAfter(fin); d = d.plusDays(1)) {
                YearMonth ym = YearMonth.from(d);
                if (!meses.contains(ym)) {
                    meses.add(ym);
                }
            }
        }
        for (YearMonth ym : meses) {
            generarVentas(directorio, ym);
            generarBloqueos(directorio, ym);
        }
    }

    /** Productos por dia del mes, con crecimiento exponencial de 111 a 5 182. */
    public static double productosPorDia(YearMonth ym) {
        int meses = (ym.getYear() - 2026) * 12 + ym.getMonthValue() - 1;
        double factor = Math.pow(5182.0 / 111.0, Math.min(35, Math.max(0, meses)) / 35.0);
        return 111.0 * factor;
    }

    private void generarVentas(Path dir, YearMonth ym) {
        Path archivo = dir.resolve(LectorVentas.nombreArchivo(ym.getYear(), ym.getMonthValue()));
        int dias = ym.lengthOfMonth();
        double productosDia = productosPorDia(ym);
        double pedidosDia = productosDia / 5.5;
        List<long[]> registros = new ArrayList<>();       // {minutoAbsoluto, x, y, qq, hl}
        for (int dia = 1; dia <= dias; dia++) {
            int pedidos = (int) Math.round(pedidosDia * (0.85 + 0.30 * rnd.nextDouble()));
            for (int i = 0; i < pedidos; i++) {
                int hora = rnd.nextInt(24);
                int minuto = rnd.nextInt(60);
                registros.add(new long[]{(long) (dia - 1) * 1440 + hora * 60L + minuto,
                        rnd.nextInt(71), rnd.nextInt(51), 1 + rnd.nextInt(10), plazo()});
            }
        }
        registros.sort(Comparator.comparingLong(r -> r[0]));
        // Desde setiembre de 2026 los archivos entregados estan truncados a 5 000 registros.
        boolean truncar = ym.getYear() > 2026 || (ym.getYear() == 2026 && ym.getMonthValue() >= 9);
        if (truncar && registros.size() > 5000) {
            registros = new ArrayList<>(registros.subList(0, 5000));
        }
        List<String> lineas = new ArrayList<>();
        int cliente = 0;
        for (long[] r : registros) {
            int dia = (int) (r[0] / 1440) + 1;
            int hora = (int) (r[0] % 1440) / 60;
            int minuto = (int) (r[0] % 60);
            lineas.add(String.format("%02dD%02dH%03d:%d,%d,c-%05d,%d,%d",
                    dia, hora, minuto, r[1], r[2], ++cliente, r[3], r[4]));
        }
        escribir(archivo, lineas);
    }

    private int plazo() {
        double u = rnd.nextDouble();
        double acumulado = 0.0;
        for (int i = 0; i < PLAZOS.length; i++) {
            acumulado += PESOS[i];
            if (u <= acumulado) {
                return PLAZOS[i];
            }
        }
        return PLAZOS[PLAZOS.length - 1];
    }

    private void generarBloqueos(Path dir, YearMonth ym) {
        Path archivo = dir.resolve(LectorBloqueos.nombreArchivo(ym.getYear(), ym.getMonthValue()));
        int dias = ym.lengthOfMonth();
        List<String> lineas = new ArrayList<>();
        int total = (int) Math.round(19.8 * dias);
        for (int i = 0; i < total; i++) {
            int dia = 1 + rnd.nextInt(dias);
            int hora = rnd.nextInt(24);
            int minuto = rnd.nextInt(60);
            int duracion = 1 + rnd.nextInt(240);                    // de 1 minuto a 4 horas
            long inicio = (long) (dia - 1) * 1440 + hora * 60L + minuto;
            long fin = Math.min(inicio + duracion, (long) dias * 1440 - 1);
            int[][] poli = poligonales[rnd.nextInt(poligonales.length)];
            StringBuilder puntos = new StringBuilder();
            for (int[] p : poli) {
                if (puntos.length() > 0) {
                    puntos.append(',');
                }
                puntos.append(p[0]).append(',').append(p[1]);
            }
            lineas.add(String.format("%02dD%02dH%03d-%02dD%02dH%03d:%s",
                    (int) (inicio / 1440) + 1, (int) (inicio % 1440) / 60, (int) (inicio % 60),
                    (int) (fin / 1440) + 1, (int) (fin % 1440) / 60, (int) (fin % 60), puntos));
        }
        lineas.sort(String::compareTo);
        escribir(archivo, lineas);
    }

    /** Quince corredores fijos que no incluyen los nodos de los almacenes. */
    private static int[][][] construirPoligonales() {
        return new int[][][]{
                {{5, 5}, {5, 20}}, {{9, 30}, {20, 30}}, {{15, 10}, {15, 25}, {25, 25}},
                {{30, 5}, {45, 5}}, {{33, 20}, {33, 35}}, {{40, 30}, {55, 30}, {55, 40}},
                {{20, 44}, {35, 44}}, {{60, 10}, {60, 24}}, {{50, 15}, {65, 15}},
                {{10, 8}, {24, 8}}, {{28, 40}, {40, 40}}, {{45, 45}, {58, 45}},
                {{62, 33}, {62, 47}}, {{3, 25}, {3, 42}}, {{36, 12}, {36, 24}, {48, 24}}};
    }

    private static void escribir(Path archivo, List<String> lineas) {
        try {
            Files.write(archivo, lineas, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
