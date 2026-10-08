#!/usr/bin/env bash
# Runs the lesson-7 experiments (and, with arguments, any others) back to back and prints the result table.
# Works the same whether the system was started with ./run.sh or docker compose,
# because each preset already knows where its boxes and balancer live.
#   ./demo.sh                      the four lesson-7 presets
#   ./demo.sh slow-rr slow-lc      just these presets
set -euo pipefail

BENCH="${BENCH:-http://localhost:9090}"
PRESETS=("$@")
if [ ${#PRESETS[@]} -eq 0 ]; then
  PRESETS=(server-rr signed-rr server-sticky-restart signed-rr-restart)
fi

curl -fsS -X DELETE "$BENCH/api/bench/runs"
for preset in "${PRESETS[@]}"; do
  echo "== $preset"
  curl -fsS -X POST "$BENCH/api/bench/presets/$preset/run" > /dev/null
  while curl -fsS "$BENCH/api/bench/runs" | grep -q '"status":"RUNNING"'; do
    sleep 2
  done
done

echo
curl -fsS "$BENCH/api/bench/summary"
echo
echo "Dashboard: $BENCH"
