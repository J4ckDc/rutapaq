package pe.pucp.paqrap.planner.hgs;

import pe.pucp.paqrap.planner.core.Configuracion;

/** Parametros de calibracion del HGS/GA, leidos de las claves hgs.* de planner.properties. */
record ParametrosHGS(int tamanoPoblacion,
                     int lambdaOffspring,
                     double pc,
                     double pm,
                     double pEducacion,
                     int maxGeneraciones,
                     int genSinMejora,
                     double omegaSLA,
                     double omegaTurno,
                     double objetivoFactibles,
                     int periodoAjuste,
                     int nElite,
                     int nCercanos,
                     int loteParalelo,
                     int maxPasadasEducacion,
                     int maxEvaluacionesEducacion,
                     int maxParadasSplit,
                     double fraccionTiempoInicial,
                     double factorReparacion) {

    static ParametrosHGS desde(Configuracion c) {
        return new ParametrosHGS(
                c.entero("hgs.tamanoPoblacion", 25),
                c.entero("hgs.lambdaOffspring", 15),
                c.numero("hgs.pc", 0.85),
                c.numero("hgs.pm", 0.10),
                c.numero("hgs.pEducacion", 1.0),
                c.entero("hgs.maxGeneraciones", 1_000_000),
                c.entero("hgs.genSinMejora", 400),
                c.numero("hgs.omegaSLA", 500.0),
                c.numero("hgs.omegaTurno", 800.0),
                c.numero("hgs.objetivoFactibles", 0.20),
                c.entero("hgs.periodoAjuste", 50),
                c.entero("hgs.nElite", 4),
                c.entero("hgs.nCercanos", 5),
                c.entero("hgs.loteParalelo", 8),
                c.entero("hgs.maxPasadasEducacion", 8),
                c.entero("hgs.maxEvaluacionesEducacion", 20000),
                c.entero("hgs.maxParadasSplit", 40),
                c.numero("hgs.fraccionTiempoInicial", 0.30),
                c.numero("hgs.factorReparacion", 10.0));
    }
}
