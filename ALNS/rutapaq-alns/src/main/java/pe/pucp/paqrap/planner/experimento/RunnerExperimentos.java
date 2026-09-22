package pe.pucp.paqrap.planner.experimento;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import pe.pucp.paqrap.planner.alns.ALNS_PAQRAP;
import pe.pucp.paqrap.planner.core.Configuracion;
import pe.pucp.paqrap.planner.core.ContextoPlanificacion;
import pe.pucp.paqrap.planner.core.InstanciaEscenario;
import pe.pucp.paqrap.planner.core.Planificador;
import pe.pucp.paqrap.planner.core.TipoEscenario;
import pe.pucp.paqrap.planner.core.ValidadorFactibilidad;
import pe.pucp.paqrap.planner.hgs.HGS_PAQRAP;
import pe.pucp.paqrap.planner.model.Incidencia;
import pe.pucp.paqrap.planner.model.PlanDistribucion;

/**
 * Banco de pruebas del Informe de Experimentacion Numerica (IEN).
 *
 * <p>Para cada escenario, replica y algoritmo se genera la misma instancia (la
 * semilla determina red, flota, pedidos e incidencias) y se ejecutan dos fases:</p>
 * <ol>
 *   <li><b>planificacion</b>: construccion del plan del turno, que mide costo
 *       total de operacion, distancia recorrida y cumplimiento del SLA;</li>
 *   <li><b>reoptimizacion</b>: bloqueos de calles y fallas mecanicas inyectados
 *       sobre el plan vigente, que mide el tiempo de respuesta ante eventos
 *       disruptivos y la degradacion de la calidad de la solucion.</li>
 * </ol>
 *
 * <p>Uso: {@code java pe.pucp.paqrap.planner.experimento.RunnerExperimentos
 * [--algoritmos=ALNS,HGS] [--replicas=N]
 * [--escenarios=TIEMPO_REAL,SIMULACION_5D,COLAPSO] [--salida=ruta.csv]
 * [clave=valor ...]}; cada par clave=valor sobrescribe un parametro de
 * alns.properties, lo que habilita el barrido factorial sin recompilar.</p>
 */
public final class RunnerExperimentos {

    public static void main(String[] args) {
        Configuracion base = Configuracion.porDefecto();
        int replicas = 3;
        Path salida = Path.of("resultados", "metricas.csv");
        List<TipoEscenario> escenarios = new ArrayList<>(List.of(
                TipoEscenario.TIEMPO_REAL, TipoEscenario.SIMULACION_5D, TipoEscenario.COLAPSO));
        List<String> algoritmos = new ArrayList<>(List.of("ALNS", "HGS"));
        List<String> sobrescritos = new ArrayList<>();

        for (String arg : args) {
            if (arg.startsWith("--replicas=")) {
                replicas = Integer.parseInt(arg.substring(11));
            } else if (arg.startsWith("--salida=")) {
                salida = Path.of(arg.substring(9));
            } else if (arg.startsWith("--escenarios=")) {
                escenarios.clear();
                for (String e : arg.substring(13).split(",")) {
                    escenarios.add(TipoEscenario.valueOf(e.trim()));
                }
            } else if (arg.startsWith("--algoritmos=")) {
                algoritmos.clear();
                for (String a : arg.substring(13).split(",")) {
                    algoritmos.add(a.trim().toUpperCase(Locale.ROOT));
                }
            } else if (arg.contains("=")) {
                int i = arg.indexOf('=');
                base.con(arg.substring(0, i), arg.substring(i + 1));
                sobrescritos.add(arg);
            }
        }
        String etiquetaParametros = sobrescritos.isEmpty() ? "base" : String.join("|", sobrescritos);

        RegistroMetricas registro = new RegistroMetricas();
        System.out.println("=== RUTAPAQ / PaqRap - Experimentacion numerica del planificador ===");
        System.out.printf(Locale.US, "Algoritmos: %s   Replicas por escenario: %d   Parametros: %s%n",
                algoritmos, replicas, etiquetaParametros);
        System.out.println();
        System.out.printf(Locale.US, "%-14s %-5s %-15s %3s %6s %11s %10s %7s %6s %7s %7s%n",
                "ESCENARIO", "ALG", "FASE", "REP", "RUTAS", "COSTO S/", "DIST km", "SLA%", "NOATN", "ITER", "ms");

        for (TipoEscenario escenario : escenarios) {
            for (int replica = 1; replica <= replicas; replica++) {
                for (String algoritmo : algoritmos) {
                    ejecutarReplica(base, escenario, replica, algoritmo, registro, etiquetaParametros);
                }
            }
        }

        registro.exportar(salida);
        System.out.println();
        System.out.println("Metricas exportadas a " + salida.toAbsolutePath());
    }

