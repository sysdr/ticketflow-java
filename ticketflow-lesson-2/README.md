# TicketFlow — Lesson 2 checkpoint: Modeling the Domain

**Hands-On Distributed Systems: Building TicketFlow** · Module 1, Day 2 · Java 25 LTS, Spring Boot 4

One Java process. Four objects — `Venue`, `Seat`, `Hold`, `Booking` — all in memory. Restart it and everything is gone; lesson 8 fixes that.

## What's in here

```
src/main/java/com/ticketflow/
  domain/          Venue, Seat, SeatStatus, Hold, Booking, DomainException   (plain Java, no Spring)
  service/         InventoryService, SeatView, EventSink, RequestIds         (plain Java, no Spring)
  observability/   RecordingEventSink, RequestIdFilter, EventRecord          (logs + request ids)
  config/          TicketFlowConfig, DemoClock                               (wiring, skippable clock)
  api/             InventoryController, ApiModels, ApiExceptionHandler       (REST)
src/main/resources/static/index.html                                         (the dashboard)
src/test/java/com/ticketflow/service/InventoryServiceTest.java
```

Later lessons keep this package layout. `domain` and `service` stay free of framework imports on purpose, so they carry over unchanged when TicketFlow gets split into services in Module 3.

**Deliberately unfinished:** `InventoryService.placeHold` reads a seat's status and then writes it, with nothing guarding the gap. One buyer at a time, it is correct. Many at once, it double-sells. Lesson 11 reproduces that on purpose, so do not "fix" it yet.

## Prerequisites

| Path | You need |
|---|---|
| No Docker | JDK 25, Maven 3.9+, `curl` |
| Docker | Docker 24+ (Compose v2 optional) |

## Path A — without Docker

```bash
# 1. Build + run the tests
./build.sh                    # same as: mvn clean verify

# 2. Run
./run.sh                      # same as: mvn spring-boot:run

# 3. Verify
curl -s localhost:8080/api/state | head -c 300
# open http://localhost:8080 for the dashboard

# 4. Demo the whole lifecycle from the terminal
./demo.sh
```

Just the tests: `./test.sh`.

## Path B — with Docker

```bash
# 1. Build (compiles and runs the tests inside the build stage)
docker build -t ticketflow:lesson-2 .

# 2. Run
docker run --rm -p 8080:8080 ticketflow:lesson-2
#   or: docker compose up --build

# 3. Verify and demo: identical to Path A from step 3 onward
curl -s localhost:8080/api/state | head -c 300
./demo.sh
```

## What to look at

**Dashboard (http://localhost:8080).**
1. Click a green seat. It turns orange and a countdown appears under *Buyer & holds*.
2. Press **Skip ahead 60 s** twice. The countdown passes zero and the seat turns green again. No timer ran; the next poll asked the seat, and the seat noticed its deadline was past.
3. Hold a seat and press **Confirm**. It turns blue and stays blue.
4. In *Event trace*, click any line. Every line sharing its request id lights up.

**Logs.** Each line starts with the request id in brackets. One hold request produces three lines that share it:

```
14:02:11.402 INFO  [r-3f9a01bc] rid=r-3f9a01bc component=api event=request.received method=POST path=/api/holds
14:02:11.403 INFO  [r-3f9a01bc] rid=r-3f9a01bc component=api event=hold.requested seat=C5 buyer=alice
14:02:11.404 INFO  [r-3f9a01bc] rid=r-3f9a01bc component=inventory event=hold.created hold=h-77d1e0aa seat=C5 buyer=alice ttlSeconds=120
14:02:11.405 INFO  [r-3f9a01bc] rid=r-3f9a01bc component=api event=request.completed status=201 ms=3
```

(Ids and timings differ on your machine; the shape is what matters.) Send your own id with `-H 'X-Request-Id: my-test'` and `grep my-test` the console.

## API

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/state` | Every seat, counts, and the invariant check |
| POST | `/api/holds` `{"seatId","buyerId"}` | Hold a seat → 201, or 409 if taken |
| POST | `/api/holds/{id}/confirm` | Turn a hold into a booking → 201, or 410 if expired |
| DELETE | `/api/holds/{id}` | Let go of a hold |
| GET | `/api/holds/{id}` | Hold status |
| GET | `/api/bookings` | All bookings |
| GET | `/api/events?after=N` | Recent events, as shown in the trace |
| POST | `/api/dev/clock/advance?seconds=N` | Demo only: push the clock forward |

## Settings (`application.properties`)

`ticketflow.hold-ttl-seconds` (120), `ticketflow.venue.rows` (5), `ticketflow.venue.seats-per-row` (10).

## Notes on this build

- The JSON models use epoch milliseconds, not date strings, so no Jackson date setting can change the output.
- The starter is `spring-boot-starter-webmvc`, the Spring Boot 4 name for the web starter.
- The demo clock only moves forward and exists for the dashboard; it is not a pattern to keep in production code.
