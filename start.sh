#!/bin/sh
# Starts backend (http://localhost:8080) and frontend (http://localhost:5173) together.
# Ctrl+C stops both. Requires: JDK 25, Node ^22.12.0, ^24.0.0 or >=26.0.0, npm.
set -eu

cd "$(dirname "$0")"
PID_FILE="$PWD/.start.pids"

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

PID_FILE_TMP="$PID_FILE.$$"
printf '%s\n%s\n' "$BACKEND_PID" "$FRONTEND_PID" > "$PID_FILE_TMP"
mv "$PID_FILE_TMP" "$PID_FILE"

# A pid plus all of its descendants (npm -> sh -> vite). The backend's java is a child
# of the Gradle daemon, not of gradlew; the daemon kills it when the gradlew client dies.
tree() {
  for c in $(pgrep -P "$1" 2>/dev/null); do tree "$c"; done
  echo "$1"
}

stop() {
  trap - INT TERM EXIT
  echo
  echo "==> stopping"
  pids=$(tree "$BACKEND_PID"; tree "$FRONTEND_PID")
  rm -f "$PID_FILE" "$PID_FILE_TMP"
  kill $pids 2>/dev/null || true
  # Vite's graceful shutdown sometimes hangs; give everything a moment, then force it.
  for _ in 1 2 3 4 5; do
    kill -0 $pids 2>/dev/null || break
    sleep 1
  done
  kill -KILL $pids 2>/dev/null || true
  wait 2>/dev/null || true
}
trap stop INT TERM EXIT

# Exit as soon as either process dies (e.g. port in use); the trap stops the other.
while kill -0 "$BACKEND_PID" 2>/dev/null && kill -0 "$FRONTEND_PID" 2>/dev/null; do
  sleep 1
done
