#!/usr/bin/env bash
# Compara byte a byte las respuestas (status + cuerpo) de ambas APIs para los mismos clientes.
# Requiere: mock (8090), webflux (8081) y virtual-threads (8082) levantados.
#   ./scripts/compare-contracts.sh
set -u
WEBFLUX=${WEBFLUX_URL:-http://localhost:8081}
VT=${VT_URL:-http://localhost:8082}

# C0001 éxito | C0404 no existe | C0500 saldo 500 | C0504 saldo timeout | C0599 movimientos 500 | X1 inválido
CLIENTS=(C0001 C4321 C0404 C0500 C0504 C0599 C0800 X1)
failures=0

for id in "${CLIENTS[@]}"; do
  path="/api/clients/$id/financial-summary"
  a=$(curl -s -w '\n%{http_code}' "$WEBFLUX$path")
  b=$(curl -s -w '\n%{http_code}' "$VT$path")
  status_a=$(tail -n1 <<<"$a"); body_a=$(sed '$d' <<<"$a")
  status_b=$(tail -n1 <<<"$b"); body_b=$(sed '$d' <<<"$b")
  if [[ "$status_a" == "$status_b" && "$body_a" == "$body_b" ]]; then
    printf 'OK    %-6s %s  %s\n' "$id" "$status_a" "${body_a:0:90}"
  else
    printf 'DIFF  %-6s webflux=%s vt=%s\n  webflux: %s\n  vt:      %s\n' "$id" "$status_a" "$status_b" "$body_a" "$body_b"
    failures=$((failures + 1))
  fi
done

[[ $failures -eq 0 ]] && echo "Contratos equivalentes." || { echo "$failures diferencias."; exit 1; }
