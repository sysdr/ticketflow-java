# TicketFlow — Lesson 4: The Request Lifecycle and Latency Budgets

Hands-On Distributed Systems, Module 1 (Foundations & First Node).
Stack: Java 25, Spring Boot 4, Maven.

One booking, followed from the buyer's chair to the reply. `POST /api/book` now does what a buyer actually
experiences: **hold** the seat, **charge** the card, **confirm** the booking. Every hop is timed, the numbers travel back
in a standard `Server-Timing` response header, and a **budget** (300 ms in total, with a slice for each hop and a reserve
that belongs to nobody) says how much each hop is allowed to spend.

> **Honest status.** This project was written in an environment with no network and no Maven, so the full Spring Boot
> build and the JUnit tests have **not** been run there. The application classes were type-checked against stand-ins for
> the Spring and SLF4J APIs. The budget lab was then exercised over real sockets against a stand-in HTTP server that used
> the real `BookFlowController`: a healthy link stayed within budget, a slow link was blamed on the wire, a slow payment was
> blamed on payment, and the Lesson 2 and 3 checks still passed. `./build.sh` and `./test.sh` on your machine are the
> first real build. Spring Boot 4.0.0 is pinned in `pom.xml`.

## What's new since Lesson 3

| Where | What |
|---|---|
| `booking/BookFlowController` | `POST /api/book?seat=A-01&buyer=sam`: hold, charge, confirm. Replies with a `Server-Timing` header |
| `booking/PaymentGateway` | Stand-in for the payment provider: blocks the calling thread for `ticketflow.payment.latency-ms` (120 ms). It becomes a real service in Module 3 |
| `trace/RequestTrace`, `TraceContext` | Per-request stopwatch for each hop. Durations only, never timestamps |
| `trace/Budget` | The budget: total, one slice per hop, and a reserve. Must add up |
| `trace/BudgetLab` | Books many seats through the Lesson 3 flaky link, times each one from the client's chair, and compares with the server's numbers |
| `VenueService` | Lock wait ("lock") and work inside the lock ("domain") are now reported to the current trace. Behavior is unchanged |
| Dashboard | http://localhost:8081/latency-budget |

The hops, in order:

| Hop | What it covers | Budget |
|---|---|---|
| Wire + queue | Everything the server can't see: connection, network both ways, waiting for a free thread. Computed as the client's total minus the server's reported hops | 60 ms |
| Lock | Waiting for the venue lock | 10 ms |
| Domain | Hold and confirm work inside the lock | 15 ms |
| Payment | The charge (a blocking wait on the stand-in provider) | 150 ms |
| Render | Building the reply | 5 ms |
| Reserve | Belongs to nobody | 60 ms |

Change any of it in `application.properties` (`ticketflow.budget.*`). The slices plus the reserve must equal `total-ms`; the
dashboard flags a budget that doesn't balance.

The request carries `X-Budget-Ms` (the total budget). The server subtracts only its own measured time and reports `left`.
That number is always optimistic: time spent before the request arrived is invisible to the server.

## Path A — Docker

```bash
docker compose up --build
```

Open http://localhost:8081/latency-budget.

## Path B — No Docker

Prerequisites: JDK 25, Maven 3.9+.

```bash
./build.sh
./run.sh
```

Open http://localhost:8081/latency-budget. Or from a second terminal:

```bash
./budget.sh                          # 100 bookings, 10 at a time, payment 120 ms, healthy link
./budget.sh 100 10 120 LATENCY 100   # add 100 ms each way on the link
./budget.sh 100 10 400               # slow payment provider
./budget.sh 200 200 120              # 200 buyers at once
```

You can also call one booking by hand and read the header:

```bash
curl -i -X POST 'localhost:8080/api/book?seat=A-01&buyer=sam' -H 'X-Request-Id: demo-1' -H 'X-Budget-Ms: 300'
# Server-Timing: lock;dur=0.01, domain;dur=0.80, payment;dur=120.30, render;dur=0.05, left;dur=178.40;desc="budget left"
```

