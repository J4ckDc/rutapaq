package pe.pucp.paqrap.planner.ien;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import pe.pucp.paqrap.planner.alns.ALNS_PAQRAP;
import pe.pucp.paqrap.planner.core.Configuracion;
import pe.pucp.paqrap.planner.core.MapaReticula;
import pe.pucp.paqrap.planner.core.Planificador;
import pe.pucp.paqrap.planner.hgs.HGS_PAQRAP;

/**
 * Banco de pruebas del IEN: ejecuta los veinte bloques con el ALNS y con el GA
 * y produce la hoja de datos del tratamiento.
 *
 * <p>Uso: {@code java pe.pucp.paqrap.planner.ien.RunnerIEN [--datos=dir]
 * [--salida=dir] [--bloques=1-20] [--algoritmos=ALNS,GA] [--generar]
 * [clave=valor ...]}. La opcion {@code --generar} crea un historial sintetico
 * con las caracteristicas de las Tablas 5 y 6 cuando aun no se dispone de los
 * archivos oficiales.</p>
 *
 * <p>Salidas: {@code datos_tratamiento.csv} con las columnas bloque, prod_dia,
 * tc_alns y tc_ga que espera el cuaderno del Anexo 4, y
 * {@code detalle_corridas.csv} con la causa del colapso, el Ta maximo y
 * promedio, el numero de ejecuciones y los productos entregados de cada corrida
 * (Anexo 1).</p>
 */
public final class RunnerIEN {

    public static void main(String[] args) {
        Configuracion cfg = Configuracion.porDefecto();
        Path datos = Path.of("datos");
        Path salida = Path.of("resultados");
        boolean generar = false;
        boolean soloGenerar = false;
        List<Integer> seleccion = new ArrayList<>();
        List<String> algoritmos = new ArrayList<>(List.of("ALNS", "GA"));

        for (String arg : args) {
            if (arg.startsWith("--datos=")) {
                datos = Path.of(arg.substring(8));
            } else if (arg.startsWith("--salida=")) {
                salida = Path.of(arg.substring(9));
            } else if (arg.equals("--generar")) {
                generar = true;
            } else if (arg.equals("--solo-generar")) {
                generar = true;
                soloGenerar = true;
            } else if (arg.startsWith("--bloques=")) {
                seleccion.addAll(rango(arg.substring(10)));
            } else if (arg.startsWith("--algoritmos=")) {
                algoritmos.clear();
                for (String a : arg.substring(13).split(",")) {
                    algoritmos.add(a.trim().toUpperCase(Locale.ROOT));
                }
            } else if (arg.contains("=")) {
                int i = arg.indexOf('=');
                cfg.con(arg.substring(0, i), arg.substring(i + 1));
            }
        }

        int horizonte = cfg.entero("ien.horizonteHoras", 48);
        MapaReticula mapa = new MapaReticula(cfg.entero("ien.mapaAnchoKm", 70), cfg.entero("ien.mapaAltoKm", 50));
        List<DefinicionBloque> bloques = DefinicionBloque.bloquesOficiales();
        if (!seleccion.isEmpty()) {
            bloques = bloques.stream().filter(b -> seleccion.contains(b.numero())).toList();
        }
        if (generar) {
            new GeneradorDatosSinteticos(cfg.semilla())
                    .generar(datos, DefinicionBloque.bloquesOficiales(), horizonte);
            System.out.println("Historial sintetico generado en " + datos.toAbsolutePath());
            if (soloGenerar) {
                return;
            }
        }

        System.out.println("=== RUTAPAQ / PaqRap - IEN: tiempo hasta el colapso (ALNS vs GA) ===");
        System.out.printf(Locale.US, "Sc = %d min | Sa = %.0f s | P = %.1f s | H = %d h | flota %d/%d/%d%n%n",
                cfg.entero("ien.scMinutos", 60), cfg.numero("ien.saSegundos", 30.0),
                cfg.presupuestoSegundos(), horizonte,
                cfg.entero("ien.autos", 10), cfg.entero("ien.motos", 15), cfg.entero("ien.bicicletas", 12));
        System.out.printf(Locale.US, "%-7s %-12s %10s %9s %6s %8s %8s %9s %11s%n",
                "BLOQUE", "INICIO", "PROD/DIA", "ALG", "Tc(h)", "CAUSA", "EJEC", "TaMax(ms)", "PROD.ENTR.");

        List<ResultadoCorrida> resultados = new ArrayList<>();
        for (DefinicionBloque def : bloques) {
            Bloque bloque = CargadorBloque.cargar(datos, def, horizonte, mapa,
                    cfg.entero("ien.maximoProductosPorEntrega", 24));
            for (String alg : algoritmos) {
                SimuladorColapso simulador = new SimuladorColapso(cfg, mapa);
                Planificador planificador = crear(alg, cfg);
                ResultadoCorrida r = simulador.correr(bloque, planificador);
                resultados.add(r);
                System.out.printf(Locale.US, "%-7d %-12s %10.1f %9s %6.2f %8s %8d %9d %11d%n",
                        def.numero(), def.inicio(), bloque.productosPorDia(), r.algoritmo(), r.tcHoras(),
                        r.causa(), r.ejecuciones(), r.taMaxMs(), r.productosEntregados());
            }
        }

        exportar(salida, resultados, horizonte);
        System.out.println();
        umbrales(resultados, horizonte);
        System.out.println("\nHoja de datos en " + salida.toAbsolutePath());
    }

