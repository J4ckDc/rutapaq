# RUTAPAQ

Sistema de planificación y monitoreo de rutas de reparto para PaqRap.
Curso 1INF54 Proyecto de Diseño y Desarrollo de Software, PUCP, Grupo 4D.

## Estructura del repositorio

| Carpeta | Contenido | Estado |
|---|---|---|
| rutapaq-planner/ | Biblioteca Java 17 (Maven) con los algoritmos ALNS y GA (HGS) y el banco de pruebas del IEN | v2.0.0 |
| rutapaq-server/ | Servidor de aplicación (Spring Boot 3) | Por desarrollar |
| rutapaq-web/ | Visualizador (React 18 + Vite) | Por desarrollar |

Los nombres de las carpetas siguen los artefactos definidos en el documento de arquitectura (24.dis.DS.ARQSOL.v02).

## Planificador

Las instrucciones de compilación, ejecución y pruebas están en rutapaq-planner/README.md.

## Versiones

| Etiqueta | Contenido |
|---|---|
| v1.0.0 | ALNS y GA en carpetas separadas (semana 6) |
| v2.0.0 | Planificador unificado, alineado al IEN v02 |

## Ramas

- main: versiones estables (líneas base).
- develop: integración.
- feature/*: trabajo en curso; se integra a develop mediante Pull Request revisado por otro integrante.

## Equipo

- Joseph Vilchez: Jefe de Proyecto y Gestor de la Configuración
- Augusto Varas: Líder Técnico del Planificador
- Ricardo Romero: Líder Técnico del Visualizador
- Jack De la Cruz: Líder de Simulación, Datos y Calidad
