#!/bin/sh
# Stops the backend and frontend launched by ./start.sh by asking that run to shut down;
# its trap kills both servers (up to ~5s grace, then SIGKILL), stops the PostgreSQL container if
# that run started it, and removes the pid file.
set -eu

cd "$(dirname "$0")"
PID_FILE="$PWD/.start.pids"

if [ ! -f "$PID_FILE" ]; then
  echo "==> no servers started by ./start.sh"
  exit 0
fi

pid=
IFS= read -r pid < "$PID_FILE" || true

# True when $1 is the pid of a running start.sh. The pid file can outlive a crashed run and
# the OS may hand its pid to something else, so check the command line, not just liveness.
is_start_sh() {
  case "$1" in ''|0|*[!0-9]*) return 1 ;; esac
  kill -0 "$1" 2>/dev/null || return 1
  case "$(ps -o args= -p "$1" 2>/dev/null)" in */start.sh|*" start.sh") return 0 ;; *) return 1 ;; esac
}

if ! is_start_sh "$pid"; then
  rm -f "$PID_FILE"
  echo "==> stale .start.pids (./start.sh${pid:+ pid $pid} is not running); removed"
  exit 0
fi

echo "==> stopping ./start.sh (pid $pid)"
kill -TERM "$pid"
# 20s: the servers' grace period plus `docker stop` of the container.
for _ in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20; do
  kill -0 "$pid" 2>/dev/null || { echo "==> stopped"; exit 0; }
  sleep 1
done
echo "==> ./start.sh (pid $pid) is still running after 20s" >&2
exit 1
