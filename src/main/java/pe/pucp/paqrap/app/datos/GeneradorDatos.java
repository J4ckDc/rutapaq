package pe.pucp.paqrap.app.datos;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.BiFunction;

/**
 * Genera archivos de prueba con el formato oficial (ver Formato), para probar con más o menos demanda.
 * La carpeta datos/ ya trae el historial sintético de rutapaq-planner.
 * Uso: java -jar rutapaq.jar generar datos 202601 [pedidosPorDia=150] [bloqueosPorDia=8] [semilla=7]
 * v03: también genera averías programadas de prueba (generarAverias).
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
                    w.write(String.format("%02dd%02dh%02dm:%d,%d,c-%05d,%d,%d%n", d, m / 60, m % 60, x, y,
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
                    w.write(String.format("%02dd%02dh%02dm-%02dd%02dh%02dm:%s%n", d, ini[0] / 60, ini[0] % 60,
                            d, fin / 60, fin % 60, pts));
                }
            }
        }
        System.out.println("Generados " + ventas + " y " + bloqueos);
    }

    /**
     * v03: genera averias.aaaamm.txt (averías programadas de prueba) para cada mes de desde a hasta.
     * Cada día elige porDia unidades distintas de la flota, en un minuto al azar del día, con tipo 1 (50 %),
     * tipo 2 (30 %) o tipo 3 (20 %). No elige una unidad que ese día esté en mantenimiento preventivo ni una que
     * siga averiada por un registro anterior: vuelveDisponible(tipo, instante) aplica las reglas vigentes.
     * Cada mes usa su propia semilla, así que el mismo mes sale igual aunque se generen rangos distintos.
     * Uso: java -jar rutapaq.jar generar-averias datos 202601 [202812] [averiasPorDia=2] [semilla=7]
     */
    public static void generarAverias(Path carpeta, YearMonth desde, YearMonth hasta, int porDia, long semilla,
                                      List<String> flota,
                                      BiFunction<Integer, LocalDateTime, LocalDateTime> vuelveDisponible) throws IOException {
        Files.createDirectories(carpeta);
        PlanMantenimiento mantenimiento = new PlanMantenimiento(carpeta);
        Map<String, LocalDateTime> libreDesde = new HashMap<>();
        int archivos = 0, registros = 0;
        for (YearMonth mes = desde; !mes.isAfter(hasta); mes = mes.plusMonths(1)) {
            Random r = new Random(semilla * 1_000_003L + mes.getYear() * 12L + mes.getMonthValue());
            Path archivo = carpeta.resolve(Formato.archivoAverias(mes));
            try (BufferedWriter w = Files.newBufferedWriter(archivo, StandardCharsets.UTF_8)) {
                w.write("# Averías programadas de prueba generadas por RUTAPAQ. Registro: ##d##h##m:TTNN,tipo (1 menor, 2 intermedia, 3 mayor)\n");
                for (int d = 1; d <= mes.lengthOfMonth(); d++) {
                    List<String> candidatas = new ArrayList<>(flota);
                    Collections.shuffle(candidatas, r);
                    List<int[]> minutos = new ArrayList<>();
                    for (int i = 0; i < porDia; i++) minutos.add(new int[]{r.nextInt(24 * 60), tipoAveria(r)});
                    minutos.sort((a, b) -> Integer.compare(a[0], b[0]));
                    List<String> lineas = new ArrayList<>();
                    for (int[] m : minutos) {
                        LocalDateTime instante = mes.atDay(d).atStartOfDay().plusMinutes(m[0]);
                        for (int i = 0; i < candidatas.size(); i++) {
                            String u = candidatas.get(i);
                            LocalDateTime libre = libreDesde.get(u);
                            if (mantenimiento.enMantenimiento(u, instante.toLocalDate())) continue;
                            if (libre != null && libre.isAfter(instante)) continue;
                            candidatas.remove(i);
                            libreDesde.put(u, vuelveDisponible.apply(m[1], instante));
                            lineas.add(String.format("%02dd%02dh%02dm:%s,%d%n", d, m[0] / 60, m[0] % 60, u, m[1]));
                            break;
                        }
                    }
                    for (String l : lineas) w.write(l);
                    registros += lineas.size();
                }
            }
            archivos++;
        }
        System.out.printf("Generados %d archivos de averías (%d registros) en %s%n", archivos, registros, carpeta);
    }

    private static int tipoAveria(Random r) {
        int v = r.nextInt(100);
        return v < 50 ? 1 : v < 80 ? 2 : 3;
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
