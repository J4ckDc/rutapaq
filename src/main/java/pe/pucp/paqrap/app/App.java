package pe.pucp.paqrap.app;

import pe.pucp.paqrap.app.datos.GeneradorDatos;
import pe.pucp.paqrap.app.sim.Planificadores;
import pe.pucp.paqrap.app.sim.Simulacion;
import pe.pucp.paqrap.app.web.Servidor;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;

/**
 * Punto de entrada.
 *   java -jar rutapaq.jar                                   servidor + visualizador
 *   java -jar rutapaq.jar generar datos 202601 [150 8 7]    archivos de prueba
 *   java -jar rutapaq.jar probar 2026-01-01 [120] [SEMANAL|COLAPSO]   simulación sin interfaz (consola)
 *   java -cp rutapaq.jar pe.pucp.paqrap.planner.ien.RunnerIEN   banco experimental del IEN (sin cambios)
 */
public final class App {
    public static void main(String[] args) throws Exception {
        Config cfg = new Config(Path.of("config.properties"));
        String cmd = args.length > 0 ? args[0] : "servidor";
        switch (cmd) {
            case "generar" -> GeneradorDatos.generar(Path.of(args[1]),
                    YearMonth.parse(args[2], DateTimeFormatter.ofPattern("yyyyMM")),
                    args.length > 3 ? Integer.parseInt(args[3]) : 150,
                    args.length > 4 ? Integer.parseInt(args[4]) : 8,
                    args.length > 5 ? Long.parseLong(args[5]) : 7);
            case "probar" -> {
                LocalDate fecha = LocalDate.parse(args.length > 1 ? args[1] : "2026-01-01");
                double horas = args.length > 2 ? Double.parseDouble(args[2]) : 120;
                Simulacion.Escenario esc = Simulacion.Escenario.valueOf(args.length > 3 ? args[3].toUpperCase() : "SEMANAL");
                Simulacion s = new Simulacion(cfg, esc, fecha.atStartOfDay(), Planificadores.crear(cfg.planificador()));
                long t0 = System.currentTimeMillis();
                for (int h = 12; h <= horas; h += 12) {
                    s.avanzar(12 * 3600);
                    System.out.println(s.resumen());
                    if (s.estado() == Simulacion.Estado.TERMINADA) break;
                }
                System.out.printf("Tiempo de cómputo: %.1f s%n", (System.currentTimeMillis() - t0) / 1000.0);
            }
            default -> new Servidor(cfg).iniciar();
        }
    }
}
