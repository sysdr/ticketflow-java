#!/usr/bin/env bash
# Walks one seat through its whole life against a RUNNING server and prints the trace.
# Usage: ./demo.sh [base-url]   (default http://localhost:8080)
set -euo pipefail
BASE="${1:-http://localhost:8080}"
RID="demo-$(date +%s)"

say() { printf '\n\033[1m== %s\033[0m\n' "$*"; }
json() { sed -n "s/.*\"$1\":\"\([^\"]*\)\".*/\1/p"; }

say "1. Hold seat C5 for alice (request id: $RID-hold)"
HOLD=$(curl -s -X POST "$BASE/api/holds" -H "X-Request-Id: $RID-hold" -H 'Content-Type: application/json' \
       -d '{"seatId":"C5","buyerId":"alice"}')
echo "$HOLD"
HOLD_ID=$(echo "$HOLD" | json id)

say "2. Bob tries the same seat while alice holds it -> expect 409 SEAT_UNAVAILABLE"
curl -s -o /dev/stderr -w 'HTTP %{http_code}\n' -X POST "$BASE/api/holds" -H "X-Request-Id: $RID-bob" \
     -H 'Content-Type: application/json' -d '{"seatId":"C5","buyerId":"bob"}'

say "3. Skip the clock 121 s: alice's hold passes its deadline, nobody touches it"
curl -s -X POST "$BASE/api/dev/clock/advance?seconds=121"; echo

say "4. Alice confirms too late -> expect 410 HOLD_EXPIRED"
curl -s -o /dev/stderr -w 'HTTP %{http_code}\n' -X POST "$BASE/api/holds/$HOLD_ID/confirm" -H "X-Request-Id: $RID-late"

say "5. Bob holds and confirms C5 -> expect a booking"
H2=$(curl -s -X POST "$BASE/api/holds" -H "X-Request-Id: $RID-bob2" -H 'Content-Type: application/json' \
     -d '{"seatId":"C5","buyerId":"bob"}' | json id)
curl -s -X POST "$BASE/api/holds/$H2/confirm" -H "X-Request-Id: $RID-bob2"; echo

say "6. Everything the server reported, newest last"
curl -s "$BASE/api/events" | tr '{' '\n' | grep "$RID" | sed 's/^/   /' || true

say "7. Invariants (expect an empty list)"
curl -s "$BASE/api/state" | sed -n 's/.*"invariantViolations":\(\[[^]]*\]\).*/\1/p'
