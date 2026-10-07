# TicketFlow — Lesson 6: Load Balancing Fundamentals

Two NGINX upstream algorithms (round-robin and least-connections) in front of two identical
Spring Boot instances sharing one PostgreSQL database.

---

## What this teaches

* Round-robin distributes by count; least-connections distributes by current load.
* The difference is only visible when request durations vary — see the workflow diagram.
* `least_conn` counts open TCP connections, not requests in flight.
  Disabling HTTP/1.1 keepalive (`proxy_http_version 1.0`) is required for accurate counts.
* `processedBy` field on every Booking row lets you query which instance handled each request.

---

## Prerequisites

| Tool           | Version     | Notes                                   |
|----------------|-------------|-----------------------------------------|
| Docker         | 24+         | With Compose v2 (`docker compose`)      |
| Java           | 25          | For load generator only (`java --source 25`) |
| curl           | any         | Smoke tests                             |
| A browser      | any         | Dashboard (`dashboard/index.html`)      |

> **Sandbox note:** This project was authored with Spring Boot 4 / Java 25. If your local
> JDK is older, the Docker build handles compilation — you only need JDK 25 locally to run
> the load generator. The Maven build inside Docker downloads all dependencies from Maven
> Central; the first build takes ~2 min.

---

## Quick start (Docker)

```bash
# 1. Build images (once)
./build.sh

# 2. Start the stack
./run.sh

# 3. Smoke-test + run load generator + check distribution
./test.sh
```

After `test.sh` finishes, open `dashboard/index.html` in a browser and drop
`reports/rr-results.csv` and `reports/lc-results.csv` onto the upload area.

---

## No-Docker path (local JDK 25 + local PostgreSQL)

```bash
# Start PostgreSQL locally and create the database
createdb ticketflow

# Build the app
mvn package -DskipTests

# Run instance 1
SERVER_PORT=8081 INSTANCE_ID=app-1 \
  DB_URL=jdbc:postgresql://localhost:5432/ticketflow \
  java -jar target/ticketflow-*.jar &

# Run instance 2
SERVER_PORT=8082 INSTANCE_ID=app-2 \
  DB_URL=jdbc:postgresql://localhost:5432/ticketflow \
  java -jar target/ticketflow-*.jar &

# NGINX (if available locally):
#   Point nginx/nginx.conf upstream blocks at localhost:8081 and localhost:8082
#   nginx -c "$(pwd)/nginx/nginx.conf"
#
# Without NGINX, hit instances directly:
#   curl -X POST http://localhost:8081/bookings -H "Content-Type: application/json" \
#        -d '{"venueId":1,"buyerEmail":"test@example.com"}'
```

---

## Endpoints

| Path                              | Method | Description                            |
|-----------------------------------|--------|----------------------------------------|
| `/rr/bookings`                    | POST   | Book a seat via round-robin            |
| `/lc/bookings`                    | POST   | Book a seat via least-connections      |
| `/rr/venues/1/seatmap`            | GET    | Seat-map (400 ms intentional delay) via RR |
| `/lc/venues/1/seatmap`            | GET    | Seat-map via LC                        |
| `/rr/admin/distribution`          | GET    | Per-instance booking counts            |
| `/rr/admin/duplicates`            | GET    | Double-booking check                   |
| `/health`                         | GET    | Actuator health (bypasses upstream)    |

---

## Request body: POST /bookings

```json
{ "venueId": 1, "buyerEmail": "you@example.com" }
```

---

## Running the load generator manually

```bash
# Round-robin run (60 s, 80 RPS, 80% booking / 20% seatmap)
java --source 25 load-generator/src/main/java/com/ticketflow/loadgen/MixedLoadGenerator.java \
  --algorithm rr \
  --output    reports/rr-results.csv \
  --rps       80

# Least-connections run
java --source 25 load-generator/src/main/java/com/ticketflow/loadgen/MixedLoadGenerator.java \
  --algorithm lc \
  --output    reports/lc-results.csv \
  --rps       80
```

Drop both CSV files onto `dashboard/index.html` to see the comparison.

---

## Verifying distribution

```bash
curl -s http://localhost/rr/admin/distribution
# Expected: [["app-1", N], ["app-2", M]] where N ≈ M
```

Both instances should show roughly equal booking counts.
A 60/40 split is fine; 90/10 is a sign something is wrong.

---

## Homework: slow-drain scenario

The article describes a homework challenge: raise the seatmap request ratio from
5% to 60% over 90 seconds while keeping total RPS constant at 80.

Modify `MixedLoadGenerator.java`:
* Add a `--ramp-seconds` flag (default 90).
* Linearly increase seatmap fraction from 0.20 to 0.60 over that window.
* Keep `--rps` fixed.
* Write a third column `seatmap_fraction` to the CSV.

**Key trap**: don't change RPS and fraction at the same time — that conflates two
variables and makes the result uninterpretable. Vary only one.

---

## Project structure

```
ticketflow-lesson-06/
├── src/
│   ├── main/java/com/ticketflow/
│   │   ├── TicketFlowApplication.java   — MDC instanceId tag
│   │   ├── config/DataInitializer.java  — seeds Riverside Hall (500 seats)
│   │   ├── controller/BookingController.java
│   │   ├── model/  Venue, Seat, Booking
│   │   ├── repository/  SeatRepository, BookingRepository, VenueRepository
│   │   └── service/InventoryService.java  — 400 ms seatmap delay lives here
│   ├── resources/application.yml
│   └── test/java/com/ticketflow/InventoryServiceTest.java  — 15 unit tests
├── load-generator/
│   └── src/main/java/com/ticketflow/loadgen/MixedLoadGenerator.java
├── dashboard/index.html                  — self-contained comparison dashboard
├── nginx/nginx.conf                      — /rr/ and /lc/ upstream blocks
├── docker-compose.yml
├── Dockerfile
├── build.sh / run.sh / test.sh
└── README.md  (this file)
```

---

## Day 7 preview

Both instances share one PostgreSQL database — that is intentional.
In Day 7 you will store session state in the JVM heap of app-1.
When NGINX routes the next request to app-2, that state will be missing.
The architecture diagram's red annotation is a spoiler of exactly what breaks.
