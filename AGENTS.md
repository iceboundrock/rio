# Repository Rules

This repository is optimized for short AI-assisted coding exercises. Read this whole file; it is short.

## Purpose

This project exists to learn and practise AI coding. It is not a product and has no external users.
Unless a task explicitly says otherwise, do not preserve compatibility across code changes: renaming
an API path, a table, a schema, or a type is fine without shims, migrations, legacy-detection code, or
deprecation periods. Document breaking changes in the PR description and move on.

## General

- Inspect existing patterns before editing. Copy the neighbouring style.
- Prefer minimal diffs. Do not refactor unrelated code.
- Do not add dependencies unless the task requires them.
- Keep the architecture explicit: Route -> Service -> Repository -> JdbcTemplate -> SQLite.
- Run the relevant tests after modifying code (`backend/./gradlew test`, `frontend/npm test -- --run`, or `./verify.sh`).

## Backend (Kotlin / Ktor)

- Ktor only; do not introduce Spring.
- Plain JDBC only; do not introduce an ORM, Exposed, jOOQ, or a DI container.
- SQL lives in concrete repositories (`cardtransaction/CardTransactionRepository.kt`). Always bind values with `?` parameters; never interpolate.
- `db/JdbcTemplate.kt` handles JDBC mechanics only. It must not learn about Money or any domain type.
- Business rules belong in services (`cardtransaction/CardTransactionService.kt`).
- HTTP translation belongs in routes and `http/ErrorHandling.kt`. Throw `ValidationException` (400) or `NotFoundException` (404).
- Do not create generic repository hierarchies or interfaces with a single implementation.
- Multi-statement writes go inside `jdbc.withTransaction { tx -> ... }` and use `tx` for every statement.
- Schema DDL lives in `db/SchemaInitializer.kt`. There are no migrations: edit the DDL and delete `backend/data/rio.db`. Do not add code to detect or migrate old databases.

## Money

Money is a domain value: `integer minor-unit amount + Currency`.

- Never use Double or Float for money.
- Never use SQLite REAL for money.
- Never use JavaScript `number` for money arithmetic.
- Never assume every currency has 2 decimal places; use `Currency.precision`.
- Never add, subtract, or compare Money values with different currencies. The helpers already throw.
- HTTP money amounts are base-10 integer strings: `{ "amount": "1250", "currency": "USD" }`.
- Backend Money uses `Long` minor units (`money/Money.kt`). Frontend Money uses `bigint` (`src/money/money.ts`).
- SQLite stores `amount_minor INTEGER` + `currency TEXT`. That representation must not leak above the repository.
- CardTransaction `amount` is a magnitude (> 0); direction comes from `type` (CREDIT/DEBIT).
- Splitting and allocation must preserve every minor unit (`Money.split`, `Money.allocate`).
- Percentage calculations use an integer `Ratio` plus an explicit `MoneyRounding`; never `amount * 0.0825`.
- Arithmetic is checked (`Math.addExact` etc.). Do not replace it with wrapping arithmetic.
- No FX conversion unless a task explicitly requires it.

Never aggregate or compare mixed currencies as if they were one currency.

    -- Bad: mixes USD, EUR and JPY minor units
    SELECT SUM(amount_minor) FROM card_transactions;
    SELECT * FROM card_transactions WHERE amount_minor >= ?;

    -- Good: currency is always part of the semantics
    SELECT currency, SUM(amount_minor) FROM card_transactions GROUP BY currency;
    SELECT * FROM card_transactions WHERE currency = ? AND amount_minor >= ?;

Global ordering by `amount_minor` across currencies is meaningless; scope it by currency or do not offer it.

## Contracts

- JSON Schema under `contracts/schemas/` is the shared HTTP contract for both sides.
- Any API shape change requires updating the schema first, then backend DTOs, then frontend wire types.
- Backend route tests must validate real HTTP responses with `JsonSchemaAssertions`.
- Frontend runtime must validate fetched responses through `src/api/client.ts`.
- Do not copy or duplicate schemas anywhere. Do not hand-write a second version in TypeScript.
- Use `additionalProperties: false` and explicit enums on API objects.

## Frontend (React / TypeScript)

- Raw `fetch` belongs only under `src/api/`.
- Pages and components receive domain `Money` (`bigint`); they must not parse wire strings.
- Convert Money at the API boundary with `moneyFromJson` / `moneyToJson`.
- Keep UI state local (`useState`) unless a task truly requires otherwise.
- Do not add Redux, Zustand, React Query, Tailwind, or a component library unless explicitly required.
- Every page handles loading, error, contract-error, and empty states.

## AutoForge

- Feature requirements live under `features/` as one Markdown spec per feature.
- Do not edit the active feature specification during an AutoForge execution run.