## Test

```bash
./test.sh
```

`BudgetTest` checks the budget arithmetic and the `Server-Timing` parser. `RequestTraceTest` checks that nested venue calls are
counted once. `LatencyBudgetIntegrationTest` starts the real server (ports 18100-18102) and checks three runs: healthy and within
budget, a slow link blamed on the wire, a slow payment blamed on payment. Earlier lessons' tests still pass.

## Verify and demo

1. Open the dashboard and press **Run the bookings** with the defaults. The verdict says all or nearly all bookings finished within
   300 ms, and the payment row shows about 120 ms against a 150 ms slice.
2. Set **Link** to *Slow link*, delay 100, and run again. Payment, lock and work look the same as before, but **Wire + queue**
   jumps by about 200 ms and becomes the hop that blows its slice. The server's own numbers never changed.
3. Back to *Healthy*, set **Payment takes** to 400 and run. Now payment is blamed, and nothing finishes within budget.
4. Healthy link, payment 120, set **At the same time** to 200. The wire hop grows as buyers wait for a free thread, with no change in what
   any hop does.
5. Follow one booking through every component by its id:

```bash
./run.sh | tee run.log
grep 'rid=trc-0007 ' run.log
```

```
LAB      rid=trc-0007 event=send seat=A-07 budgetMs=300
PROXY    rid=trc-0007 event=request_seen bytes=... fault=NONE
EDGE     rid=trc-0007 event=booking_requested seat=A-07 buyer=trc-007 budgetMs=300
VENUE    rid=trc-0007 event=hold_placed hold=h-0007 seat=A-07 buyer=trc-007 expiresAt=...
EDGE     rid=trc-0007 event=hop_done hop=hold lockMs=0.01 domainMs=0.62 paymentMs=0.00 renderMs=0.00 budgetLeftMs=298.9
PAYMENT  rid=trc-0007 event=charge_start hold=h-0007 buyer=trc-007 latencyMs=120
PAYMENT  rid=trc-0007 event=charge_ok hold=h-0007
EDGE     rid=trc-0007 event=hop_done hop=payment lockMs=0.01 domainMs=0.62 paymentMs=120.41 renderMs=0.00 budgetLeftMs=178.4
VENUE    rid=trc-0007 event=booking_confirmed booking=b-0007 hold=h-0007 seat=A-07 buyer=trc-007
EDGE     rid=trc-0007 event=booking_done booking=b-0007 hold=h-0007 serverMs=121.3 ...
PROXY    rid=trc-0007 event=upstream_replied bytes=...
LAB      rid=trc-0007 event=outcome result=HTTP_201 totalMs=143.2 wireMs=21.9 serverMs=121.3 within=true worst=none
```

Read down the `budgetLeftMs` column: it shrinks as each hop spends its slice.

## Notes

* Each run starts from an empty venue and wipes box-office state. A few throwaway bookings run first so a cold JVM doesn't distort the numbers.
* Up to 500 bookings per run (one seat each). Timing numbers vary by machine, but the blame stays with the hop that is actually slow.
* The lab client is a raw socket, so its stopwatch is the client's: connect, request, and the whole reply.
* The Lesson 1 stampede and its `X-Deadline-Epoch-Ms` header are unchanged. That header is a clock timestamp, which only works when sender and receiver share a clock. This lesson's `X-Budget-Ms` is a duration instead.

## Layout

```
src/main/java/dev/ticketflow/
  trace/          RequestTrace, TraceContext, Budget, BudgetLab
  booking/        BookFlowController, PaymentGateway, HoldController, BookingController
  lab/ domain/ inventory/ metrics/ loadgen/ logging/ observability/   as in Lessons 1-3
src/main/resources/dashboard/   index.html, box-office.html, fallacy-lab.html, latency-budget.html
```
