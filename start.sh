#!/bin/sh
# Starts PostgreSQL (the rio-postgres container), backend (http://localhost:8080) and frontend
# (http://localhost:5173) together. Ctrl+C stops all three; a container that was already running is
# reused and left running. Requires: Docker, JDK 25, Node 24 (>=24.21.0, the current LTS), pnpm (see README.md).
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

PG_CONTAINER=rio-postgres
# postgres:17 is also the image the backend tests use (PostgresTestDatabase); bump both together.
PG_IMAGE=postgres:17
PG_STARTED=

if ! command -v docker >/dev/null 2>&1 || ! docker info >/dev/null 2>&1; then
  echo "==> Docker is required: the backend runs on PostgreSQL in the $PG_CONTAINER container. Install or start Docker and retry." >&2
  exit 1
fi

# Not --frozen-lockfile, unlike verify.sh and CI: this is the dev path, so a package.json edited
# since the last install should update pnpm-lock.yaml here rather than fail. pnpm freezes it by
# itself when it detects a CI environment.
if [ ! -d frontend/node_modules ]; then
  echo "==> frontend: pnpm install"
  (cd frontend && pnpm install --loglevel=error)
fi

# Stops the container only if this run started it; --rm removes it and the named volume keeps the data.
stop_postgres() {
  [ -n "$PG_STARTED" ] || return 0
  PG_STARTED=
  echo "==> postgres: docker stop $PG_CONTAINER   (data stays in volume rio-postgres-data)"
  docker stop "$PG_CONTAINER" >/dev/null || true
}

if [ "$(docker inspect -f '{{.State.Running}}' "$PG_CONTAINER" 2>/dev/null)" = true ]; then
  echo "==> postgres: reusing running container $PG_CONTAINER (left running on exit)"
else
  # Loopback only: the password is a fixed local default. wal_level=logical and init.sql (the CDC
  # role, grants and publication; the image runs it only on an empty volume) are for the CDC pipeline (#115).
  echo "==> postgres: docker run $PG_CONTAINER   (127.0.0.1:5432, volume rio-postgres-data)"
  docker run -d --rm --name "$PG_CONTAINER" \
    -p 127.0.0.1:5432:5432 \
    -v rio-postgres-data:/var/lib/postgresql/data \
    -v "$PWD/docker/postgres-rio/init.sql:/docker-entrypoint-initdb.d/init.sql:ro" \
    -e POSTGRES_USER=rio -e POSTGRES_PASSWORD=rio -e POSTGRES_DB=rio \
    "$PG_IMAGE" -c wal_level=logical >/dev/null
  PG_STARTED=1
fi
trap stop_postgres EXIT
trap 'exit 1' INT TERM

# Over TCP, not the socket: on first start the image initializes the database with a temporary
# server that accepts only socket connections, then restarts it.
waited=0
until docker exec "$PG_CONTAINER" pg_isready -q -h 127.0.0.1 -U rio -d rio; do
  waited=$((waited + 1))
  if [ "$waited" -ge 30 ]; then
    echo "==> postgres: not ready after 30s; last log lines:" >&2
    docker logs --tail 20 "$PG_CONTAINER" >&2 || true
    exit 1
  fi
  sleep 1
done

echo "==> backend: ./gradlew run   (http://localhost:8080)"
(cd backend && exec ./gradlew run --quiet --console=plain) &
BACKEND_PID=$!

echo "==> frontend: pnpm run dev   (http://localhost:5173)"
(cd frontend && exec pnpm run dev) &
FRONTEND_PID=$!

# A pid plus all of its descendants (pnpm -> sh -> vite). The backend's java is a child
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
  # After the backend, so its pool is gone before the server stops.
  stop_postgres
}
trap stop INT TERM EXIT

# Record our own pid so ./stop.sh can ask this run to shut down through the trap above.
printf '%s\n' "$$" > "$PID_FILE_TMP"
mv "$PID_FILE_TMP" "$PID_FILE"

# Exit as soon as either process dies (e.g. port in use); the trap stops the other.
while kill -0 "$BACKEND_PID" 2>/dev/null && kill -0 "$FRONTEND_PID" 2>/dev/null; do
  sleep 1
done
