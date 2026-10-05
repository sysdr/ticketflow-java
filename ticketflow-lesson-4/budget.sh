#!/usr/bin/env bash
# Usage: ./budget.sh [requests] [parallel] [paymentMs] [linkMode] [linkDelayMs]
#   linkMode: NONE | LATENCY
set -euo pipefail
N=${1:-100}; PAR=${2:-10}; PAY=${3:-120}; MODE=${4:-NONE}; DELAY=${5:-100}
curl -fsS -X POST "http://localhost:8081/lab/fault?mode=${MODE}&delayMs=${DELAY}&percent=100&bytesPerSec=2000"; echo
curl -fsS -X POST "http://localhost:8081/budget/run?requests=${N}&parallel=${PAR}&paymentMs=${PAY}"; echo
sleep 1
until curl -fsS http://localhost:8081/budget/state | grep -q '"state":"done"'; do sleep 1; done
curl -fsS http://localhost:8081/budget/state; echo
