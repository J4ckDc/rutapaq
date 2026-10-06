package pe.pucp.paqrap.app.sim;

import pe.pucp.paqrap.planner.alns.ALNS_PAQRAP;
import pe.pucp.paqrap.planner.core.Configuracion;
import pe.pucp.paqrap.planner.core.Planificador;
import pe.pucp.paqrap.planner.hgs.HGS_PAQRAP;

/** Crea el algoritmo de rutapaq-planner indicado en planificador.algoritmo (ALNS o GA). */
public final class Planificadores {
    private Planificadores() {
    }

    public static Planificador crear(Configuracion cfg) {
        String a = cfg.texto("planificador.algoritmo", "ALNS").trim().toUpperCase();
        return switch (a) {
            case "ALNS" -> new ALNS_PAQRAP(cfg, cfg.semilla());
            case "GA", "HGS" -> new HGS_PAQRAP(cfg, cfg.semilla());
            default -> throw new IllegalArgumentException("planificador.algoritmo debe ser ALNS o GA: " + a);
        };
    }
}
