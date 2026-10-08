#!/usr/bin/env bash
# Repo-wide cleanup: stop TicketFlow services, strip local/build clutter,
# remove secret-like files, reclaim unused Docker resources, and stop Docker.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

# WSL + Docker Desktop: default /var/run/docker.sock is often missing.
if [[ -z "${DOCKER_HOST:-}" ]]; then
  for sock in \
    /var/run/docker.sock \
    /mnt/wsl/docker-desktop-bind-mounts/Ubuntu-24.04/docker.sock \
    /mnt/wsl/docker-desktop/shared-sockets/guest-services/docker.sock
  do
    if [[ -S "$sock" ]]; then
      export DOCKER_HOST="unix://$sock"
      break
    fi
  done
fi

# Avoid Docker Desktop Windows credential-helper failures in WSL.
if [[ -z "${DOCKER_CONFIG:-}" ]]; then
  export DOCKER_CONFIG="${ROOT}/.docker-config-cleanup"
  mkdir -p "$DOCKER_CONFIG"
  printf '%s\n' '{}' > "${DOCKER_CONFIG}/config.json"
fi

echo "==> Stopping local TicketFlow JVMs (if any)"
pkill -f 'java -jar .*ticketflow.*\.jar' 2>/dev/null || true
pkill -f 'java -jar /app/app\.jar' 2>/dev/null || true
pkill -f 'spring-boot:run' 2>/dev/null || true
pkill -f 'com.ticketflow.TicketFlowApplication|dev.ticketflow.TicketFlowApplication' 2>/dev/null || true
pkill -f 'com.ticketflow.loadgen.MixedLoadGenerator' 2>/dev/null || true
for lesson in ticketflow-lesson-*/; do
  [[ -d "$lesson" ]] || continue
  if [[ -f "${lesson}stop.sh" ]]; then
    (cd "$lesson" && bash ./stop.sh) 2>/dev/null || true
  fi
  rm -f "${lesson}.pids"
done

echo "==> Stopping all Compose projects and removing their containers/images"
if command -v docker >/dev/null 2>&1; then
  for lesson in ticketflow-lesson-*/; do
    [[ -f "${lesson}docker-compose.yml" || -f "${lesson}compose.yml" ]] || continue
    echo "    docker compose down in ${lesson%/}"
    (cd "$lesson" && docker compose down --rmi local --remove-orphans --volumes) 2>/dev/null || true
  done
  docker images --format '{{.Repository}}:{{.Tag}} {{.ID}}' 2>/dev/null \
    | awk '/ticketflow/ {print $2}' \
    | xargs -r docker rmi -f 2>/dev/null || true
else
  echo "    docker not found; skipping container cleanup"
fi

echo "==> Removing files/folders not useful for git push"
rm_tree() {
  local path="$1"
  local abs parent base
  [[ -e "$path" ]] || return 0
  if rm -rf "$path" 2>/dev/null; then
    echo "    removed $path"
    return 0
  fi
  abs="$(cd "$(dirname "$path")" && pwd)/$(basename "$path")"
  parent="$(dirname "$abs")"
  base="$(basename "$abs")"
  if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
    echo "    $path not writable locally; removing via docker"
    docker run --rm -v "${parent}:/work" -w /work alpine:3.20 rm -rf "$base" 2>/dev/null || true
  elif command -v docker.exe >/dev/null 2>&1 && docker.exe info >/dev/null 2>&1; then
    echo "    $path not writable locally; removing via docker.exe"
    docker.exe run --rm -v "${parent}:/work" -w /work alpine:3.20 rm -rf "$base" 2>/dev/null || true
  fi
  if [[ -e "$path" ]] && command -v sudo >/dev/null 2>&1; then
    sudo -n rm -rf "$path" 2>/dev/null || true
  fi
  [[ -e "$path" ]] || echo "    removed $path"
}

# Empty accidental folder (not a lesson)
rm_tree "lesson-06"

# Nested Maven/build output under any lesson (including load-generator/target)
while IFS= read -r -d '' d; do
  rm_tree "$d"
