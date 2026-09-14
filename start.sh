#!/bin/sh
# Starts backend (http://localhost:8080) and frontend (http://localhost:5173) together.
# Ctrl+C stops both. Requires: JDK 25, Node ^22.12.0, ^24.0.0 or >=26.0.0, npm.
set -eu

cd "$(dirname "$0")"
PID_FILE="$PWD/.start.pids"
PID_FILE_TMP="$PID_FILE.$$"

# True when $1 is the pid of a running start.sh. The pid file can outlive a crashed run and
# the OS may hand its pid to something else, so check the command line, not just liveness.
is_start_sh() {
  case "$1" in ''|0|*[!0-9]*) return 1 ;; esac
  kill -0 "$1" 2>/dev/null || return 1
  case "$(ps -o args= -p "$1" 2>/dev/null)" in */start.sh|*" start.sh") return 0 ;; *) return 1 ;; esac
}

if [ -f "$PID_FILE" ]; then
  running=
  IFS= read -r running < "$PID_FILE" || true
  if is_start_sh "$running"; then
    echo "==> already running (./start.sh pid $running); run ./stop.sh first" >&2
    exit 1
  fi
fi

if [ ! -d frontend/node_modules ]; then
  echo "==> frontend: npm install"
  (cd frontend && npm install --no-audit --no-fund --loglevel=error)
fi

echo "==> backend: ./gradlew run   (http://localhost:8080)"
(cd backend && exec ./gradlew run --quiet --console=plain) &
BACKEND_PID=$!

echo "==> frontend: npm run dev    (http://localhost:5173)"
(cd frontend && exec npm run dev) &
FRONTEND_PID=$!

# A pid plus all of its descendants (npm -> sh -> vite). The backend's java is a child
# of the Gradle daemon, not of gradlew; the daemon kills it when the gradlew client dies.
tree() {
  for c in $(pgrep -P "$1" 2>/dev/null); do tree "$c"; done
  echo "$1"
}

# True while any of the given pids is still running. `kill -0 $pids` is not enough: dash's kill
# fails as soon as one pid is gone, bash's succeeds while one is alive.
alive() {
  for p in "$@"; do kill -0 "$p" 2>/dev/null && return 0; done
  return 1
}

stop() {
  trap - INT TERM EXIT
  echo
  echo "==> stopping"
  # Newline-separated; expanded unquoted below on purpose so each pid is its own argument.
  pids=$(tree "$BACKEND_PID"; tree "$FRONTEND_PID")
  rm -f "$PID_FILE" "$PID_FILE_TMP"
  kill $pids 2>/dev/null || true
  # Vite's graceful shutdown sometimes hangs; give everything a moment, then force it.
  for _ in 1 2 3 4 5; do
    alive $pids || break
    sleep 1
  done
  kill -KILL $pids 2>/dev/null || true
  wait 2>/dev/null || true
}
trap stop INT TERM EXIT

# Record our own pid so ./stop.sh can ask this run to shut down through the trap above.
printf '%s\n' "$$" > "$PID_FILE_TMP"
mv "$PID_FILE_TMP" "$PID_FILE"

# Exit as soon as either process dies (e.g. port in use); the trap stops the other.
while kill -0 "$BACKEND_PID" 2>/dev/null && kill -0 "$FRONTEND_PID" 2>/dev/null; do
  sleep 1
done
