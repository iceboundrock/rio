# Repository rules

This repository is optimized for short AI-assisted coding exercises. This file holds only the rules
that apply to every change. Each major subtree has its own `AGENTS.md` with the rules for that code:
read this file, then the file for the subtree you are editing. The most specific applicable
`AGENTS.md` wins.

## Purpose

This project exists to learn and practise AI coding. It is not a product and has no external users.
Unless a task explicitly says otherwise, do not preserve compatibility across code changes: renaming
an API path, a table, a schema, or a type is fine without shims, migrations, legacy-detection code, or
deprecation periods. Document breaking changes in the PR description and move on.

## General

- Inspect existing patterns before editing. Copy the neighbouring style.
- Prefer minimal diffs. Do not refactor unrelated code.
- Do not add dependencies unless the task requires them.
- Keep the ownership boundaries in the map below explicit. Do not move logic across them to save a file.
- Run the relevant tests after modifying code; each scope's `AGENTS.md` names what to run. `./verify.sh` runs everything CI runs.

## Repository map

| Path | What lives there | Rules |
|---|---|---|
| `backend/` | Kotlin / Ktor HTTP API over SQLite through plain JDBC | `backend/AGENTS.md` |
| `frontend/` | React / TypeScript single-page app; talks to the backend only over HTTP | `frontend/AGENTS.md` |
| `contracts/schemas/` | JSON Schema for every HTTP shape; both sides validate against it. Any API shape change starts here | `contracts/AGENTS.md` |
| `features/` | One Markdown spec per feature; agree on it before implementing | `features/README.md` |

## Directory structure

Before creating or moving files, follow `directory-structure.md` at the repository root.

### Backend

The Kotlin/Ktor backend is organized by feature, not by technical layer.

- Add backend code to the feature package under `ai/project/rio/` that owns the behavior (`cardtransaction/` is the reference).
- Keep Route, Service, Repository, domain model and DTO files inside their owning feature, flat, with no `model/` or other sub-package.
- Do not create `routes/`, `services/`, `repositories/` or `models/` packages at any level.
- Name the core files `<Feature>.kt`, `<Feature>Dtos.kt`, `<Feature>Routes.kt`, `<Feature>Service.kt`, `<Feature>Repository.kt`; further feature-owned files are `<Feature><Concern>.kt` (`CardTransactionRequestFingerprint.kt`, `CardTransactionIdempotencyRepository.kt`). No `Impl` or interface for a single implementation.
- A new feature is wired by hand in `Application.kt`: construct its Service, register its `Route.<feature>Routes()`.
- Put code in `db/`, `http/` or `money/` only when it is JDBC, HTTP/JSON or money mechanics with no feature knowledge; caller count is not the test. Do not add `common/`, `shared/` or `util/` packages.
- Table DDL and seed rows go in `db/SchemaInitializer.kt`; application exceptions that map to an HTTP status are declared in `http/ApiError.kt` and mapped in `http/ErrorHandling.kt`.
- `http/` and `money/` never import a feature package. `db/SchemaInitializer.kt` is the only shared file allowed to.
- Backend tests mirror the main package as `<Class>Test.kt`; route tests schema-validate real responses with `contract/JsonSchemaAssertions`.

### Frontend

- `src/api/` is the only place that calls `fetch` or handles wire shapes; one endpoint module per resource, wire types in `src/api/schemas.ts`. The one exception is `MoneyJson` and its conversion, which stay in `src/money/money.ts` and are called only from `src/api/`.
- Domain types go in `src/types/`, money helpers in `src/money/money.ts`, routed screens in `src/pages/` (registered in `App.tsx`), everything else UI in `src/components/`.
- Tests sit next to the file they test as `<name>.test.ts(x)`; DOM-driven tests are `<Name>.interaction.test.tsx`.
- `src/api/validators.generated.{js,d.ts}` is generated from `contracts/schemas`; never edit it by hand.

### Contracts and specs

- A new or changed HTTP shape is a new or changed `contracts/schemas/<kebab-case>.schema.json` first; then backend DTOs, then frontend wire types, then `cd frontend && pnpm run generate:validators`.
- A new feature gets `features/<kebab-case>.md` before code.

## Money

Money is a domain value: an integer amount in the currency's minor unit plus the currency. This
invariant holds in every scope; each scope's `AGENTS.md` says how its code expresses it.

- Never represent money as a floating-point number, and never assume every currency has 2 decimal places.
- Currency is part of the value. Never add, subtract, compare, aggregate, sort, or filter amounts across different currencies as if they were one.
- A `CardTransaction` `amount` is a magnitude (> 0); direction comes from `type` (CREDIT/DEBIT).
- No FX conversion unless a task explicitly requires it.

## AutoForge

- Do not edit the active feature specification during an AutoForge execution run.
