#!/usr/bin/env bash
# run.sh — start the full stack and wait for readiness
set -euo pipefail

echo "▶ Starting postgres, app-1, app-2, nginx..."
docker compose up -d

echo "⏳ Waiting for app-1 health..."
until curl -sf http://localhost:80/health > /dev/null 2>&1; do
  sleep 2
  printf "."
done
echo ""

echo "✓ Stack is up"
echo ""
echo "Endpoints:"
echo "  Round-robin:      http://localhost/rr/"
echo "  Least-conn:       http://localhost/lc/"
echo "  Health:           http://localhost/health"
echo "  Distribution:     http://localhost/rr/admin/distribution"
echo ""
echo "Run ./test.sh to verify, then load-generator runs for the comparison."
