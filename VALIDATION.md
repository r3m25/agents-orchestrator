# Validación del MVP — 8 de noviembre de 2026

## Entorno comprobado

- macOS Apple Silicon; Java usado: Microsoft OpenJDK **21.0.13.0.2**.
- Gradle **9.3.1**; dependencias descargadas de Maven Central.
- Docker: `orchestration` con `camunda/camunda:8.10.9` y `connectors` con `camunda/connectors-bundle:8.10.5`, ambos saludables.
- Topología real: gateway/broker **8.10.9**, una partición activa y saludable.
- Codex CLI instalada: **0.167.0-alpha.3.2**. Se comprobaron sus opciones de sandbox, esquema de salida, modo efímero y ejecución sin configuración de usuario.
- Se verificó la compatibilidad publicada del starter Camunda 8.10 con Java 21 y Spring Boot 3.5, y se resolvió el artefacto `camunda-spring-boot-3-starter:8.10.9`.

## Automatización

Comando final de verificación, ejecutado desde la raíz del workspace con Java 21:

```sh
gradle -p agents-orchestrator test bootJar validateImpact \
  -PresultFile=.runtime/runs/2251799813768326.json --no-daemon
```

**29 pruebas, 0 fallas, 0 errores.** Se genera `build/libs/agents-orchestrator-0.1.0.jar`. El validador offline confirma `VALID_IMPACT_RESULT` sobre el resultado de una ejecución real.

Las pruebas cubren:

- JSON Schema, ticket y componentes, archivos/citas/revisiones inexistentes, dependencias desconocidas y ciclos.
- Bloqueos observados por el servicio que el modelo no puede ocultar; repositorio no inspeccionado representado como `affected: null`.
- Persistencia de resultado antes de completar el job, reutilización al redelivery y rechazo de salidas inválidas.
- Terminación de procesos por timeout y exceso de salida; limpieza de temporales de Codex.
- Adapter Codex con proceso **simulado explícitamente** para comprobar argumentos, contexto y entorno sin credenciales de integración. Estas pruebas no se presentan como consultas reales al proveedor.
- Adapter Git con repositorios temporales **reales**: lectura del commit, exclusión de secretos/cambios sin commit y rechazo de otra identidad Bitbucket.
- Worker con cliente Camunda **simulado explícitamente**: completar, descontar reintentos y generar incidente sin exponer texto sensible de excepciones.
- Formato ECS real con el contexto de correlación que Camunda añade a los logs. Se corrigió una colisión de claves detectada durante la prueba viva.
- Parseo y validación del BPMN con el modelo oficial Zeebe; identidad de proceso independiente y ausencia de actividad de creación de ramas.

Se revisaron referencias y wiring del alcance nuevo: no se encontraron componentes desconectados. El schema final es un contrato público documentado y consumido por `validateImpact`. La capa `application` no importa Spring ni JPA.

## Despliegue y ejecución reales

El despliegue REST del nuevo BPMN fue aceptado por **Camunda local**, sin desplegar el original ni alterar sus versiones:

- Deployment key: `2251799813767948`.
- Process definition ID: `implementar_feature_desde_tech_lead_agent_v1`.
- Process definition key: `2251799813767949`, versión **1**.

Se consultó Jira real utilizando las credenciales privadas del lector existente. La búsqueda devolvió **KIVNES-34**, “Permitir reserva manual durante una clase en curso a roles de gestión”. Se iniciaron dos instancias locales, sin modificar el ticket:

| Ejecución | Instancia Camunda | Job / runId | Resultado |
| --- | --- | --- | --- |
| Primera integración viva | 2251799813768003 | 2251799813768019 | COMPLETED; análisis BLOCKED; sin incidentes |
| Contrato final con impacto desconocido | 2251799813768310 | 2251799813768326 | COMPLETED; análisis BLOCKED; sin incidentes |

En ambas se ejecutó realmente la cadena:

1. `ticket.read` existente consultó Jira y devolvió `ticket`.
2. El worker Java verificó el perfil y las identidades Git disponibles.
3. Se invocó **Codex real**, con sandbox de lectura y contexto limitado, sin usar una respuesta prefabricada.
4. Se validó su salida, se persistió el resultado y se completó el job Camunda.
5. El gateway tomó el fin con bloqueos; Camunda conserva `impactAnalysis` completo.

El resultado final contiene **cinco tareas propuestas y seis criterios de aceptación**, hechos con citas, inferencias, riesgos, decisiones y aprobaciones pendientes. La salida usa `affected: null` donde no hay evidencia suficiente; no ejecuta las tareas propuestas.

Los snapshots reales disponibles fueron:

| Componente | Repositorio | Commit local analizado |
| --- | --- | --- |
| frontend | crossfit-app-web | 700b16850d4484dec4e0aea156905ed390673441 |
| mobile | crossfit-app-mobile | ef55e56ca1a693e7d35b0895df1ecb315f40e609 |
| backend | spring-boot-starter-backend | No inspeccionado: identidad/ruta sin confirmar |

