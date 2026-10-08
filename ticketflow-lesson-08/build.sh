#!/usr/bin/env bash
# build.sh — build Docker images for Lesson 8
set -euo pipefail
echo "▶ Building TicketFlow images (~2 min first run — Maven fetches deps)"
docker compose build
echo "✓ Build complete"
