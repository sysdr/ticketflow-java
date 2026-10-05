# TicketFlow — Lesson 3: The Network Is Not Your Friend

Hands-On Distributed Systems, Module 1 (Foundations & First Node).
Stack: Java 25, Spring Boot 4, Maven.

TicketFlow is still one server, but today we put a flaky link between a client and it and watch what happens.
A **fault proxy** on port 8082 sits on a real TCP path to the real server. It can delay messages, swallow requests,
swallow replies, or trickle replies out. The **fallacy lab** sends real requests through it and compares what each
client *heard* with what the server *actually did*. Nothing is fixed today; the goal is to see the gap.

> **Honest status.** This project was written in an environment with no network and no Maven, so the full Spring Boot
> build and the JUnit tests have **not** been run there. The application classes were type-checked against stand-ins for
> the Spring and SLF4J APIs. The fault proxy and lab runner were then exercised end to end over real sockets against a
> stand-in HTTP server using the real `HoldController`, and all seven scenarios in the test behaved as described below.
> `./build.sh` and `./test.sh` on your machine are the first real build. Spring Boot 4.0.0 is pinned in `pom.xml`.

## What's new since Lesson 2

| Where | What |
|---|---|
| `lab/FaultProxy` | TCP proxy on port 8082 that forwards to TicketFlow and misbehaves on demand |
| `lab/FaultMode`, `FaultConfig` | `NONE`, `LATENCY`, `LOSE_REQUEST`, `LOSE_RESPONSE`, `THROTTLE` |
| `lab/LabRunner` | Sends real requests through the proxy, then asks the server what it really did |
| `VenueService` | Two small additions: `liveHoldOn(seat)` (ground truth) and `releaseHoldsOf(prefix)` |
| Dashboard | http://localhost:8081/fallacy-lab |

Nothing from Lessons 1 and 2 changed. The stampede and the box office work exactly as before.

What each fault does:

| Fault | What happens | What the client sees |
|---|---|---|
| Healthy | Nothing | A normal answer |
| Slow link | Delays the request in and the reply out | Slow answers, or silence if slower than its patience |
| Request lost | Proxy swallows the request. **The server never sees it.** | Silence |
| Reply lost | Server does the work. Proxy swallows the reply | Silence, identical to the line above |
| Thin pipe | Reply trickles out at N bytes per second | A partial reply, then silence |

Limits worth knowing: the proxy handles one HTTP request per connection and works on whole messages. It simulates
the network at message level, not packet level.

## Path A — Docker

```bash
docker compose up --build
```

Open http://localhost:8081/fallacy-lab.

## Path B — No Docker

Prerequisites: JDK 25, Maven 3.9+.

```bash
./build.sh
./run.sh
```

Open http://localhost:8081/fallacy-lab. Or run a scenario from a second terminal:

```bash
./lab.sh LOSE_RESPONSE 8 800 true     # mode, buyers, patience ms, retry once
./lab.sh LOSE_REQUEST 8 800 true
./lab.sh THROTTLE 3 1500 false
```

## Test

```bash
./test.sh
```

`FallacyLabIntegrationTest` starts the real server (ports 18090-18092) and checks all five situations:
healthy, reply lost, request lost, slow link, thin pipe. The Lesson 1 and 2 tests still pass.

## Verify and demo

1. Pick **Reply lost**, tick **Retry once when you hear nothing**, press **Run the lab**. Every row shows `silence`
   in "Client heard" but `yes, held` in "Server holds the seat", and the retry comes back `HTTP_409`. The verdict is
   *Retry hit your own hold*: the client's retry was rejected because of its own earlier success.
2. Pick **Request lost** with the same retry box ticked. This time "Server holds the seat" starts as `no`, the retry succeeds,
   and the verdict is *Retry fixed it*. To the client, silence looked identical to the case above.
3. Pick **Slow link**, delay 400, patience 600, retry off. Every request times out, yet the server ends up holding every seat.
   The requests landed after the clients had left.
4. Pick **Thin pipe**, call **Fetch seat map**, patience 1500. Bytes arrive, then the client gives up mid-reply.
5. Follow one request through every component by its id:

```bash
./run.sh | tee run.log
grep 'rid=lab-0003-a1 ' run.log
```

Reply lost:

```
LAB    rid=lab-0003-a1 event=send call=hold seat=T-03 patienceMs=800
PROXY  rid=lab-0003-a1 event=request_seen bytes=133 fault=LOSE_RESPONSE
EDGE   rid=lab-0003-a1 event=hold_requested seat=T-03 buyer=lab-03
VENUE  rid=lab-0003-a1 event=hold_placed hold=h-0003 seat=T-03 buyer=lab-03 expiresAt=...
PROXY  rid=lab-0003-a1 event=upstream_replied bytes=200
PROXY  rid=lab-0003-a1 event=fault_applied mode=LOSE_RESPONSE detail=reply_swallowed_server_already_did_the_work
LAB    rid=lab-0003-a1 event=gave_up afterMs=801 bytesSoFar=0
```

Request lost: the same grep shows `LAB send`, `PROXY request_seen` and `PROXY fault_applied mode=LOSE_REQUEST`, then
`LAB gave_up`. There are **no** `EDGE` or `VENUE` lines. That absence is the proof the server never saw it.
Compare the two traces: the client's last line is identical.

The retry is a separate id ending in `-a2`; it carries a header that tells the proxy the link has recovered.

## Notes

* Hold deadlines are 30 seconds (Lesson 2). The lab finishes well inside that.
* Lab buyers are named `lab-01`... and sit in the last row of the venue. Each run releases the previous run's lab holds first.
* The server-side column of the table is read straight from `VenueService`, as an outside observer. A real client can never see it.

## Layout

```
src/main/java/dev/ticketflow/
  lab/            FaultProxy, FaultMode, FaultConfig, LabRunner
  domain/ inventory/ booking/ metrics/ loadgen/ logging/ observability/   as in Lessons 1-2
src/main/resources/dashboard/   index.html, box-office.html, fallacy-lab.html
```
