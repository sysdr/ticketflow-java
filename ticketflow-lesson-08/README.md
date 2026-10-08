# TicketFlow — Lesson 8: From Memory to PostgreSQL

TicketFlow now persists every booking to PostgreSQL. Restart any app instance —
the seats and bookings are still there.

---

## What changed from Lesson 7

| Before (Lesson 6/7)                       | After (Lesson 8)                           |
|-------------------------------------------|--------------------------------------------|
| `HashMap<Long, Seat>` in JVM heap         | `SeatRepository` + JPA → PostgreSQL table  |
| Booking lost on restart                   | Booking survives restart (WAL-backed)      |
| `bookSeat()` — plain method call          | `@Transactional` → SELECT FOR UPDATE → COMMIT |
| No `GET /bookings/{id}` endpoint          | Lookup by ID for restart verification      |
| No booking history                        | `GET /venues/{id}/bookings` (homework)     |

---

## The key insight

PostgreSQL's WAL (write-ahead log) is what makes a COMMIT durable. Before the
database replies OK to a COMMIT, it writes the commit record to the WAL on disk.
If the app or the database crashes after that point, the WAL replays on startup
and the data is restored. `test.sh` demonstrates this directly — book a seat,
restart app-1, confirm the booking still exists.

---

## Prerequisites

| Tool    | Version | Notes                                    |
|---------|---------|------------------------------------------|
| Docker  | 24+     | With Compose v2 (`docker compose`)       |
| curl    | any     | Smoke tests                              |
| Python3 | any     | JSON pretty-printing in test.sh          |

> **Sandbox note:** Maven build and Docker steps are authored for Spring Boot 4 / Java 25.
> First build pulls dependencies from Maven Central and takes ~2 min.

---

## Quick start (Docker)

```bash
./build.sh   # build images once
./run.sh     # start postgres + app-1 + app-2 + nginx
./test.sh    # smoke-test + restart proof + homework endpoint check
```

---

## No-Docker path

```bash
createdb ticketflow   # requires local PostgreSQL

mvn package -DskipTests

# Instance 1
SERVER_PORT=8081 INSTANCE_ID=app-1 \
  DB_URL=jdbc:postgresql://localhost:5432/ticketflow \
  java -jar target/ticketflow-*.jar &

# Instance 2
SERVER_PORT=8082 INSTANCE_ID=app-2 \
  DB_URL=jdbc:postgresql://localhost:5432/ticketflow \
  java -jar target/ticketflow-*.jar &
```

Without NGINX, call instances directly on ports 8081 / 8082.

---

## Endpoints

| Path                           | Method | Description                                  |
|--------------------------------|--------|----------------------------------------------|
| `/rr/bookings`                 | POST   | Book a seat (via round-robin)                |
| `/lc/bookings`                 | POST   | Book a seat (via least-connections)          |
| `/rr/bookings/{id}`            | GET    | Look up a booking by ID — restart proof      |
| `/rr/venues/1/seatmap`         | GET    | Seat counts (available / booked)             |
| `/rr/venues/1/bookings`        | GET    | Booking history (homework endpoint)          |
| `/rr/admin/distribution`       | GET    | Per-instance booking counts                  |
| `/rr/admin/duplicates`         | GET    | Duplicate-booking check (should be `[]`)     |
| `/health`                      | GET    | Actuator health                              |

---

## Request body: POST /bookings

```json
{ "venueId": 1, "buyerEmail": "you@example.com" }
```

---

## Running unit tests

```bash
mvn test
# Expected: 18 tests, 0 failures — Mockito only, no Spring context, no database
```

---

## Schema notes

`ddl-auto: update` creates all tables on first startup — safe for development.
For production, switch to `ddl-auto: validate` and manage schema with Flyway or
Liquibase. Don't add either dependency yet; the schema is still evolving through
Module 1.

---

## Project structure

```
ticketflow-lesson-08/
├── src/
│   ├── main/java/com/ticketflow/
│   │   ├── TicketFlowApplication.java
│   │   ├── config/DataInitializer.java        — seeds Riverside Hall on first start
│   │   ├── controller/BookingController.java  — REST endpoints incl. GET /bookings/{id}
│   │   ├── model/  Venue, Seat, Booking        — @Entity classes
│   │   ├── repository/  VenueRepo, SeatRepo, BookingRepo  — JPA interfaces
│   │   └── service/InventoryService.java      — @Transactional bookSeat()
│   ├── resources/application.yml
│   └── test/java/com/ticketflow/InventoryServiceTest.java  — 18 unit tests
├── nginx/nginx.conf
├── docker-compose.yml
├── Dockerfile
├── build.sh / run.sh / test.sh
└── README.md  (this file)
```

---

## Day 9 preview

The HikariCP pool is set to `maximum-pool-size: 10`. Lesson 9 will resize it
intentionally — too small — and show exactly what the log looks like when all
connections are in use and new requests queue up waiting for one. You'll also
learn the correct formula for sizing a pool for a given workload.