Se enviaron diez archivos de cada clone verificado y el inventario acotado. No se afirma que esa muestra cubra todos los flujos.

Evidencia completa disponible localmente, excluida de Git:

- `.runtime/live-validation-final.json`: estado y análisis recuperados desde Camunda.
- `.runtime/runs/2251799813768326.json`: resultado persistido por el servicio y validado contra el schema final.
- `.runtime/worker.log`: arranque/eventos de la prueba; incluye el diagnóstico de colisión de logging que luego se corrigió y cubrió con una prueba de regresión.
- `build/reports/tests/test/index.html`: informe de las 29 pruebas.

Se detuvieron los workers temporales usados para validar. Camunda y sus contenedores permanecen activos; el nuevo BPMN permanece desplegado. Para nuevas ejecuciones iniciar los workers según el README.

## Bloqueos y límites que permanecen

**El sistema ejecuta correctamente la planificación; el análisis funcional está bloqueado.** El perfil original pide `spring-boot-starter-backend`, mientras que el clone backend disponible tiene origin `box-crossfit-app/crossfit-backend`. No se modificó el perfil ni se asumió equivalencia. Debe confirmarse/corregirse ese mapeo antes de una planificación completa.

El agente también señala que faltan archivos web pertinentes en la muestra y requiere aclarar la reactivación de reservas y la precedencia de roles combinados. Estas son conclusiones/propuestas del agente, sujetas a revisión humana, no errores de conectividad.

No se probaron como reales:

- API remoto/fetch de Bitbucket y frescura respecto de los remotos: solo se leen clones y commits locales verificados.
- Criterios Jira almacenados en campos personalizados, porque el lector existente no los consulta.
- Caídas reales de Jira/Codex ni el vencimiento real de los 15 minutos: se probaron límites/errores con dobles y el BPMN se validó/desplegó; no se afirma una prueba viva de esos escenarios.
- Desarrollo delegado, QA funcional, Jenkins, Playwright, simuladores, PRs, aprobaciones ejecutables, Bootstrap, MCP u otros motores.
- Exactly-once, ejecución distribuida y límite monetario estricto; ver los límites del MVP en README.

Postman CLI **1.86.0** está instalado, sin sesión iniciada ni recursos inicializados. Se entregó una colección local; no se publicaron recursos y no se atribuyen a Postman las pruebas realizadas por Gradle o la API de Camunda.

## Conservación del original

SHA-256 de `../implementar-feature-desde-ticket.bpmn`, idéntico antes y después del trabajo:

```text
94b1b54f108d9a951325c057df5f8c092afdb4c597200f808804f791548d4d6f
```

El repositorio tenía cambios previos del usuario, incluidos cambios de ese BPMN respecto de Git. Se conservaron y no se hizo commit, restore ni limpieza de trabajo ajeno.

## Actualización: decisiones en la respuesta de inicio

Se ajustaron el helper y la colección para que `POST /v2/process-instances` use `awaitCompletion: true`, `requestTimeout: 600000` y `fetchVariables: ["impactAnalysis"]`. El inicio devuelve ahora las decisiones del ticket dentro de `variables.impactAnalysis` en la misma respuesta, además de la clave de instancia. El modo anterior sigue disponible con `start --async`.

La comprobación real del nuevo contrato creó la instancia `2251799813768889`, runId `2251799813768905`, para KIVNES-34. Jira, Git y Codex se ejecutaron realmente; la respuesta HTTP incluyó el análisis completo con estado `BLOCKED`, decisiones, cinco tareas, agentes, dependencias y siguiente acción `REQUEST_INFORMATION`. La ejecución del worker duró aproximadamente 42 segundos. Se comprobó que las decisiones y demás colecciones estuvieran presentes y que `readOnly` fuera true. Evidencia: `.runtime/synchronous-response.json`.

El resultado propone aclarar reglas pendientes e inspeccionar el backend y consumidores; después, condicionado a desbloqueo y aprobación humana, plantea backend, web y QA. No se ejecutó desarrollo ni se presentaron esas propuestas como trabajo realizado.

**Cuatro pruebas adicionales del cliente Python aprobadas**, con transporte HTTP simulado explícitamente: respuesta síncrona con decisiones, modo asíncrono, finalización sin análisis y HTTP 504 sin iniciar otra instancia. Se ejecutan con:

```sh
python3 -m unittest discover -s scripts -p 'test_*.py'
```

La colección contiene aserciones equivalentes sobre `impactAnalysis`. Se intentó ejecutarla localmente con Postman y exportar su informe, pero la CLI exigió autenticación para `--output` antes de enviar peticiones. La validación viva se completó mediante el helper contra el mismo endpoint; no se atribuye esa ejecución a Postman.

Un timeout HTTP no cancela la instancia Camunda; no reintentar automáticamente el POST. El helper informa este caso como error y recomienda revisar Operate para evitar instancias duplicadas. Los workers temporales se detuvieron tras comprobar la respuesta.