    private static Planificador crear(String algoritmo, Configuracion cfg, ValidadorFactibilidad v, long semilla) {
        return switch (algoritmo) {
            case "ALNS" -> new ALNS_PAQRAP(cfg, v, semilla);
            case "HGS", "GA", "AG" -> new HGS_PAQRAP(cfg, v, semilla);
            default -> throw new IllegalArgumentException("Algoritmo desconocido: " + algoritmo);
        };
    }

    private static void ejecutarReplica(Configuracion base, TipoEscenario escenario, int replica,
                                        String algoritmo, RegistroMetricas registro, String etiquetaParametros) {
        long semilla = base.semilla() + replica;
        Configuracion cfg = base.copia()
                .con("escenario.tipo", escenario)
                .con("escenario.semilla", semilla)
                .con("escenario.maxIteraciones",
                        base.entero("escenario." + escenario + ".maxIteraciones", base.maxIteraciones()))
                .con("escenario.limiteTiempoSegundos",
                        base.numero("escenario." + escenario + ".limiteTiempoSegundos", base.limiteTiempoSegundos()));

        GeneradorInstancias generador = new GeneradorInstancias(cfg, semilla);
        GeneradorInstancias.Instancia instancia = generador.generar(escenario);
        ContextoPlanificacion ctx = instancia.contexto();
        ValidadorFactibilidad validador = new ValidadorFactibilidad(cfg);
        Planificador planificador = crear(algoritmo, cfg, validador, semilla);

        // Fase 1: planificacion del turno.
        PlanDistribucion plan = planificador.resolver(new InstanciaEscenario(
                escenario + "-" + replica, ctx, instancia.pedidos(), List.of(), null));
        reportar(registro, escenario, planificador, "planificacion", replica, semilla, instancia, ctx, plan,
                etiquetaParametros);
        auditar(plan, ctx, validador, instancia, escenario, planificador, "planificacion", replica);

        // Fase 2: reoptimizacion rodante ante bloqueos de calles y fallas mecanicas.
        ctx.setInstante(ctx.getInstante().plusMinutes(cfg.entero("experimento.minutosHastaIncidencia", 90)));
        cfg.con("escenario.maxIteraciones",
                        base.entero("escenario." + escenario + ".maxIteracionesReoptimizacion", 5000))
           .con("escenario.limiteTiempoSegundos",
                        base.numero("escenario." + escenario + ".limiteTiempoSegundosReoptimizacion", 3.0));
        List<Incidencia> incidencias = generador.generarIncidencias(ctx,
                cfg.entero("experimento.bloqueos", 3), cfg.entero("experimento.averias", 2), ctx.getInstante());
        PlanDistribucion replanificado = planificador.resolver(new InstanciaEscenario(
                escenario + "-" + replica + "-reopt", ctx, List.of(), incidencias, plan));
        reportar(registro, escenario, planificador, "reoptimizacion", replica, semilla, instancia, ctx,
                replanificado, etiquetaParametros);
        auditar(replanificado, ctx, validador, instancia, escenario, planificador, "reoptimizacion", replica);
    }

    private static void reportar(RegistroMetricas registro, TipoEscenario escenario, Planificador alg,
                                 String fase, int replica, long semilla, GeneradorInstancias.Instancia instancia,
                                 ContextoPlanificacion ctx, PlanDistribucion plan, String etiquetaParametros) {
        registro.registrar(escenario.name(), alg.nombre(), fase, replica, semilla,
                instancia.pedidos().size(), ctx.getFlota().size(), plan.getListaRutas().size(),
                plan.getCostoTotalGlobal(), plan.getDistanciaTotalGlobalKm(),
                plan.getIndicadorPuntualidadSLA(), plan.getPedidosNoAtendidos().size(),
                alg.iteracionesEjecutadas(), alg.tiempoUltimaCorridaMs(),
                plan.getSemaforoOperativo().name(), etiquetaParametros);
        System.out.printf(Locale.US, "%-14s %-5s %-15s %3d %6d %11.2f %10.2f %7.2f %6d %7d %7d%n",
                escenario, alg.nombre(), fase, replica, plan.getListaRutas().size(), plan.getCostoTotalGlobal(),
                plan.getDistanciaTotalGlobalKm(), 100.0 * plan.getIndicadorPuntualidadSLA(),
                plan.getPedidosNoAtendidos().size(), alg.iteracionesEjecutadas(), alg.tiempoUltimaCorridaMs());
    }

    private static void auditar(PlanDistribucion plan, ContextoPlanificacion ctx, ValidadorFactibilidad validador,
                                GeneradorInstancias.Instancia instancia, TipoEscenario escenario,
                                Planificador alg, String fase, int replica) {
        List<String> hallazgos = VerificadorPlan.verificar(plan, ctx, validador, instancia.pedidos());
        if (!hallazgos.isEmpty()) {
            System.out.println("  [VERIFICACION] " + escenario + "/" + alg.nombre() + "/" + fase + "/rep" + replica
                    + " - " + hallazgos.size() + " hallazgo(s):");
            hallazgos.stream().limit(5).forEach(h -> System.out.println("    - " + h));
        }
    }
}
