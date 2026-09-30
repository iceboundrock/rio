#!/bin/sh
# Runs everything CI would: the compose file check, backend tests, frontend tests, frontend production build.
# Requires: JDK 25, Node 24 (>=24.21.0, the current LTS), pnpm (the version pinned by `packageManager` in
# frontend/package.json; `corepack enable pnpm` provides it), a running Docker daemon, because backend
# tests start PostgreSQL in a container through Testcontainers, and Docker Compose 2.20.2 or later.
# Nothing here is needed to run the app itself.
set -eu

cd "$(dirname "$0")"

# docker-compose.cdc.yml is otherwise read only by a manual `up`.
echo "==> docker-compose.cdc.yml: docker compose config, once per profile; every service has a profile and is in full"
# Without the Compose plugin, the Docker CLI reads `compose -f` as its own flags and fails with
# "unknown shorthand flag", which does not say what is missing.
docker compose version >/dev/null || {
  echo "error: ./verify.sh needs Docker Compose 2.20.2 or later, and \`docker compose version\` fails" >&2
  exit 1
}
# Compose rejects a profile whose service depends on one outside it, and `--profile '*'` would hide that,
# so each profile is loaded on its own; any combination of valid profiles is valid. The assignment makes
# an unparsable file fail here. `config --services`, because before Compose 2.26 `config --quiet` accepts
# such a profile, which `up` then refuses.
profiles="$(docker compose -f docker-compose.cdc.yml config --profiles)"
for profile in $profiles; do
  docker compose -f docker-compose.cdc.yml --profile "$profile" config --services >/dev/null || {
    echo "error: docker-compose.cdc.yml is invalid with only --profile $profile" >&2
    exit 1
  }
done
# The header's `--profile full up` is the whole playground, so every service is in `full`.
services="$(docker compose -f docker-compose.cdc.yml --profile '*' config --services)"
full_services="$(docker compose -f docker-compose.cdc.yml --profile full config --services)"
for service in $services; do
  printf '%s\n' "$full_services" | grep -qxF "$service" || {
    echo "error: service $service in docker-compose.cdc.yml is not in profile full" >&2
    exit 1
  }
done
# The header's bare `down` skips every service only if every service has a profile, so with no profile
# active, nothing is listed. An exported COMPOSE_PROFILES would activate its profiles, so it is emptied.
unprofiled="$(COMPOSE_PROFILES= docker compose -f docker-compose.cdc.yml config --services)"
for service in $unprofiled; do
  echo "error: service $service in docker-compose.cdc.yml has no profile" >&2
  exit 1
done

echo "==> backend: ./gradlew test"
(cd backend && ./gradlew test --quiet)

echo "==> frontend: pnpm install --frozen-lockfile"
(cd frontend && pnpm install --frozen-lockfile --loglevel=error)

# The schema validators are generated from contracts/schemas and checked in; a stale artifact means
# a schema changed without `pnpm run generate:validators` being run and its output committed.
# Compare file contents rather than git status so a regenerated-but-uncommitted artifact passes locally.
echo "==> frontend: pnpm run generate:validators (must match the checked-in artifact)"
generated_before="$(mktemp -d)"
trap 'rm -rf "$generated_before"' EXIT
cp frontend/src/api/validators.generated.js frontend/src/api/validators.generated.d.ts "$generated_before/"
(cd frontend && pnpm run generate:validators)
for f in validators.generated.js validators.generated.d.ts; do
  if ! cmp -s "$generated_before/$f" "frontend/src/api/$f"; then
    echo "error: frontend/src/api/$f was stale (regenerated now); commit the regenerated files" >&2
    exit 1
  fi
done

# vitest.config.ts starts every test worker with --disallow-code-generation-from-strings, so a validator
# that quietly went back to runtime compilation fails here instead of in a browser with a strict CSP.
# `pnpm exec vitest` rather than `pnpm test`: the test script regenerates the validators first, and the
# generator already ran above with its output checked against the committed artifact, so running it
# again here would only rewrite the same files.
echo "==> frontend: pnpm exec vitest --run (eval disabled in test workers)"
(cd frontend && pnpm exec vitest --run)

echo "==> frontend: pnpm run build"
(cd frontend && pnpm run build)

echo "==> all checks passed"
