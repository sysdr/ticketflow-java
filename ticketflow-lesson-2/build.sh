#!/usr/bin/env bash
# Compiles, runs the tests, and packages target/ticketflow-0.2.0.jar. Needs JDK 25 and Maven 3.9+.
set -euo pipefail
cd "$(dirname "$0")"
mvn -B clean verify
