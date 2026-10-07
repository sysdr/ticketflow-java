#!/usr/bin/env bash
# Runs the three experiments back to back and prints the result table.
#   ./demo.sh           boxes started with ./run.sh
#   ./demo.sh docker    boxes started with docker compose up
set -euo pipefail

BENCH="${BENCH:-http://localhost:9090}"
if [ "${1:-local}" = "docker" ]; then
  SMALL1=http://box-small-1:8080; SMALL2=http://box-small-2:8080; BIG=http://box-big:8080
else
  SMALL1=http://localhost:8081;  SMALL2=http://localhost:8082;  BIG=http://localhost:8083
fi
START="${START:-50}"; END="${END:-900}"; STEP="${STEP:-50}"; SECONDS_PER_STEP="${SECONDS_PER_STEP:-4}"

ramp() { # label, json array of targets
  echo "== $1"
  curl -fsS -X POST "$BENCH/api/bench/runs" -H 'Content-Type: application/json' \
    -d "{\"label\":\"$1\",\"targets\":$2,\"startRate\":$START,\"endRate\":$END,\"stepRate\":$STEP,\"stepSeconds\":$SECONDS_PER_STEP}" \
    > /dev/null
  while curl -fsS "$BENCH/api/bench/runs" | grep -q '"status":"RUNNING"'; do
    sleep 2
  done
}

curl -fsS -X DELETE "$BENCH/api/bench/runs"
ramp "1 small box"               "[\"$SMALL1\"]"
ramp "1 big box (scale up)"      "[\"$BIG\"]"
ramp "2 small boxes (scale out)" "[\"$SMALL1\",\"$SMALL2\"]"

echo
curl -fsS "$BENCH/api/bench/summary"
echo
echo "Dashboard: $BENCH"
