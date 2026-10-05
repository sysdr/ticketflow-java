#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
mvn -q -DskipTests package
echo "built target/ticketflow.jar"
