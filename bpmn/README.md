# BPMN

`agents-orchestrator.bpmn` es la fuente de verdad del proceso. Gradle lo empaqueta como
`classpath:/bpmn/agents-orchestrator.bpmn` y la anotación `@Deployment` lo despliega al iniciar el worker.

No mantener una segunda copia del BPMN: el build genera el recurso dentro del JAR para evitar divergencias.
