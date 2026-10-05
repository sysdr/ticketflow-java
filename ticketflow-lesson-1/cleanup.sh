#!/usr/bin/env bash
# Stop TicketFlow, strip local/build clutter, and reclaim unused Docker resources.
set -euo pipefail
cd "$(dirname "$0")"

echo "==> Stopping local TicketFlow JVM (if any)"
pkill -f 'java -jar .*ticketflow\.jar' 2>/dev/null || true
pkill -f 'java -jar /app/app\.jar' 2>/dev/null || true

echo "==> Stopping Compose project and removing its containers/images"
if command -v docker >/dev/null 2>&1; then
  docker compose down --rmi local --remove-orphans --volumes 2>/dev/null || true

  echo "==> Removing unused Docker containers, networks, volumes, and images"
  docker container prune -f 2>/dev/null || true
  docker network prune -f 2>/dev/null || true
  docker volume prune -f 2>/dev/null || true
  # Drop images not used by any container (includes this project's build cache layers).
  docker image prune -af 2>/dev/null || true
  docker builder prune -af 2>/dev/null || true

  echo "==> Stopping Docker engine/service (best effort)"
  if command -v docker >/dev/null 2>&1 && docker desktop version >/dev/null 2>&1; then
    docker desktop stop 2>/dev/null || true
  fi
  if command -v systemctl >/dev/null 2>&1; then
    sudo systemctl stop docker.socket docker 2>/dev/null || true
  fi
  if command -v service >/dev/null 2>&1; then
    sudo service docker stop 2>/dev/null || true
  fi
else
  echo "    docker not found; skipping container cleanup"
fi

echo "==> Removing files/folders not useful for git push"
rm -rf target/
rm -rf .idea/ .vscode/ .settings/ .classpath .project
rm -rf out/ build/ dist/ .gradle/
find . -type f \( \
  -name '*.log' -o \
  -name '*.iml' -o \
  -name '.DS_Store' -o \
  -name 'Thumbs.db' -o \
  -name '*~' -o \
  -name '*.swp' -o \
  -name '*.swo' \
\) -delete 2>/dev/null || true

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
  -name 'id_rsa' -o -name 'id_ed25519' \
\) ! -path './.git/*' -print0 2>/dev/null)

if grep -RInE \
  --exclude-dir=target \
  --exclude-dir=.git \
  --exclude='cleanup.sh' \
  '(api[_-]?key|secret[_-]?key|BEGIN (RSA |OPENSSH )?PRIVATE KEY|AKIA[0-9A-Z]{16}|sk-[A-Za-z0-9]{20,}|ghp_[A-Za-z0-9]{20,})' \
  . 2>/dev/null; then
  echo "    WARNING: possible secret strings still present in source — review the lines above"
  SECRET_HITS=$((SECRET_HITS + 1))
else
  echo "    no API keys or private-key markers found in source"
fi

echo "==> Cleanup complete"
echo "    secret-like files removed: ${SECRET_HITS}"
echo "    ports 8080/8081 should now be free; restart later with:"
echo "      docker compose up --build -d"
echo "    or: ./build.sh && ./run.sh"
