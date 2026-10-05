#!/usr/bin/env bash
# Source this to put a JDK 25 on PATH for TicketFlow scripts.
# Prefer an already-correct JAVA_HOME; otherwise use the local Temurin install.

_java_major() {
  "$1" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1
}

if [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/java" ]]; then
  if [[ "$(_java_major "${JAVA_HOME}/bin/java")" == "25" ]]; then
    export PATH="${JAVA_HOME}/bin:${PATH}"
    return 0 2>/dev/null || exit 0
  fi
fi

_candidates=(
  "${HOME}/.local/jdk-25/jdk-25.0.4.1+1"
  "${HOME}/.local/jdk-25"
)

# Nested extract layouts: ~/.local/jdk-25/jdk-*
if [[ -d "${HOME}/.local/jdk-25" ]]; then
  while IFS= read -r d; do
    _candidates+=("$d")
  done < <(find "${HOME}/.local/jdk-25" -maxdepth 1 -type d -name 'jdk-*' 2>/dev/null | sort -r)
fi

for _home in "${_candidates[@]}"; do
  if [[ -x "${_home}/bin/java" && "$(_java_major "${_home}/bin/java")" == "25" ]]; then
    export JAVA_HOME="${_home}"
    export PATH="${JAVA_HOME}/bin:${PATH}"
    return 0 2>/dev/null || exit 0
  fi
done

if command -v java >/dev/null 2>&1 && [[ "$( _java_major "$(command -v java)" )" == "25" ]]; then
  return 0 2>/dev/null || exit 0
fi

echo "error: JDK 25 is required (pom.xml java.version=25)." >&2
echo "Install Temurin 25, or set JAVA_HOME to a JDK 25 install, then retry." >&2
echo "Example:" >&2
echo "  export JAVA_HOME=\"\$HOME/.local/jdk-25/jdk-25.0.4.1+1\"" >&2
echo "  export PATH=\"\$JAVA_HOME/bin:\$PATH\"" >&2
return 1 2>/dev/null || exit 1
