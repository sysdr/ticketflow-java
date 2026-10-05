#!/usr/bin/env bash
# Starts TicketFlow on http://localhost:8080 (dashboard at the root URL).
set -euo pipefail
cd "$(dirname "$0")"
mvn -B -q spring-boot:run
