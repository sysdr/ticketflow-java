#!/usr/bin/env bash
# run.sh — start the full stack and wait until healthy
set -euo pipefail

echo "▶ Starting postgres, app-1, app-2, nginx..."
docker compose up -d

echo "⏳ Waiting for health endpoint..."
until curl -sf http://localhost:80/health > /dev/null 2>&1; do
  sleep 2; printf "."
done
echo ""

echo "✓ Stack is up"
echo "  Round-robin:   http://localhost/rr/"
echo "  Least-conn:    http://localhost/lc/"
echo "  Booking:       POST http://localhost/rr/bookings"
echo "  History:       GET  http://localhost/rr/venues/1/bookings"
echo "  Distribution:  GET  http://localhost/rr/admin/distribution"
