package pe.pucp.paqrap.planner.hgs;

import pe.pucp.paqrap.planner.core.Configuracion;

/** Parametros de calibracion del HGS/GA (Tabla 18), leidos de las claves hgs.* de alns.properties. */
record ParametrosHGS(int tamanoPoblacion,
                     int lambdaOffspring,
                     double pc,
                     double pm,
                     int vecinosGranulares,
                     double pEducacion,
                     int maxGeneraciones,
                     int genSinMejora,
                     double omegaSLA,
                     double omegaTurno,
                     double omegaStock,
                     double omegaNoAtendido,
                     double objetivoFactibles,
                     int periodoAjuste,
                     int nElite,
                     int nCercanos,
                     int loteParalelo,
                     int maxPasadasEducacion,
                     int maxParadasSplit,
                     double fraccionTiempoInicial,
                     double factorReparacion) {

    static ParametrosHGS desde(Configuracion c) {
        return new ParametrosHGS(
                c.entero("hgs.tamanoPoblacion", 100),
                c.entero("hgs.lambdaOffspring", 40),
                c.numero("hgs.pc", 0.85),
                c.numero("hgs.pm", 0.10),
                c.entero("hgs.vecinosGranulares", 20),
                c.numero("hgs.pEducacion", 1.0),
                c.entero("hgs.maxGeneraciones", 2000),
                c.entero("hgs.genSinMejora", 500),
                c.numero("hgs.omegaSLA", 500.0),
                c.numero("hgs.omegaTurno", 800.0),
                c.numero("hgs.omegaStock", 1000.0),
                c.numero("hgs.omegaNoAtendido", 50000.0),
                c.numero("hgs.objetivoFactibles", 0.20),
                c.entero("hgs.periodoAjuste", 100),
                c.entero("hgs.nElite", 4),
                c.entero("hgs.nCercanos", 5),
                c.entero("hgs.loteParalelo", 8),
                c.entero("hgs.maxPasadasEducacion", 30),
                c.entero("hgs.maxParadasSplit", 15),
                c.numero("hgs.fraccionTiempoInicial", 0.30),
                c.numero("hgs.factorReparacion", 10.0));
    }
}
