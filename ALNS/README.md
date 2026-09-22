# RUTAPAQ — Solución algorítmica 1: ALNS (Adaptive Large Neighborhood Search)

Componente **planificador** de PaqRap programado en Java 17, listo para la
experimentación numérica del **IEN – Informe de Diseño de Experimento**
(requisitos no funcionales (a) y (b) de la situación auténtica).

Curso 1INF54 · Grupo 4D · Paquete raíz `pe.pucp.paqrap.planner`.

---

## 1. Cómo ejecutarlo

Con Maven:

```bash
mvn -q clean test            # compila y ejecuta la batería de pruebas
mvn -q exec:java             # ejecuta el banco de experimentos completo
```

Sin Maven (sólo JDK 17+, el proyecto no tiene dependencias en tiempo de ejecución):

```bash
javac -d target/classes $(find src/main/java -name '*.java')
cp src/main/resources/alns.properties target/classes/
java -cp target/classes pe.pucp.paqrap.planner.experimento.RunnerExperimentos --replicas=3
```

Salida: tabla por consola y `resultados/alns-metricas.csv`.

Argumentos del runner:

| Argumento | Efecto |
|---|---|
| `--replicas=N` | número de réplicas por escenario (semilla = base + réplica) |
| `--escenarios=TIEMPO_REAL,SIMULACION_5D,COLAPSO` | subconjunto de escenarios |
| `--salida=ruta.csv` | archivo de métricas |
| `clave=valor` | sobrescribe cualquier parámetro de `alns.properties` |

Ejemplo de corrida factorial para el IEN (un punto del diseño):

```bash
java -cp target/classes pe.pucp.paqrap.planner.experimento.RunnerExperimentos \
     --replicas=10 alns.qMin=0.25 alns.qMax=0.45 alns.kRegret=4 alns.alfa=0.99 \
     --salida=resultados/corrida-A.csv
```

La columna `parametros` del CSV registra los valores sobrescritos, de modo que
cada fila queda trazable con su punto del diseño experimental.

---

## 2. Estructura del código

```
pe.pucp.paqrap.planner
├── model/           Dominio: NodoRed, Arco, Almacen, Pedido, Vehiculo,
│                    TurnoConductor, Incidencia, VisitaCliente, Ruta,
│                    PlanDistribucion, TransferenciaCarga
├── core/            RedVial (Haversine + Dijkstra sobre calles de doble sentido),
│                    Configuracion, ContextoPlanificacion, InstanciaEscenario,
│                    ValidadorFactibilidad (R1–R7), Planificador (interfaz común)
├── alns/            EstadoALNS, Insercion, RuletaAdaptativa, ManejadorIncidencias,
│   │                ALNS_PAQRAP (procedimiento principal)
│   ├── destroy/     RemocionAleatoria, RemocionPeorCosto,
│   │                RemocionRelacionadaShaw, RemocionDeRuta
│   └── repair/      InsercionCostoMinimo, InsercionRegretK
└── experimento/     GeneradorInstancias, RegistroMetricas,
                     VerificadorPlan, RunnerExperimentos
```

La interfaz `Planificador` es el punto de intercambio con el **HGS/GA**: basta
implementarla en la clase del algoritmo genético para que el mismo runner, el
mismo generador de instancias y las mismas métricas evalúen ambas soluciones
algorítmicas en igualdad de condiciones.

---

## 3. Función objetivo y criterio de aceptación

**f(s) = Σ distancia(r) · costoPorKm(tipo(r)) + ω · Σ μ(p)**, con:

- `costoPorKm` = S/ 8.00 auto, S/ 6.00 moto, S/ 3.00 bicicleta;
- `ω` = `alns.omegaUrgencia` (10 000 S/), penalización por pedido no atendido;
- `μ(p)` = 5 para ventanas de 4 y 8 h, 3 para 12 y 18 h, 1 para 36 h.

El plazo comprometido es **restricción dura**: ninguna solución factible entrega
fuera de su ventana, por lo que la penalización de urgencia actúa sobre la
*cobertura* y no sobre holguras negativas. Una candidata que deja más pedidos sin
atender que la solución actual se descarta antes de comparar costos, de modo que
el recocido nunca intercambia cobertura por ahorro en kilómetros.

Aceptación por recocido simulado: `exp(−Δ / T)` con `T ← α^(1/tamañoSegmento) · T`
y `T₀ = 0.05 · f(s₀)`, independiente de la escala de la instancia.

---

## 4. Trazabilidad con `21.dis.selec.algoritmos.v03`

| Documento (v03) | Implementación |
|---|---|
| Algoritmo 1 — `ALNS_PAQRAP_OPTIMIZAR` | `ALNS_PAQRAP.optimizar(...)` |
| Algoritmo 2 — `Destroy_WorstCostRemoval` | `destroy.RemocionPeorCosto` |
| Algoritmo 2 — `Destroy_TimeWindowRelatedRemoval` | `destroy.RemocionRelacionadaShaw` |
| Algoritmo 2 — `Destroy_StreetBlockRemoval` | `ManejadorIncidencias.bloqueoCalle` |
| Algoritmo 2 — `Destroy_BreakdownRemoval` | `ManejadorIncidencias.fallaMecanica` |
| Algoritmo 3 — `Repair_CostMinimizing_Insertion` | `repair.InsercionCostoMinimo` |
| Algoritmo 3 — `Repair_RegretK_Insertion` | `repair.InsercionRegretK` |
| Algoritmo 4 — `ValidarFactibilidadRuta` | `core.ValidadorFactibilidad.evaluarRuta` |
| Tablas 4–13 — estructuras de datos | paquete `model` + `core.RedVial` |
| Tabla 15 — parámetros de calibración | `src/main/resources/alns.properties` |
| `intentarReasignacionEnTransito` | `EstadoALNS.intentarReasignacionEnTransito` |
| Ruleta adaptativa w(i,s+1) | `alns.RuletaAdaptativa` |

