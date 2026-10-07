# TicketFlow — Lesson 5 checkpoint: Vertical vs. Horizontal Scaling

This is the whole TicketFlow project as it stands at the end of lesson 5. It builds and runs on its own.

Today's addition: a load generator and dashboard that push the single-node TicketFlow until it falls over, then repeat the push against a bigger box and against two small boxes, so you can compare the three with numbers.

## What is in here

| From | Package | What it does |
|---|---|---|
| Lesson 1 | `model` | `RushModel`: the paper estimate of how far one box falls behind (`GET /api/model/rush`) |
| Lesson 2 | `domain`, `inventory` | `Venue`, `Seat`, `Hold`, `Booking`, and the in-memory `SeatMap` (500 seats, Riverside Hall) |
| Lesson 4 | `lifecycle` | Request id, per-hop `Server-Timing` header, 200 ms latency budget |
| **Lesson 5** | `scaling` | `CpuPool` (the size of the box) and `TicketSigner` (the CPU cost of one request) |
| **Lesson 5** | `bench` | Open-loop `LoadGenerator`, ramp runner, and the audit that finds seats sold by more than one box |
| **Lesson 5** | `static/index.html` | The dashboard |

Lesson 3 was a reading lesson and added no code.

One jar plays every part. Started on port 8081–8083 it is a box that sells seats. Started on port 9090 it is the bench: same code, but you only use its dashboard and load generator.

## Install

Pick one path.

- **Docker path:** Docker with Compose v2. Nothing else.
- **No-Docker path:** JDK 25 and Maven 3.9 or newer, plus `curl` and `bash`. A machine with 6 or more cores gives the cleanest curves, because four JVMs run at once.

## Build

Docker:

```bash
docker compose build
```

No Docker:

```bash
./build.sh            # mvn -DskipTests package  ->  target/ticketflow.jar
```

## Run

Docker:

```bash
docker compose up -d
docker compose ps     # four services: box-small-1, box-small-2, box-big, bench
```

No Docker:

```bash
./run.sh              # starts four JVMs, waits until each answers, prints their status
```

Either way you get:

| Process | URL | Size |
|---|---|---|
| box-small-1 | http://localhost:8081 | 1 core |
| box-small-2 | http://localhost:8082 | 1 core |
| box-big | http://localhost:8083 | 4 cores |
| bench | http://localhost:9090 | dashboard |

How "size" is enforced differs by path. With Docker, `cpus:` in `docker-compose.yml` is a hard CPU limit from the container runtime. Without Docker, the size is the number of threads in the box's `CpuPool`, so it is only honest if your machine has that many cores to give. On a 4-core laptop run `BIG_CORES=2 ./run.sh`.

## Test

Docker (runs the tests in a throwaway Maven container):

```bash
docker run --rm -v "$PWD":/src -w /src maven:3.9-eclipse-temurin-25 mvn -B test
```

No Docker:

```bash
./test.sh
```

Five test classes run. `TicketFlowApplicationTest` boots a real box on a free port, books a seat over HTTP, and ramps real load at it.

## Verify

1. Book one seat by hand and read the hop timings from lesson 4:

   ```bash
   curl -i -X POST localhost:8081/api/bookings -H 'Content-Type: application/json' \
     -d '{"venueId":"riverside","seatId":"R01-01","buyerId":"me"}'
   ```

   Expect `HTTP/1.1 201`, a `Server-Timing: queue;dur=…, sign;dur=…, reserve;dur=…` header, and `"node":"box-small-1"` in the body. `sign` should be close to 8 ms. Send it again and you get `409` with `SEAT_TAKEN`.

2. Ask a box how big it is:

   ```bash
   curl -s localhost:8083/api/box
   ```

   Expect `"cores":4` and `"inFlight":0`.

3. Compare with the lesson 1 estimate for one core:

   ```bash
   curl -s 'localhost:8081/api/model/rush?buyers=10000&windowSeconds=1&cpuMillis=8&cores=1'
   ```

   `capacityPerSecond` is 125. Remember that number.