    private static Planificador crear(String algoritmo, Configuracion cfg) {
        return switch (algoritmo) {
            case "ALNS" -> new ALNS_PAQRAP(cfg, cfg.semilla());
            case "GA", "HGS", "AG" -> new HGS_PAQRAP(cfg, cfg.semilla());
            default -> throw new IllegalArgumentException("Algoritmo desconocido: " + algoritmo);
        };
    }

    /** Carga maxima sostenida L* y primer nivel de carga con colapso L_c (seccion 3.4). */
    private static void umbrales(List<ResultadoCorrida> resultados, int horizonte) {
        for (String alg : resultados.stream().map(ResultadoCorrida::algoritmo).distinct().toList()) {
            List<ResultadoCorrida> propias = new ArrayList<>(resultados.stream()
                    .filter(r -> r.algoritmo().equals(alg)).toList());
            propias.sort(Comparator.comparingDouble(ResultadoCorrida::productosPorDia));
            Double lEstrella = null;
            Double lColapso = null;
            for (ResultadoCorrida r : propias) {
                if (r.tcHoras() >= horizonte - 1.0E-9) {
                    lEstrella = r.productosPorDia();
                } else {
                    lColapso = r.productosPorDia();
                    break;
                }
            }
            System.out.printf(Locale.US, "%-5s  L* = %s prod/dia   L_c = %s prod/dia%n", alg,
                    lEstrella == null ? "sin bloque sostenido" : String.format(Locale.US, "%.1f", lEstrella),
                    lColapso == null ? "no colapso" : String.format(Locale.US, "%.1f", lColapso));
        }
    }

    private static void exportar(Path salida, List<ResultadoCorrida> resultados, int horizonte) {
        try {
            Files.createDirectories(salida);
            List<String> tratamiento = new ArrayList<>();
            tratamiento.add("bloque,prod_dia,tc_alns,tc_ga");
            List<Integer> bloques = resultados.stream().map(ResultadoCorrida::bloque).distinct().sorted().toList();
            for (int b : bloques) {
                Double alns = null;
                Double ga = null;
                double prod = 0;
                for (ResultadoCorrida r : resultados) {
                    if (r.bloque() == b) {
                        prod = r.productosPorDia();
                        if (r.algoritmo().equals("ALNS")) {
                            alns = r.tcHoras();
                        } else {
                            ga = r.tcHoras();
                        }
                    }
                }
                tratamiento.add(String.format(Locale.US, "%d,%.1f,%s,%s", b, prod,
                        alns == null ? "" : String.format(Locale.US, "%.3f", alns),
                        ga == null ? "" : String.format(Locale.US, "%.3f", ga)));
            }
            Files.write(salida.resolve("datos_tratamiento.csv"), tratamiento, StandardCharsets.UTF_8);

            List<String> detalle = new ArrayList<>();
            detalle.add("bloque,prod_dia,algoritmo,tc_h,causa,ta_max_ms,ta_prom_ms,ejecuciones,"
                    + "productos_entregados,pedidos_entregados,pedidos_totales");
            for (ResultadoCorrida r : resultados) {
                detalle.add(String.format(Locale.US, "%d,%.1f,%s,%.3f,%s,%d,%.1f,%d,%d,%d,%d",
                        r.bloque(), r.productosPorDia(), r.algoritmo(), r.tcHoras(), r.causa(),
                        r.taMaxMs(), r.taPromedioMs(), r.ejecuciones(), r.productosEntregados(),
                        r.pedidosEntregados(), r.pedidosTotales()));
            }
            Files.write(salida.resolve("detalle_corridas.csv"), detalle, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<Integer> rango(String texto) {
        List<Integer> r = new ArrayList<>();
        for (String parte : texto.split(",")) {
            if (parte.contains("-")) {
                String[] lim = parte.split("-");
                for (int i = Integer.parseInt(lim[0].trim()); i <= Integer.parseInt(lim[1].trim()); i++) {
                    r.add(i);
                }
            } else {
                r.add(Integer.parseInt(parte.trim()));
            }
        }
        return r;
    }
}
