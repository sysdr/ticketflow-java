#!/usr/bin/env bash
# Stops the JVMs started by run.sh.
set -uo pipefail
cd "$(dirname "$0")"
[ -f .pids ] || { echo "nothing to stop"; exit 0; }
while read -r pid; do
  kill "$pid" 2>/dev/null && echo "stopped $pid"
done < .pids
rm -f .pids
