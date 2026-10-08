#!/usr/bin/env bash
# Stop TicketFlow lesson 7, strip local/build clutter, and reclaim unused Docker resources.
set -euo pipefail
cd "$(dirname "$0")"

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
  export DOCKER_CONFIG="$(pwd)/.docker-config-cleanup"
  mkdir -p "$DOCKER_CONFIG"
  printf '%s\n' '{}' > "${DOCKER_CONFIG}/config.json"
fi

echo "==> Stopping local TicketFlow JVM (if any)"
if [[ -f ./stop.sh ]]; then
  bash ./stop.sh 2>/dev/null || true
fi
pkill -f 'java -jar .*ticketflow.*\.jar' 2>/dev/null || true
pkill -f 'java -jar /app/app\.jar' 2>/dev/null || true
pkill -f 'spring-boot:run' 2>/dev/null || true
pkill -f 'com.ticketflow.TicketFlowApplication|dev.ticketflow.TicketFlowApplication' 2>/dev/null || true
rm -f .pids

echo "==> Stopping Compose project and removing its containers/images"
if command -v docker >/dev/null 2>&1; then
  docker compose down --rmi local --remove-orphans --volumes 2>/dev/null || true
  docker images --format '{{.Repository}}:{{.Tag}} {{.ID}}' 2>/dev/null \
    | awk '/ticketflow/ {print $2}' \
    | xargs -r docker rmi -f 2>/dev/null || true
else
  echo "    docker not found; skipping container cleanup"
fi

echo "==> Removing files/folders not useful for git push"
rm_tree() {
  local path="$1"
  [[ -e "$path" ]] || return 0
  if rm -rf "$path" 2>/dev/null; then
    echo "    removed $path"
    return 0
  fi
  if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
    echo "    $path not writable locally; removing via docker"
    docker run --rm -v "$(pwd):/work" -w /work alpine:3.20 rm -rf "$path" 2>/dev/null || true
  elif command -v docker.exe >/dev/null 2>&1 && docker.exe info >/dev/null 2>&1; then
    echo "    $path not writable locally; removing via docker.exe"
    docker.exe run --rm -v "$(pwd):/work" -w /work alpine:3.20 rm -rf "$path" 2>/dev/null || true
  fi
  if [[ -e "$path" ]] && command -v sudo >/dev/null 2>&1; then
    sudo -n rm -rf "$path" 2>/dev/null || true
  fi
  [[ -e "$path" ]] || echo "    removed $path"
}
rm_tree target
rm_tree .m2
rm_tree .docker-config
rm_tree reports
rm -rf .idea/ .vscode/ .settings/ .classpath .project
rm -rf out/ build/ dist/ .gradle/ logs/
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

rm -rf .docker-config-cleanup 2>/dev/null || true

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
  if command -v systemctl >/dev/null 2>&1; then
    sudo -n systemctl stop docker.socket docker 2>/dev/null || true
  fi
  if command -v service >/dev/null 2>&1; then
    sudo -n service docker stop 2>/dev/null || true
  fi
fi

echo "==> Cleanup complete"
echo "    secret-like files removed / warnings: ${SECRET_HITS}"
echo "    ports 8080/8081/8082/8083/9090 should now be free; restart later with:"
echo "      docker compose up --build -d"
echo "    or: ./build.sh && ./run.sh"