done < <(find . -path './.git' -prune -o -type d \( \
  -name target -o -name reports -o -name logs -o -name .m2 -o -name .docker-config \
  -o -name out -o -name build -o -name dist -o -name .gradle \
  -o -name .idea -o -name .vscode -o -name .settings \
\) -print0 2>/dev/null)

for lesson in ticketflow-lesson-*/; do
  [[ -d "$lesson" ]] || continue
  rm -f "${lesson}.classpath" "${lesson}.project" 2>/dev/null || true
done

find . -type f \( \
  -name '*.log' -o \
  -name '*.jar' -o \
  -name '*.war' -o \
  -name '*.class' -o \
  -name '*.iml' -o \
  -name '.DS_Store' -o \
  -name 'Thumbs.db' -o \
  -name '*~' -o \
  -name '*.swp' -o \
  -name '*.swo' -o \
  -name 'hs_err_pid*' -o \
  -name '*-results.csv' -o \
  -name '.pids' \
\) ! -path './.git/*' -delete 2>/dev/null || true

echo "==> Scanning for secrets / credential files"
SECRET_HITS=0
while IFS= read -r -d '' f; do
  echo "    removing secret-like file: $f"
  rm -f "$f"
  SECRET_HITS=$((SECRET_HITS + 1))
done < <(find . -type f \( \
  -name '.env' -o -name '.env.*' -o \
  -name '*credentials*' -o -name '*secrets*' -o \
  -name '*.pem' -o -name '*.p12' -o -name '*.jks' -o \
  -name 'id_rsa' -o -name 'id_ed25519' -o \
  -name '.token_seed' -o -name '.token_seed.lock' \
\) ! -path './.git/*' -print0 2>/dev/null)

if grep -RInE \
  --exclude-dir=target \
  --exclude-dir=.m2 \
  --exclude-dir=.docker-config \
  --exclude-dir=.docker-config-cleanup \
  --exclude-dir=.git \
  --exclude='cleanup.sh' \
  '(api[_-]?key\s*[:=]|secret[_-]?key\s*[:=]|BEGIN (RSA |OPENSSH )?PRIVATE KEY|AKIA[0-9A-Z]{16}|sk-[A-Za-z0-9]{20,}|ghp_[A-Za-z0-9]{20,})' \
  . 2>/dev/null; then
  echo "    WARNING: possible secret strings still present in source — review the lines above"
  SECRET_HITS=$((SECRET_HITS + 1))
else
  echo "    no API keys or private-key markers found in source"
fi

# Drop the temporary Docker config used only for this run.
rm -rf "${ROOT}/.docker-config-cleanup" 2>/dev/null || true

if command -v docker >/dev/null 2>&1; then
  echo "==> Removing unused Docker containers, networks, volumes, and images"
  docker container prune -f 2>/dev/null || true
  docker network prune -f 2>/dev/null || true
  docker volume prune -f 2>/dev/null || true
  docker image prune -af 2>/dev/null || true
  docker builder prune -af 2>/dev/null || true

  echo "==> Stopping Docker engine/service (best effort)"
  if docker desktop version >/dev/null 2>&1; then
    docker desktop stop 2>/dev/null || true
  fi
  if command -v powershell.exe >/dev/null 2>&1; then
    powershell.exe -Command \
      "Stop-Process -Name 'Docker Desktop','com.docker.backend','com.docker.service' -Force -ErrorAction SilentlyContinue" \
      >/dev/null 2>&1 || true
  fi
  # Non-interactive only: never prompt for a sudo password on Docker Desktop / WSL.
  if command -v systemctl >/dev/null 2>&1; then
    sudo -n systemctl stop docker.socket docker 2>/dev/null || true
  fi
  if command -v service >/dev/null 2>&1; then
    sudo -n service docker stop 2>/dev/null || true
  fi
fi

echo "==> Cleanup complete"
echo "    secret-like files removed / warnings: ${SECRET_HITS}"
echo "    restart a lesson later with: cd ticketflow-lesson-N && docker compose up --build -d"
echo "    or: ./build.sh && ./run.sh"
