# TicketFlow — Lesson 7 checkpoint: Designing for Statelessness

This is the whole TicketFlow project as it stands at the end of lesson 7. It builds and runs on its own.

Today's addition: a two-step checkout (hold a seat, then pay for it) whose session no longer lives in the memory of the box that started it. The buyer carries the session as a signed token, so any box can finish the checkout. The old server-side session store stays in the code, switchable at runtime, so the dashboard can show both side by side. So does a sticky-sessions strategy on the balancer, the usual workaround, to show what a restart does to it.

## What is in here

| From | Package | What it does |
|---|---|---|
| Lesson 1 | `model` | `RushModel`: the paper estimate of how far one box falls behind (`GET /api/model/rush`) |
| Lesson 2 | `domain`, `inventory` | `Venue`, `Seat`, `Hold`, `Booking`, and the in-memory `SeatMap` (500 seats, Riverside Hall) |
| Lesson 4 | `lifecycle` | Request id, per-hop `Server-Timing` header, 200 ms latency budget |
| Lesson 5 | `scaling`, `bench` | Box size (`CpuPool`), request cost (`TicketSigner`), open-loop load generator, ramp runner |
| Lesson 6 | `balancer`, `faults` | `LoadBalancer` with round-robin and least-connections; `FaultSwitch` for slow and broken boxes |
| **Lesson 7** | `checkout` | `CheckoutService` (start, confirm), `SignedSessionTokens` (the default), `ServerSessionStore` (the comparison) |
| **Lesson 7** | `balancer` | `Sticky`: keeps a client on the box named in its `X-Route` header |
| **Lesson 7** | `bench` | Two-step buyers who hold, think for a second, then confirm; the `restart` fault; a "sessions lost" count |

Lesson 3 was a reading lesson and added no code.

## The checkout, in two calls

```text
POST /api/checkout/start      {"venueId":"riverside","seatId":"R07-12","buyerId":"ana"}
  -> 201 {"session":"djF8cml2ZXJzaWRl...","sessionKind":"signed","seatId":"R07-12","expiresAt":"...","node":"box-small-1"}

POST /api/checkout/confirm    header X-Checkout-Session: <the session from start>
  -> 201 the booking
  -> 401 SESSION_UNKNOWN    a server-side session this box never saw (or forgot in a restart)
  -> 401 SESSION_TAMPERED   a token whose signature does not match
  -> 401 SESSION_EXPIRED    the hold behind the session has run out
  -> 409 SEAT_TAKEN         this box's seat map already gave the seat to someone else
```

A signed session is `base64url(payload).base64url(HMAC-SHA256(payload))`, and the payload is readable: `v1|riverside|R07-12|ana|<expiry>|box-small-1`. The buyer can read it but cannot change it. Every box must share the signing key (`ticketflow.checkout.signing-key`).

The one-shot `POST /api/bookings` from earlier lessons is unchanged, and the lesson 5 and 6 experiments still use it.

## Install

Pick one path.

