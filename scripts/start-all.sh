#!/usr/bin/env bash
# Arranca mock (8090), WebFlux (8081) y Virtual Threads (8082) con los MISMOS flags de JVM.
#   ./scripts/start-all.sh            # modo benchmark (logs INFO)
#   PROFILE=demo ./scripts/start-all.sh   # modo charla (logs DEBUG + endpoints de demo)
#   EXTRA_ARGS="--banking.downstream.max-connections=20" ./scripts/start-all.sh   # args extra para ambas APIs
# Logs en ./logs/*.log. Para parar: ./scripts/stop-all.sh
set -euo pipefail
cd "$(dirname "$0")/.."

JVM_OPTS=${JVM_OPTS:-"-Xms512m -Xmx512m -XX:+UseG1GC"}
PROFILE=${PROFILE:-}
read -r -a extra_args <<< "${EXTRA_ARGS:-}"
VERSION=1.0.0-SNAPSHOT

if [[ ! -f banking-api-webflux/target/banking-api-webflux-$VERSION.jar || ! -f banking-api-virtual-threads/target/banking-api-virtual-threads-$VERSION.jar ]]; then
  echo ">> Empaquetando (mvn -DskipTests package)..."
  mvn -q -B -DskipTests package
fi

mkdir -p logs
profile_arg=()
[[ -n "$PROFILE" ]] && profile_arg=(--spring.profiles.active="$PROFILE")
api_args=("${profile_arg[@]}" "${extra_args[@]}")

start() { # nombre jar puerto [args]
  local name=$1 jar=$2 port=$3; shift 3
  nohup java $JVM_OPTS -jar "$jar" "$@" > "logs/$name.log" 2>&1 &
  echo "$!" > "logs/$name.pid"
  for _ in $(seq 1 60); do
    curl -s -o /dev/null "http://localhost:$port/actuator/health" && { echo "OK  $name en http://localhost:$port (pid $(cat "logs/$name.pid"))"; return; }
    sleep 1
  done
  echo "ERROR: $name no arrancó; revisa logs/$name.log"; exit 1
}

start downstream-mock-service downstream-mock-service/target/downstream-mock-service-$VERSION.jar 8090
start banking-api-webflux banking-api-webflux/target/banking-api-webflux-$VERSION.jar 8081 "${api_args[@]}"
start banking-api-virtual-threads banking-api-virtual-threads/target/banking-api-virtual-threads-$VERSION.jar 8082 "${api_args[@]}"
