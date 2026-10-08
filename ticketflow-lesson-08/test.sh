#!/usr/bin/env bash
# test.sh — smoke-tests + THE KEY LESSON 8 PROOF: bookings survive a restart
set -euo pipefail

BASE=http://localhost

echo "── Smoke tests ─────────────────────────────────────────────"

echo -n "Health ... "
STATUS=$(curl -s -o /dev/null -w "%{http_code}" "$BASE/health")
[[ "$STATUS" == "200" ]] && echo "OK" || { echo "FAIL ($STATUS)"; exit 1; }

echo -n "POST /rr/bookings ... "
RESP=$(curl -sf -X POST "$BASE/rr/bookings" \
  -H "Content-Type: application/json" \
  -d '{"venueId":1,"buyerEmail":"smoke@test.example"}')
echo "OK → $RESP"
BOOKING_ID=$(echo "$RESP" | python3 -c "import sys,json; print(json.load(sys.stdin)['bookingId'])" 2>/dev/null || echo "")

echo -n "GET /lc/venues/1/seatmap ... "
STATUS=$(curl -s -o /dev/null -w "%{http_code}" "$BASE/lc/venues/1/seatmap")
[[ "$STATUS" == "200" ]] && echo "OK" || { echo "FAIL ($STATUS)"; exit 1; }

echo ""
echo "── Lesson 8 proof: persistence across restart ───────────────"

if [[ -z "$BOOKING_ID" ]]; then
  echo "⚠ Could not parse booking ID — skipping restart test"
else
  echo "Booking ID to verify after restart: $BOOKING_ID"

  echo "Restarting app-1 (JVM heap cleared)..."
  docker compose restart app-1
  sleep 6

  echo -n "GET /rr/bookings/$BOOKING_ID after restart ... "
  STATUS=$(curl -s -o /dev/null -w "%{http_code}" "$BASE/rr/bookings/$BOOKING_ID")
  if [[ "$STATUS" == "200" ]]; then
    echo "✓ PASS — booking survived restart (PostgreSQL WAL)"
  else
    echo "✗ FAIL ($STATUS) — booking lost! Check datasource config"
    exit 1
  fi
fi

echo ""
echo "── Seat-map counts ─────────────────────────────────────────"
curl -sf "$BASE/rr/venues/1/seatmap" | python3 -m json.tool 2>/dev/null || \
  curl -sf "$BASE/rr/venues/1/seatmap"
echo ""

echo "── Booking distribution ────────────────────────────────────"
curl -sf "$BASE/rr/admin/distribution" | python3 -m json.tool 2>/dev/null || \
  curl -sf "$BASE/rr/admin/distribution"
echo ""

echo "── Duplicate check ─────────────────────────────────────────"
DUPES=$(curl -sf "$BASE/rr/admin/duplicates")
if [[ "$DUPES" == "[]" ]]; then
  echo "✓ No duplicate bookings"
else
  echo "⚠ Duplicates found (seat IDs): $DUPES"
fi

echo ""
echo "── Booking history (homework endpoint) ─────────────────────"
echo -n "GET /rr/venues/1/bookings ... "
STATUS=$(curl -s -o /dev/null -w "%{http_code}" "$BASE/rr/venues/1/bookings")
[[ "$STATUS" == "200" ]] && echo "OK" || echo "FAIL ($STATUS)"