- **Docker path:** Docker with Compose v2. Nothing else.
- **No-Docker path:** JDK 25 and Maven 3.9 or newer, plus `curl` and `bash`. Five JVMs run at once; a machine with 6 or more cores gives the cleanest numbers.

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
docker compose ps     # five services: box-small-1, box-small-2, box-big, balancer, bench
```

No Docker:

```bash
./run.sh              # starts five JVMs, waits until each answers, prints their status
```

Either way you get:

| Process | URL | Role |
|---|---|---|
| balancer | http://localhost:8080 | load balancer for the two small boxes |
| box-small-1 | http://localhost:8081 | 1 core |
| box-small-2 | http://localhost:8082 | 1 core |
| box-big | http://localhost:8083 | 4 cores (lesson 5's scale-up box) |
| bench | http://localhost:9090 | dashboard and load generator |

## Test

Docker (runs the tests in a throwaway Maven container):

```bash
docker run --rm -v "$PWD":/src -w /src maven:3.9-eclipse-temurin-25 mvn -B test
```

No Docker:

```bash
./test.sh
```

`CheckoutServiceTest` builds two boxes in one test, each with its own seat map, and shows a server session failing on the other box while a signed one succeeds. `SignedSessionTokensTest` forges a token by editing the seat inside it and checks that it is refused.

## Verify

1. Start a checkout on one box and finish it on the other, by hand:

   ```bash
   SESSION=$(curl -s -X POST localhost:8081/api/checkout/start -H 'Content-Type: application/json' \
     -d '{"venueId":"riverside","seatId":"R07-12","buyerId":"ana"}' | sed 's/.*"session":"\([^"]*\)".*/\1/')
   curl -s -X POST localhost:8082/api/checkout/confirm -H "X-Checkout-Session: $SESSION"
   ```

   Expect a booking with `"node":"box-small-2"`: a box that never saw the start finished the checkout.

2. Read the token. It is signed, not secret:

   ```bash
   echo "${SESSION%%.*}" | tr '_-' '/+' | base64 -d 2>/dev/null; echo
   ```

   Expect `v1|riverside|R07-12|ana|<expiry>|box-small-1`.

3. Do the same with server sessions and watch it fail:

   ```bash
   curl -s -X POST localhost:8081/api/admin/checkout/server
   curl -s -X POST localhost:8082/api/admin/checkout/server
   SESSION=$(curl -s -X POST localhost:8081/api/checkout/start -H 'Content-Type: application/json' \
     -d '{"venueId":"riverside","seatId":"R08-01","buyerId":"ben"}' | sed 's/.*"session":"\([^"]*\)".*/\1/')
   curl -s -X POST localhost:8082/api/checkout/confirm -H "X-Checkout-Session: $SESSION"   # 401 SESSION_UNKNOWN
   curl -s -X POST localhost:8081/api/admin/checkout/signed
   curl -s -X POST localhost:8082/api/admin/checkout/signed
   ```

## Demo

Open http://localhost:9090. The first two tabs are today's lesson; run every button in each.

Or run all four from a terminal (about a minute and a half):

```bash
./demo.sh
```

What you should see. Exact numbers depend on your machine; the direction does not.

| Tab | Server sessions | Signed tokens |
|---|---|---|
| **Checkout behind round-robin** | Roughly half of the buyers who reach confirm get `SESSION_UNKNOWN`: their confirm landed on the other box. Expect around a third of all buyers failed | 0 sessions lost |
| **Sticky sessions vs. a restart** | Sticky routing keeps the losses near zero until box-small-2 restarts halfway through. Every buyer who was mid-checkout on it is lost in that instant (dozens) | 0 sessions lost, through the same restart |

"Sold twice" is still above zero in every run. The session moved out of memory; the seat map did not. When a confirm lands on a box whose seat map never saw the hold, that box places the hold itself, and the seat can end up booked on both boxes. That is tomorrow's lesson.

The `restart` fault wipes everything box-small-2 keeps in memory (sessions, holds and bookings) while leaving the process running, so a run can keep measuring through it. It is what a restart does to memory; it skips the few seconds a real restart spends starting up.

### Restart a box for real

Start the "Server sessions" run on the first tab and, while it runs, restart box-small-2 (`docker compose restart box-small-2`, or kill its PID from `.pids` and start it again with the same `java -jar` line `run.sh` uses). You will see `502` errors from the balancer while the box is down, in any mode, and lost sessions after it comes back, only in server mode.

### Follow one buyer through both steps

A two-step buyer has one id for the whole journey, with `.start` and `.confirm` on the two requests:

```bash
grep -h 'run-1-100-000100' logs/*.log          # no Docker
docker compose logs | grep 'run-1-100-000100'  # Docker
```

```
event=buyer.start request_id=run-1-100-000100.start target=http://localhost:8080/lb seat=R04-17 offered_rps=100
event=lb.forwarded request_id=run-1-100-000100.start strategy=round-robin backend=box-small-1 route_hint=- in_flight_before=...
event=booking.answered request_id=run-1-100-000100.start node=box-small-1 path=/api/checkout/start status=201 ...
event=buyer.holding request_id=run-1-100-000100.start session_kind=server route=box-small-1 think_ms=1000
event=lb.forwarded request_id=run-1-100-000100.confirm strategy=round-robin backend=box-small-2 route_hint=box-small-1 ...
event=booking.answered request_id=run-1-100-000100.confirm node=box-small-2 path=/api/checkout/confirm status=401 ...
event=buyer.answered request_id=run-1-100-000100 outcome=SESSION_LOST latency_ms=31
```

The `route_hint` shows the buyer asking to go back to box-small-1, and round-robin ignoring it. Run numbers depend on the order you press buttons; `curl -s localhost:9090/api/bench/summary` lists them.

## Stop

```bash
docker compose down   # Docker
./stop.sh             # no Docker
```

## Settings worth knowing

| Setting | Default | Meaning |
|---|---|---|
| `ticketflow.checkout.sessions` | `signed` | `signed` or `server`; switchable per box at `POST /api/admin/checkout/{kind}` |
| `ticketflow.checkout.signing-key` | a local dev key | HMAC key for signed sessions, at least 32 characters; every box must share it |
| `ticketflow.bench.think-millis` | `1000` | How long a two-step buyer waits between start and confirm |
| `ticketflow.balancer.strategy` | `round-robin` | Also `least-connections` or `sticky` |
| `topology.*` | localhost ports | Where each process lives; the `docker` profile overrides only this block |

## Production notes on the token

- The key is the whole security of the scheme. Anyone with it can mint sessions. Keep it out of the repository and in your secret store; the values in `application.yml` and `docker-compose.yml` are for this course only.
- A signed token cannot be withdrawn before it expires. That is acceptable here because it expires with the 2-minute hold. For anything long-lived, keep the lifetime short or keep a small shared list of withdrawn tokens.
- Rotating the key logs everyone out unless boxes accept the old key for a while. That is the homework.

## Troubleshooting

- **Every confirm fails with `SESSION_TAMPERED`.** The boxes have different signing keys. Check that `TICKETFLOW_CHECKOUT_SIGNING_KEY` (Docker) or `ticketflow.checkout.signing-key` is the same everywhere.
- **The app refuses to start: "the checkout signing key must be at least 32 characters".** Use a longer key.
- **Every step is "over budget", even the first.** Five JVMs are fighting for too few cores. Close other programs, or start with `BIG_CORES=1 ./run.sh`.
- **A run says FAILED with `ConnectException`.** A box or the balancer is not running. Check `curl localhost:8080/api/balancer`.
