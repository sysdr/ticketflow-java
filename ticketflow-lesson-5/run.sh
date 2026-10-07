#!/usr/bin/env bash
# Starts three boxes and the bench as four local JVMs.
#   box-small-1  :8081  1 core
#   box-small-2  :8082  1 core
#   box-big      :8083  4 cores   (override with BIG_CORES=2 ./run.sh on a small laptop)
#   bench        :9090  dashboard + load generator
set -euo pipefail
cd "$(dirname "$0")"

JAR=target/ticketflow.jar
BIG_CORES="${BIG_CORES:-4}"
[ -f "$JAR" ] || ./build.sh
mkdir -p logs
: > .pids

start() { # name port cores
  java -Xms128m -Xmx256m -jar "$JAR" \
    --server.port="$2" --ticketflow.box.node="$1" --ticketflow.box.cores="$3" \
    > "logs/$1.log" 2>&1 &
  echo $! >> .pids
}

start box-small-1 8081 1
start box-small-2 8082 1
start box-big     8083 "$BIG_CORES"
start bench       9090 1

for port in 8081 8082 8083 9090; do
  for attempt in $(seq 1 60); do
    if curl -fs "http://localhost:$port/api/box" > /dev/null 2>&1; then
      echo "up: $(curl -fs "http://localhost:$port/api/box")"
      continue 2
    fi
    sleep 1
  done
  echo "port $port did not come up; see logs/" >&2
  exit 1
done

echo
echo "Dashboard: http://localhost:9090"
echo "Logs:      logs/*.log     Stop: ./stop.sh"
