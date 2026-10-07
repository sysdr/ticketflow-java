#!/usr/bin/env bash
# build.sh — build all Docker images for Lesson 6
# Run once before the first `run.sh`.
set -euo pipefail

echo "▶ Building TicketFlow images (this takes ~2 min on first run — Maven downloads deps)"
docker compose build

echo "✓ Build complete"
