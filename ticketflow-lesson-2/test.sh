#!/usr/bin/env bash
# Runs only the unit tests.
set -euo pipefail
cd "$(dirname "$0")"
mvn -B test
