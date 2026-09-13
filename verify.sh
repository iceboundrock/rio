#!/bin/sh
# Runs everything CI would: backend tests, frontend tests, frontend production build.
# Requires: JDK 25, Node ^22.12.0, ^24.0.0 or >=26.0.0, npm. Nothing here is needed to run the app itself.
set -eu

cd "$(dirname "$0")"

echo "==> backend: ./gradlew test"
(cd backend && ./gradlew test --quiet)

echo "==> frontend: npm install"
(cd frontend && npm install --no-audit --no-fund --loglevel=error)

echo "==> frontend: npm test -- --run"
(cd frontend && npm test -- --run)

echo "==> frontend: npm run build"
(cd frontend && npm run build)

echo "==> all checks passed"
