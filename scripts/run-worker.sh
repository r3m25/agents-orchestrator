#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
# Optional external configuration; never commit credentials.
if [ -n "${TECH_LEAD_ENV_FILE:-}" ]; then
  set -a
  . "$TECH_LEAD_ENV_FILE"
  set +a
fi
umask 077
if [ -n "${JAVA_HOME:-}" ]; then
  exec "$JAVA_HOME/bin/java" -jar build/libs/agents-orchestrator-0.1.0.jar
fi
exec java -jar build/libs/agents-orchestrator-0.1.0.jar
