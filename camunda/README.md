# Runtime local de Camunda

Desde la raíz de este proyecto, copiar `worker.env.example` a `worker.env`, completar las credenciales de Jira y ejecutar:

```sh
docker compose up --build
```

La primera vez, autenticar Codex dentro del contenedor:

```sh
docker compose exec worker codex login
```

El worker despliega automáticamente los BPMN incluidos en el JAR. El directorio `codex/` se ignora y conserva la sesión local del CLI; no guardar secretos en Git.