## Demo

Open http://localhost:9090 and press the three buttons in order, waiting for each run to finish. Or run all three from a terminal:

```bash
./demo.sh             # no-Docker path
./demo.sh docker      # Docker path
```

The whole demo takes three to five minutes. What you should see:

- **1 small box** keeps up until roughly 100 buyers a second, then p99 latency leaves the 200 ms budget and climbs to the 2-second timeout. The run ends `FELL OVER`. Seats sold twice: 0.
- **1 big box** follows the same shape with the knee roughly four times further right. Seats sold twice: 0.
- **2 small boxes** have a knee roughly twice that of one small box. Bookings: close to 1,000 for 500 seats. Seats sold twice: close to 500.

Exact rates depend on your machine. The shapes and the sold-twice numbers do not.

Follow one buyer across two processes. The bench logs one request in every hundred with its id, and the box logs the same id:

```bash
# no Docker
grep -h 'run-1-100-000100' logs/*.log

# Docker
docker compose logs | grep 'run-1-100-000100'
```

```
event=buyer.sent request_id=run-1-100-000100 target=http://localhost:8081 seat=R07-12 offered_rps=100
event=booking.received request_id=run-1-100-000100 node=box-small-1 in_flight=3
event=booking.answered request_id=run-1-100-000100 node=box-small-1 status=409 queue_ms=14.2 sign_ms=8.1 reserve_ms=0.0 total_ms=22.9 budget_ms=200 budget=OK
event=buyer.answered request_id=run-1-100-000100 target=http://localhost:8081 outcome=SEAT_TAKEN latency_ms=24
```

The request id is `run number - buyers per second - sequence`. Pick one from a late step (for example `run-1-250-000400`) and watch `queue_ms` swallow the whole budget while `sign_ms` stays at 8.

A plain-text version of the dashboard, for terminals and CI:

```bash
curl -s localhost:9090/api/bench/summary
```

## Stop

```bash
docker compose down   # Docker
./stop.sh             # no Docker
```

## Settings worth knowing

All of these live in `src/main/resources/application.yml` and can be overridden on the command line (`--ticketflow.box.cores=2`) or by environment variable (`TICKETFLOW_BOX_CORES=2`).

| Setting | Default | Meaning |
|---|---|---|
| `ticketflow.box.node` | `box-a` | Name stamped on bookings and log lines |
| `ticketflow.box.cores` | `0` | Threads allowed to do CPU work at once; 0 means all the JVM can see |
| `ticketflow.signing.target-millis` | `8` | CPU cost of one signature; hash rounds are calibrated to this at startup |
| `ticketflow.signing.rounds` | `0` | Set above 0 to pin the rounds and skip calibration |
| `ticketflow.budget.total-millis` | `200` | Latency budget for one booking |
| `ticketflow.bench.timeout-millis` | `2000` | How long a simulated buyer waits |
| `ticketflow.bench.sample-every` | `100` | One request in this many is traced in the logs |

## Why the booking path signs before it reserves

`BookingService` signs the ticket offer first and only then tries to take the seat. That means a buyer who loses still costs 8 ms of CPU. It is deliberate: on a real on-sale almost all of the load comes from people who do not get a ticket, and a test in which sold-out answers are free would make any box look enormous.

## Troubleshooting

- **Every step is "over budget", even the first.** The machine is too busy to give each JVM its cores. Close other programs, or run with `BIG_CORES=2` and raise `ticketflow.signing.target-millis` to `16`.
- **The big box never falls over.** Raise the end rate on the dashboard (or `END=1500 ./demo.sh`).
- **`Port 8081 was already in use`.** Something from an earlier run is still up. Run `./stop.sh` or `docker compose down`.
- **A run says FAILED with `ConnectException`.** A target box is not running. Check `curl localhost:8081/api/box`.
