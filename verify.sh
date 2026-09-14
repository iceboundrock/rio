#!/bin/sh
# Runs everything CI would: backend tests, frontend tests, frontend production build.
# Requires: JDK 25, Node 24 (>=24.21.0, the current LTS), pnpm (the version pinned by `packageManager` in
# frontend/package.json; `corepack enable pnpm` provides it). Nothing here is needed to run the app itself.
set -eu

cd "$(dirname "$0")"

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
