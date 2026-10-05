#!/usr/bin/env bash
# Usage: ./lab.sh [MODE] [requests] [patienceMs] [retry]
#   MODE: NONE | LATENCY | LOSE_REQUEST | LOSE_RESPONSE | THROTTLE   (default LOSE_RESPONSE)
set -euo pipefail
MODE=${1:-LOSE_RESPONSE}; N=${2:-8}; PATIENCE=${3:-800}; RETRY=${4:-true}
CALL=hold; [ "$MODE" = "THROTTLE" ] && CALL=map
curl -fsS -X POST "http://localhost:8081/lab/fault?mode=${MODE}&delayMs=400&percent=100&bytesPerSec=500"; echo
curl -fsS -X POST "http://localhost:8081/lab/run?call=${CALL}&requests=${N}&patienceMs=${PATIENCE}&retry=${RETRY}"; echo
until curl -fsS http://localhost:8081/lab/state | grep -q '"state":"done"'; do sleep 1; done
curl -fsS http://localhost:8081/lab/state; echo