**Ajuste respecto de la v03 (a documentar en el IEN).** Los operadores dirigidos
por eventos (`StreetBlockRemoval`, `BreakdownRemoval`) no participan de la ruleta
adaptativa: se invocan al aplicar las incidencias, antes del bucle de búsqueda,
porque su probabilidad de producir un vecindario no vacío depende del evento y no
del desempeño histórico. La ruleta opera sobre cuatro operadores de propósito
general: `RandomRemoval`, `WorstCostRemoval`, `TimeWindowRelatedRemoval` y
`RouteRemoval` (este último, nuevo respecto de la v03, libera una unidad completa
y es el que permite reasignar carga entre tipos de vehículo).

---

## 5. Reglas de negocio implementadas

| Regla del enunciado | Mecanismo |
|---|---|
| Flota heterogénea 24/8/4 paquetes, 40/25/12 km/h, S/ 8.00/6.00/3.00 por km | `TipoVehiculo`; poda por capacidad antes del Δcosto; Δcosto por costoPorKm de la unidad |
| Almacén central de inventario infinito y 2 intermedios de 1 000 unidades con recarga instantánea a las 23:59:59 | `Almacen`, `EstadoALNS.consumoPorAlmacen`, R2 del validador |
| Tiempo de carga despreciable y 1 h de entrega en destino | `tiempoServicioHoras` = 0 en almacenes y depósitos temporales, 1.0 en clientes |
| Calles de doble sentido | `Arco` no dirigido; el bloqueo invalida ambos sentidos; Dijkstra bajo demanda |
| Turnos de 8 h (07:00, 15:00, 23:00) y 1 h de refrigerio con margen de 1 h | R6 y R7 del validador; `TurnoConductor` |
| Entregas regulares de 36 h y priorizadas de 4, 8, 12 y 18 h | R5 (restricción dura); banco ordenado por fecha límite; factor μ(p) en el regret |
| Bloqueos de vías y averías con reasignación de carga en tránsito | `ManejadorIncidencias`, depósitos temporales, `intentarReasignacionEnTransito` |
| Pedidos que exceden la capacidad del auto | `GeneradorInstancias.fraccionar` (subpedidos con `idPedidoPadre`) |
| Semáforo verde / ámbar / rojo con rangos configurables | `EstadoALNS.semaforo`, claves `semaforo.*` |
| Presupuesto de cómputo parametrizable e interrumpible | `escenario.maxIteraciones` y `escenario.limiteTiempoSegundos`; siempre retorna la incumbente |

---

## 6. Supuestos del modelo (declararlos en el IEN)

1. **Una ruta por unidad y turno.** No se modelan viajes múltiples con recarga
   intermedia dentro del mismo turno; ampliarlo exigiría insertar retornos al
   almacén como nodos de la secuencia.
2. **Sin retorno obligatorio al almacén.** La ruta concluye en el último cliente;
   `escenario.retornoAlmacen=true` activa el retorno y lo suma al costo.
3. **Refrigerio determinista.** Se coloca en el primer instante admisible a partir
   de `horaInicio + 1 h`; debe terminar a más tardar en `horaFin − 1 h`.
4. **Red vial sintética.** Malla de 10×10 nodos con calles de 0.8 km sobre
   coordenadas de Lima. Al sustituirla por la red real sólo cambia el cargador de
   instancias: `RedVial` ya opera sobre un grafo arbitrario.
5. **Velocidades promedio constantes** (40/25/12 km/h), sin congestión horaria.
6. **Reasignación en tránsito entre clientes** de la misma unidad: el producto “P”
   tiene presentación única, por lo que los paquetes son intercambiables; se exige
   holgura estrictamente mayor en el pedido donante para garantizar terminación.

---

## 7. Variables de respuesta que exporta el banco de pruebas

`resultados/alns-metricas.csv`:

`escenario, algoritmo, fase, replica, semilla, pedidos, vehiculos, rutas,
costoSoles, distanciaKm, pctSLA, noAtendidos, iteraciones, tiempoMs, semaforo, parametros`

Cada réplica ejecuta dos fases sobre la misma instancia:

- **planificacion** — construcción del plan del turno (costo, distancia, SLA);
- **reoptimizacion** — 3 bloqueos de calles y 2 averías inyectados 90 min después,
  con horizonte de congelamiento activo (mide el **tiempo de respuesta por
  reoptimización** y la degradación de la solución).

El escenario `COLAPSO` satura deliberadamente la flota: su variable de interés es
`noAtendidos` (tasa de resolución ante saturación / señal de colapso logístico).

`VerificadorPlan` audita cada plan de forma independiente al algoritmo —
factibilidad de cada ruta, ausencia de pedidos duplicados o extraviados, saldo de
almacenes— y reporta hallazgos por consola: una mejora de costo sobre un plan
inválido no pasa inadvertida.

---

## 8. Estado de verificación

- 9 pruebas (R1, R2, R3, R5, R6/R7, factibilidad del plan, reproducibilidad,
  reoptimización ante incidencias y fraccionamiento de pedidos): **todas correctas**.
- Corridas de los tres escenarios sin hallazgos del verificador.
- Reproducibilidad confirmada: dos corridas con la misma semilla producen costo,
  distancia, SLA y número de rutas idénticos.
