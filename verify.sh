#!/bin/sh
# Runs everything CI would: backend tests, frontend tests, frontend production build.
# Requires: JDK 25, Node ^22.12.0, ^24.0.0 or >=26.0.0, npm. Nothing here is needed to run the app itself.
set -eu

cd "$(dirname "$0")"

echo "==> backend: ./gradlew test"
(cd backend && ./gradlew test --quiet)

echo "==> frontend: npm install"
(cd frontend && npm install --no-audit --no-fund --loglevel=error)

# The schema validators are generated from contracts/schemas and checked in; a stale artifact means
# a schema changed without `npm run generate:validators` being run and committed.
echo "==> frontend: npm run generate:validators (must match the checked-in artifact)"
(cd frontend && npm run generate:validators)
if [ -n "$(git status --porcelain -- frontend/src/api/validators.generated.js frontend/src/api/validators.generated.d.ts)" ]; then
  echo "error: frontend/src/api/validators.generated.{js,d.ts} are stale; commit the regenerated files" >&2
  git --no-pager diff --stat -- frontend/src/api/validators.generated.js frontend/src/api/validators.generated.d.ts >&2
  exit 1
fi

# --disallow-code-generation-from-strings makes Node throw on eval / new Function, so a validator
# that quietly went back to runtime compilation fails here instead of in a browser with a strict CSP.
echo "==> frontend: npm test -- --run (eval disabled)"
(cd frontend && NODE_OPTIONS=--disallow-code-generation-from-strings npm test -- --run)

echo "==> frontend: npm run build"
(cd frontend && npm run build)

echo "==> all checks passed"
