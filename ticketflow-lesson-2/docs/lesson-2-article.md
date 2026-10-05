# A Seat, a Hold, and a Deadline

*A held seat is a promise with an expiry date, and most bugs live in the code that forgets the date.*

## Where this fits

Day 1 showed where a single server cracks. Today there is finally something to crack: one Java process holding a venue, its seats, and every hold and booking in memory. No database, no network. Restart it and it forgets everything; lesson 8 ends that.

## The core idea

Picture the hold shelf in a library. You request a book, and the librarian slips a paper band around it with your name and a pick-up date. Nobody else can borrow it. Check it out and the band comes off, replaced by a loan record. Miss the date and the next person in line gets it, though no alarm rang: the librarian just glanced at the date when someone asked.

That is the whole model. The **Seat** is the book. The **Hold** is the band, a lease with a deadline. The **Booking** is the loan record, a fact that never changes afterward. Keep Hold and Booking as separate types, because one `status` field with a timestamp forces every query to remember which statuses carry a deadline.

Now the part tutorials skip. TicketFlow never sweeps for expired holds. Every read and write first asks "is this hold past its deadline, as of the clock right now?" and settles the answer on the spot.

A background sweeper looks tidier, but if it stalls, seats stay held forever and nothing notices. Lazy expiry has no such failure, and with an injected clock a test moves time forward in one call instead of sleeping two minutes.

## Real-world reference

Ticketing sites with a checkout countdown, Ticketmaster among them, use this pattern: the seats are yours for a few minutes, then they return to the pool. Without the expiry, abandoned carts lock seats permanently. Without the hold, two people pay for the same seat and one of them gets an apology.

## How it fits TicketFlow

`lesson-2-architecture.svg` traces a request from dashboard to the three stores, reporting to the event log under one id. `lesson-2-state-machine.svg` shows the only moves a seat can make; the deadline arrow has no timer attached.

## Step-by-step implementation

1. **Seat.** Three statuses, one method per legal move, and a throw for any other move.
2. **Venue.** `Venue.grid` builds fifty seats, rows A to E.
3. **Hold.** ACTIVE, then exactly one of CONFIRMED, RELEASED or EXPIRED. The deadline instant itself counts as expired.
4. **Booking.** A Java record, immutable by construction.
5. **InventoryService.** It takes a `Clock` and runs this before touching any seat:

```java
private void settleIfExpired(Seat seat) {
    if (seat.status() != SeatStatus.HELD) return;
    Hold hold = holds.get(seat.holdId());
    if (hold != null && hold.status() == Hold.Status.ACTIVE && hold.isPastDeadline(clock.instant())) {
        hold.expire();
        seat.markAvailable();
        sink.emit("inventory", "hold.expired", fields("hold", hold.id(), "seat", seat.id()));
    }
}
```

6. **Wiring.** `domain` and `service` import no framework code, so they survive Module 3's split unchanged.
7. **Request ids.** A filter stamps each request, and every event carries the id.

One gap is deliberate. `placeHold` reads a seat's status, then writes it, with nothing in between. Fine for one buyer; lesson 11 shows what many do to it.

## Build, run, test, verify, demo

Without Docker (JDK 25, Maven 3.9+):

```bash
./build.sh                      # compile + tests
./run.sh                        # http://localhost:8080
./demo.sh                       # in a second terminal
```

With Docker:

```bash
docker build -t ticketflow:lesson-2 .
docker run --rm -p 8080:8080 ticketflow:lesson-2
./demo.sh
```

Then open `http://localhost:8080`, hold a seat, and press **Skip ahead 60 s** twice.

## Success criteria

- [ ] `./test.sh` reports all 11 tests in `InventoryServiceTest` passing.
- [ ] The dashboard header shows "✓ model consistent".
- [ ] After two skips, a held seat is green again, and the trace shows `event=hold.expired` appearing only when the seat was next looked at.
- [ ] `./demo.sh` prints `HTTP 409` for the second buyer and `HTTP 410` for the late confirmation.
- [ ] `grep` for one request id in the console returns at least three lines from two components.

## Homework

Limit each buyer to four active holds. The fifth attempt returns `409` with a new error code, `HOLD_LIMIT_REACHED`. Write the failing test first.

## Solution hints

Add the code to `DomainException.Code`, count that buyer's ACTIVE holds inside `placeHold`, and map the code in `ApiExceptionHandler`. Test it by holding four seats, calling `clock.advance(121)`, then holding a fifth, which must succeed.

The trap is counting before settling. Expired but untouched holds still say ACTIVE, so a buyer gets blocked by holds that died minutes ago. Settle that buyer's seats first.

## Practical takeaway

Any reserve-then-pay flow can now be modelled as a lease plus a fact, with expiry that is correct without a background job and testable without a sleep.

## Next

Tomorrow, "The Network Is Not Your Friend" walks through the fallacies of distributed computing using real outages, and names which ones TicketFlow will eventually hit.
