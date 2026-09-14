#!/bin/sh
# Stops the backend and frontend launched by ./start.sh.
set -eu

cd "$(dirname "$0")"
PID_FILE="$PWD/.start.pids"

if [ ! -f "$PID_FILE" ]; then
  echo "==> no servers started by ./start.sh"
  exit 0
fi

backend_pid=
frontend_pid=
{
  IFS= read -r backend_pid || true
  IFS= read -r frontend_pid || true
} < "$PID_FILE"

# A pid plus all of its descendants (npm -> sh -> vite). The backend's java is a child
# of the Gradle daemon, not of gradlew; the daemon kills it when the gradlew client dies.
tree() {
  for c in $(pgrep -P "$1" 2>/dev/null); do tree "$c"; done
  echo "$1"
}

pids=
for pid in "$backend_pid" "$frontend_pid"; do
  case "$pid" in
    ''|*[!0-9]*) ;;
    *) pids="$pids $(tree "$pid")" ;;
  esac
done
rm -f "$PID_FILE"

if [ -z "$pids" ]; then
  echo "==> no running servers found"
  exit 0
fi

echo "==> stopping"
kill $pids 2>/dev/null || true
# Vite's graceful shutdown sometimes hangs; give everything a moment, then force it.
for _ in 1 2 3 4 5; do
  kill -0 $pids 2>/dev/null || break
  sleep 1
done
kill -KILL $pids 2>/dev/null || true