## Corrección: worker independiente del proyecto

Se sustituyeron el grupo Gradle `cl.kivnes` y los paquetes `cl.kivnes.orchestrator` por `dev.orchestrator` y `dev.orchestrator.techlead`. El JAR reconstruido tiene `Start-Class: dev.orchestrator.techlead.AgentsOrchestratorApplication` y no contiene clases del paquete anterior.

El prompt ya no identifica al agente como KIVNES: el contexto incluye el `projectId` recibido del perfil seleccionado. El helper exige `--project` al iniciar y la colección exige configurar `project_id` y `ticket_key`, sin valores predeterminados de un proyecto. Se separaron la plantilla genérica `repository-paths.example.json` y el ejemplo explícito `repository-paths.kivnes.example.json`; se añadió el perfil de plantilla `examples/projects/example-project.json`.

Verificación posterior a la corrección: **31 pruebas Java y 6 pruebas Python aprobadas**, build limpio y JAR ejecutable generado. Las pruebas de carga Git y contexto Codex cubren perfiles `sample-project` y `parking-app`, con sus propios patrones de ticket; son fixtures de prueba, no nuevas integraciones externas verificadas. También se comprobó el arranque real del JAR con los workers desactivados para no ejecutar trabajos ni consumir Codex. Se validó offline un resultado histórico contra el schema de salida, que permanece compatible.

El proceso BPMN, sus identificadores y el contrato HTTP se mantienen. KIVNES permanece como un proyecto configurado y como evidencia histórica de las ejecuciones anteriores. No se modificaron su perfil externo, sus repositorios ni el BPMN original.

## Actualización: repositorios remotos configurables — 10 de noviembre de 2026

El modo predeterminado pasa a `REPOSITORY_MODE=remote`. Cada perfil configura proveedor (`bitbucket`, `github`, `gitlab` o `git`), URL Git y rama base, con posibilidad de distintos proveedores por componente. Los perfiles Bitbucket anteriores mantienen compatibilidad mediante workspace y nombre del repositorio. Los clones locales previamente mapeados quedan como modo offline explícito `local-snapshot`.

La planificación prepara un clone administrado, obtiene las referencias del remoto, hace checkout y pull de avance rápido de la base. Verifica identidad y SHA, y analiza ese commit. El código y las credenciales de las carpetas de desarrollo del usuario no se reutilizan implícitamente. La autenticación Git usa SSH o un credential helper privado, separado del entorno recibido por Codex.

También se implementó el caso de uso `PrepareWorkBranch`, accesible con la tarea Gradle `prepareWorkBranch`: crea una rama remota solo si falta, la reutiliza si existe y prepara su checkout local sincronizado. La publicación es de creación exclusiva mediante lease vacío, por lo que no sobrescribe una rama existente. Esta operación no está conectada automáticamente al BPMN de planificación; el desarrollo delegado y su aprobación continúan pendientes.

Validación final: **56 pruebas Java y 6 Python aprobadas**; `bootJar` generado y `validateImpact` confirma compatibilidad del contrato de salida con el resultado histórico. Las pruebas nuevas usan **Git real con remotos bare temporales**, sin credenciales ni escrituras en proveedores externos. Cubren:

- Clone y checkout de la rama base.
- Pull de un commit añadido al remoto después del primer análisis.
- Creación remota de una rama de trabajo y checkout local.
- Reutilización idempotente de ramas remotas existentes, conservando sus commits.
- Planificación sin crear ramas remotas.
- Aislamiento por proyecto y rechazo de identidad alterada.
- Conservación de archivos editados y commits locales; rechazo de divergencia.
- Fallo de sincronización al perder el remoto, sin presentar una copia vieja como actualizada.
- Limpieza de clones incompletos y rechazo de rama base inexistente.
- Lectura del contexto de planificación desde el commit obtenido del remoto.
- Nombres de repositorio con punto y ramas como `release/1.0`.
- Configuración de proveedores y rechazo de URLs con credenciales o protocolos no habilitados.

No se ha afirmado acceso real nuevo a Bitbucket/GitHub/GitLab: falta verificar para cada proyecto su URL y autenticación en el entorno de ejecución. No se crearon ramas en servicios externos durante estas pruebas. Las evidencias vivas del 8 de noviembre corresponden al modo anterior de snapshots locales y se conservan como historial.

Se revisó el wiring de los nuevos puertos/adapters y el comando de ramas: tienen consumidores y pruebas. La capa de aplicación sigue libre de Spring/JPA. El BPMN original conserva su SHA-256 y no se han modificado los perfiles externos ni los repositorios del usuario.

El arranque final con `REPOSITORY_MODE=remote` y workers desactivados se comprobó con Java 21, sin ejecutar jobs ni consumir el modelo. Se generó y revisó visualmente el diagrama `docs/orchestrator-flow-2026-11-10.png`, con el estado implementado y el alcance pendiente diferenciados; se conserva su prompt para futuras actualizaciones.
