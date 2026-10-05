#!/usr/bin/env bash
# Usage: ./stampede.sh [buyers] [windowMs] [patienceMs]
set -euo pipefail
BUYERS=${1:-10000}; WINDOW=${2:-1000}; PATIENCE=${3:-2000}
curl -fsS -X POST "http://localhost:8081/start?buyers=${BUYERS}&windowMs=${WINDOW}&patienceMs=${PATIENCE}"; echo
until curl -fsS http://localhost:8081/state | grep -q '"state":"done"'; do sleep 1; done
curl -fsS http://localhost:8081/state; echo
