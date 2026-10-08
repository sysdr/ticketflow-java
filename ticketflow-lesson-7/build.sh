#!/usr/bin/env bash
# Builds target/ticketflow.jar. Needs JDK 25 and Maven 3.9+ on PATH.
set -euo pipefail
cd "$(dirname "$0")"
mvn -B -q -DskipTests package
echo "Built target/ticketflow.jar"
