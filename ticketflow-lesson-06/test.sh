#!/usr/bin/env bash
# test.sh — smoke-test the running stack, then run the load generator for both algorithms
# Requires: stack running (./run.sh), Java 25 on PATH
set -euo pipefail

BASE=http://localhost
REPORTS=reports
mkdir -p "$REPORTS"

echo "── Smoke tests ─────────────────────────────────────────────"

# 1. Health
echo -n "Health check ... "
STATUS=$(curl -s -o /dev/null -w "%{http_code}" "$BASE/health")
[[ "$STATUS" == "200" ]] && echo "OK ($STATUS)" || { echo "FAIL ($STATUS)"; exit 1; }

# 2. Single booking via round-robin path
echo -n "POST /rr/bookings ... "
STATUS=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE/rr/bookings" \
  -H "Content-Type: application/json" \
  -d '{"venueId":1,"buyerEmail":"smoke@test.example"}')
[[ "$STATUS" == "200" ]] && echo "OK ($STATUS)" || { echo "FAIL ($STATUS)"; exit 1; }

# 3. Seat-map via least-conn path
echo -n "GET  /lc/venues/1/seatmap ... "
STATUS=$(curl -s -o /dev/null -w "%{http_code}" "$BASE/lc/venues/1/seatmap")
[[ "$STATUS" == "200" ]] && echo "OK ($STATUS)" || { echo "FAIL ($STATUS)"; exit 1; }

# 4. Distribution endpoint
echo -n "GET  /rr/admin/distribution ... "
DIST=$(curl -sf "$BASE/rr/admin/distribution")
echo "OK → $DIST"

echo ""
echo "── Load generator ──────────────────────────────────────────"
LOADGEN=load-generator/src/main/java/com/ticketflow/loadgen/MixedLoadGenerator.java

if ! command -v java &> /dev/null; then
  echo "java not found on PATH — skipping load generator"
  echo "Install JDK 25 and rerun, or run the generator manually:"
  echo "  java --source 25 $LOADGEN --algorithm rr --output $REPORTS/rr-results.csv"
  echo "  java --source 25 $LOADGEN --algorithm lc --output $REPORTS/lc-results.csv"
  exit 0
fi

echo "Running round-robin (60 s) ..."
java --source 25 "$LOADGEN" --algorithm rr --output "$REPORTS/rr-results.csv" --rps 80

echo ""
echo "Running least-connections (60 s) ..."
java --source 25 "$LOADGEN" --algorithm lc --output "$REPORTS/lc-results.csv" --rps 80

echo ""
echo "── Results written ─────────────────────────────────────────"
echo "  $REPORTS/rr-results.csv"
echo "  $REPORTS/lc-results.csv"
echo ""
echo "Open dashboard/index.html in a browser and drop both CSV files to see the comparison."

# 5. Distribution after load
echo ""
echo "── Booking distribution after load ─────────────────────────"
curl -sf "$BASE/rr/admin/distribution" | python3 -m json.tool 2>/dev/null || \
  curl -sf "$BASE/rr/admin/distribution"
echo ""

# 6. Duplicate check
echo "── Duplicate bookings check ────────────────────────────────"
DUPES=$(curl -sf "$BASE/rr/admin/duplicates")
if [[ "$DUPES" == "[]" ]]; then
  echo "✓ No duplicate bookings"
else
  echo "⚠ Duplicates found: $DUPES"
fi
