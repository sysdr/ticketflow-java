#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
# shellcheck source=jdk.sh
source ./jdk.sh
mvn -q test
