#!/usr/bin/env bash
# Ejecuta el mismo escenario contra ambas APIs, N repeticiones, alternando el orden en cada una
# (para que ninguna se beneficie sistemáticamente de ir primero/segunda) y con calentamiento previo.
#
#   ./load-tests/run-comparison.sh medium 3
#   SCENARIOS="low medium high" ./load-tests/run-comparison.sh all 2
#
# Requiere mock (8090), webflux (8081) y virtual-threads (8082) levantados SIN el perfil demo.
# Variables: K6_BIN (default k6), WEBFLUX_URL, VT_URL, MOCK_URL, WARMUP (default 30s, "0" para omitir).
set -euo pipefail

SCENARIO_ARG=${1:-medium}
REPETITIONS=${2:-3}
K6_BIN=${K6_BIN:-k6}
WEBFLUX_URL=${WEBFLUX_URL:-http://localhost:8081}
VT_URL=${VT_URL:-http://localhost:8082}
MOCK_URL=${MOCK_URL:-http://localhost:8090}
WARMUP=${WARMUP:-30s}
SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
RESULTS_DIR=${RESULTS_DIR:-$SCRIPT_DIR/results}
SESSION=$(date +%Y%m%d-%H%M%S)

if [[ "$SCENARIO_ARG" == "all" ]]; then
  read -r -a SCENARIO_LIST <<< "${SCENARIOS:-low medium high slow-downstream failing-downstream stress}"
else
  SCENARIO_LIST=("$SCENARIO_ARG")
fi

mkdir -p "$RESULTS_DIR/$SESSION"

run_k6() { # label url scenario run_id [duration]
  local extra=()
  [[ -n "${5:-}" ]] && extra=(-e "DURATION=$5")
  local rc=0
  "$K6_BIN" run -q \
    -e BASE_URL="$2" -e LABEL="$1" -e SCENARIO="$3" -e MOCK_URL="$MOCK_URL" \
    -e RESULTS_DIR="$RESULTS_DIR/$SESSION" -e RUN_ID="$4" "${extra[@]}" \
    "$SCRIPT_DIR/financial-summary.js" || rc=$?
  # 99 = algún threshold superado: es un resultado (p. ej. failing-downstream), no un fallo del runner.
  if [[ $rc -ne 0 && $rc -ne 99 ]]; then
    echo "k6 terminó con código $rc"; exit "$rc"
  fi
}

for scenario in "${SCENARIO_LIST[@]}"; do
  for ((rep = 1; rep <= REPETITIONS; rep++)); do
    if (( rep % 2 == 1 )); then order=(webflux virtual-threads); else order=(virtual-threads webflux); fi
    for label in "${order[@]}"; do
      url=$WEBFLUX_URL; [[ "$label" == "virtual-threads" ]] && url=$VT_URL
      if [[ "$WARMUP" != "0" ]]; then
        echo ">> calentamiento $label ($WARMUP)"
        run_k6 "$label" "$url" warmup "warmup-$scenario-r$rep" "$WARMUP" > /dev/null
      fi
      echo ">> $scenario | repetición $rep/$REPETITIONS | $label"
      run_k6 "$label" "$url" "$scenario" "r$rep"
      sleep 5   # deja que se cierren conexiones y se estabilice el GC
    done
  done
done

# Consolida todos los CSV (excepto calentamientos) en uno solo.
summary="$RESULTS_DIR/$SESSION/summary.csv"
first=$(ls "$RESULTS_DIR/$SESSION"/*.csv | grep -v -- '-warmup-' | grep -v summary.csv | head -1)
head -1 "$first" > "$summary"
for f in "$RESULTS_DIR/$SESSION"/*.csv; do
  [[ "$f" == "$summary" || "$f" == *-warmup-* ]] && continue
  tail -n +2 "$f" >> "$summary"
done
echo
echo "Resultados: $summary"
column -s, -t < "$summary" 2>/dev/null || cat "$summary"
