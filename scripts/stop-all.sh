#!/usr/bin/env bash
# Detiene los procesos arrancados con start-all.sh.
cd "$(dirname "$0")/.."
for pidfile in logs/*.pid; do
  [[ -f "$pidfile" ]] || continue
  pid=$(cat "$pidfile")
  kill "$pid" 2>/dev/null && echo "parado $(basename "$pidfile" .pid) (pid $pid)"
  rm -f "$pidfile"
done
