#!/usr/bin/env bash
# Runs the unit tests and the test that boots a real box and ramps load at it.
set -euo pipefail
cd "$(dirname "$0")"
mvn -B test
