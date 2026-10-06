package pe.pucp.paqrap.app.datos;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Genera archivos de prueba con el formato oficial (ver Formato), para probar con más o menos demanda.
 * La carpeta datos/ ya trae el historial sintético de rutapaq-planner.
 * Uso: java -jar rutapaq.jar generar datos 202601 [pedidosPorDia=150] [bloqueosPorDia=8] [semilla=7]
 */
public final class GeneradorDatos {
    private static final int[] PLAZOS = {4, 8, 12, 18, 36};
    private static final int[] PESOS = {10, 15, 20, 20, 35};

    private GeneradorDatos() {
    }

    public static void generar(Path carpeta, YearMonth mes, int pedidosDia, int bloqueosDia, long semilla) throws IOException {
        Files.createDirectories(carpeta);
        Random r = new Random(semilla);
        int dias = mes.lengthOfMonth();

        Path ventas = carpeta.resolve(Formato.archivoVentas(mes));
        try (BufferedWriter w = Files.newBufferedWriter(ventas, StandardCharsets.UTF_8)) {
            for (int d = 1; d <= dias; d++) {
                List<Integer> minutos = new ArrayList<>();
                for (int i = 0; i < pedidosDia; i++) minutos.add(r.nextInt(24 * 60));
                minutos.sort(null);
                for (int m : minutos) {
                    int x = r.nextInt(71), y = r.nextInt(51);
                    int cantidad = 1 + (int) Math.min(23, Math.abs(r.nextGaussian() * 4 + 3));
                    w.write(String.format("%02dD%02dH%03d:%d,%d,c-%05d,%d,%d%n", d, m / 60, m % 60, x, y,
                            1 + r.nextInt(500), cantidad, plazo(r)));
                }
            }
        }

        Path bloqueos = carpeta.resolve(Formato.archivoBloqueos(mes));
        try (BufferedWriter w = Files.newBufferedWriter(bloqueos, StandardCharsets.UTF_8)) {
            for (int d = 1; d <= dias; d++) {
                List<int[]> inicios = new ArrayList<>();
                for (int i = 0; i < bloqueosDia; i++) inicios.add(new int[]{r.nextInt(24 * 60)});
                inicios.sort((a, b) -> Integer.compare(a[0], b[0]));
                for (int[] ini : inicios) {
                    int fin = Math.min(ini[0] + 120 + r.nextInt(600), 24 * 60 - 1);
                    StringBuilder pts = new StringBuilder();
                    int x = 2 + r.nextInt(67), y = 2 + r.nextInt(47);
                    pts.append(x).append(',').append(y);
                    boolean horizontal = r.nextBoolean();
                    for (int k = 0; k < 1 + r.nextInt(2); k++) {
                        int largo = 3 + r.nextInt(8) * (r.nextBoolean() ? 1 : -1);
                        if (horizontal) x = Math.max(0, Math.min(70, x + largo));
                        else y = Math.max(0, Math.min(50, y + largo));
                        pts.append(',').append(x).append(',').append(y);
                        horizontal = !horizontal;
                    }
                    w.write(String.format("%02dD%02dH%03d-%02dD%02dH%03d:%s%n", d, ini[0] / 60, ini[0] % 60,
                            d, fin / 60, fin % 60, pts));
                }
            }
        }
        System.out.println("Generados " + ventas + " y " + bloqueos);
    }

    private static int plazo(Random r) {
        int v = r.nextInt(100), acc = 0;
        for (int i = 0; i < PLAZOS.length; i++) {
            acc += PESOS[i];
            if (v < acc) return PLAZOS[i];
        }
        return 36;
    }
}
