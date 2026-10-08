#!/usr/bin/env bash
# Starts the whole lesson-6 system as five local JVMs.
#   balancer     :8080  load balancer in front of box-small-1 and box-small-2
#   box-small-1  :8081  1 core
#   box-small-2  :8082  1 core
#   box-big      :8083  4 cores   (lesson 5's scale-up box; BIG_CORES=2 ./run.sh on a small laptop)
#   bench        :9090  dashboard + load generator
set -euo pipefail
cd "$(dirname "$0")"

JAR=target/ticketflow.jar
BIG_CORES="${BIG_CORES:-4}"
[ -f "$JAR" ] || ./build.sh
mkdir -p logs
: > .pids

start() { # name port cores [extra args...]
  local name="$1" port="$2" cores="$3"
  shift 3
  java -Xms128m -Xmx256m -jar "$JAR" \
    --server.port="$port" --ticketflow.box.node="$name" --ticketflow.box.cores="$cores" "$@" \
    > "logs/$name.log" 2>&1 &
  echo $! >> .pids
}

start box-small-1 8081 1
start box-small-2 8082 1
start box-big     8083 "$BIG_CORES"
start balancer    8080 1 \
  '--ticketflow.balancer.backends[0].name=box-small-1' '--ticketflow.balancer.backends[0].url=http://localhost:8081' \
  '--ticketflow.balancer.backends[1].name=box-small-2' '--ticketflow.balancer.backends[1].url=http://localhost:8082'
start bench       9090 1

for port in 8081 8082 8083 8080 9090; do
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
echo "balancer: $(curl -fs http://localhost:8080/api/balancer)"
echo
echo "Dashboard: http://localhost:9090"
echo "Logs:      logs/*.log     Stop: ./stop.sh"
