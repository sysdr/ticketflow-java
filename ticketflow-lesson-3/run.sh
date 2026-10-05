#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
# shellcheck source=jdk.sh
source ./jdk.sh
# Ten thousand buyers means ten thousand sockets on both sides of the connection.
ulimit -n 65536 2>/dev/null || echo "warning: could not raise open-file limit; large stampedes may fail early"
exec java -jar target/ticketflow.jar "$@"
