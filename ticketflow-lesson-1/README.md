# TicketFlow — Lesson 1: Why One Server Isn't Enough

Hands-On Distributed Systems, Module 1 (Foundations & First Node).
Stack: Java 25, Spring Boot 4, Maven.

TicketFlow today is one JVM, one Tomcat thread pool, and a counter standing in for the seat map
(real Venue/Seat/Hold/Booking objects arrive on Day 2). A built-in load generator fires 10,000 buyers
at 500 seats within one second, over real HTTP, and the dashboard shows where the single server breaks.

> **Honest status.** This project was written in an environment with no network and no Maven, so the full
> Spring Boot build and the tests have **not** been executed there. The application classes were
> type-checked against stand-ins for the Spring and SLF4J APIs and exercised once end to end, but
> `./build.sh` and `./test.sh` on your machine are the first real build. If the Spring Boot 4 version in
> `pom.xml` (4.0.0) is older than what you want, bump it; nothing else depends on the exact patch.

## What's in the box

| Port | What | Why a separate port |
|---|---|---|
| 8080 | `POST /api/bookings`, the server under test (Tomcat, 50 threads) | — |
| 8081 | Dashboard, `/state`, `/start`, `/reset` | Runs on its own threads so the page can't freeze behind the overload it is showing |

Workload model (all in `application.properties`): each booking holds a thread for `ticketflow.booking.work-ms`
(25 ms) — a stand-in for the blocking validation, payment, and database calls that arrive in later lessons —
and then claims a seat. 50 threads / 25 ms is a ceiling of about 2,000 bookings per second.

## Path A — Docker

Prerequisite: Docker with Compose.

```bash
docker compose up --build
```

Open http://localhost:8081, press **Fire the stampede**.

## Path B — No Docker

Prerequisites: JDK 25, Maven 3.9+.

```bash
./build.sh        # mvn package -> target/ticketflow.jar
./run.sh          # raises the open-file limit, then starts the server
```

Open http://localhost:8081, press **Fire the stampede**. Or from a second terminal:

```bash
./stampede.sh                 # 10000 buyers, 1000 ms window, 2000 ms patience
./stampede.sh 5000 1000 3000  # buyers, window ms, patience ms
```

## Test

```bash
./test.sh
```

`SeatInventoryTest` proves 10,000 concurrent claims yield exactly 500 distinct seats.
`StampedeIntegrationTest` starts the real server on port 18080 and runs 1,200 buyers through real HTTP:
every buyer is answered, exactly 500 seats sell, nothing is late.

## Verify

1. Dashboard: after the run, the teal "Threads at peak" card reads `50 / 50`, "Most buyers waiting at once"
   is in the thousands, and "Bookings finished after the buyer left" is above zero.
2. Seat grid shows exactly 500 teal dots; the "sold" counter reads 500 and never more.
3. Logs: pick a traced buyer (every 100th: `b-00100`, `b-00200`, ...) and follow one id through four components.

```bash
./run.sh | tee run.log        # in another terminal, fire the stampede
grep 'rid=b-00300 ' run.log
```

Expected shape (times and values vary):

```
STAMPEDE  rid=b-00300 event=send patienceMs=2000
EDGE      rid=b-00300 event=received buyerPatienceLeftMs=1190
BOOKING   rid=b-00300 event=work_start workMs=25
BOOKING   rid=b-00300 event=work_done sold=false lateByMs=0
STAMPEDE  rid=b-00300 event=response status=409 ms=1030
```

A buyer who waited too long shows `event=gave_up`, and if the server still did the work you will also see
`work_done ... lateByMs=` with a positive number: the booking finished after the buyer left. Every seat sold logs
`INVENTORY ... event=seat_sold`, whether or not that buyer is traced.

## Demo script (two minutes)

1. Press **Fire** with the defaults. Watch the orange line climb while the teal line sits at 50.
2. Note the "bookings in progress at the same moment" sentence above the chart: that is the gap you are watching.
3. Set buyers to 1,000 and fire again. The line barely forms: this is the same server, just inside its ceiling.
4. Set patience to 10000 and buyers back to 10000. Fewer buyers give up, but the line is just as long: patience changes who leaves, not how fast the server works.

## Notes on numbers

* The load generator runs in the same JVM and on the same machine as the server, over real localhost sockets,
  so a small laptop spends CPU on both sides. Exact figures differ per machine; the shape does not.
* 10,000 buyers means about 10,000 sockets on each side. `run.sh` and the compose file raise the open-file
  limit to 65,536. If your OS refuses, you will see buyers counted as "gave up" because connections failed.
  Lower the buyer count.
* On a very slow machine, almost nobody may get an answer within 2 seconds. That is a valid result; raise
  patience or lower buyers to see the same system inside its ceiling.

## Layout

```
src/main/java/dev/ticketflow/
  TicketFlowApplication.java
  booking/        BookingController (edge), BookingService (the slow part + claim)
  inventory/      SeatInventory (atomic counter standing in for the seat map)
  metrics/        ServerStats (what the server can see)
  loadgen/        Stampede (10,000 buyers over HTTP), StampedeRunner, Percentiles
  logging/        TraceSampler (which ids get a full trace)
  observability/  ObservabilityServer (dashboard + state on port 8081)
src/main/resources/dashboard/index.html
```

Later lessons keep this package layout and add to it.
