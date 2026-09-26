# Backend rules

Applies to everything under `backend/`. The root `AGENTS.md` still applies; this file adds the
Kotlin / Ktor / JDBC rules and the server-side financial-correctness rules.

Source paths below are relative to `backend/src/main/kotlin/ai/project/rio/`.

## Architecture

- Ktor only; do not introduce Spring.
- Plain JDBC only; do not introduce an ORM, Exposed, jOOQ, or a DI container.
- Keep the layering explicit: Route -> Service -> Repository -> JdbcExecutor -> PostgreSQL. The executor is a standalone `JdbcTemplate` or the transaction-bound executor inside `transactional { tx -> }`; a repository takes `JdbcExecutor`, never `JdbcTemplate`.
- Feature query and DML SQL lives in concrete repositories (`cardtransaction/CardTransactionRepository.kt`); table DDL is the `SchemaInitializer.kt` exception described below. Always bind values with `?` parameters; never interpolate.
- `db/JdbcTemplate.kt` handles JDBC mechanics only. It must not learn about Money or any domain type.
- Business rules belong in services (`cardtransaction/CardTransactionService.kt`).
- HTTP translation belongs in routes and `http/ErrorHandling.kt`. Every application exception that becomes an HTTP error, whichever capability raises it, is declared in `http/ApiError.kt` and mapped in `http/ErrorHandling.kt`. Reuse an existing exception when its HTTP meaning fits; a new status or error code adds an exception class there, a mapping in `http/ErrorHandling.kt`, and the code to the enum in `contracts/schemas/api-error.schema.json`. A capability package declares no exception classes and registers no `StatusPages` handlers.
- Error responses never echo request values or parser diagnostics and never expose stack traces or SQL. A body the parser rejects answers a fixed message (`malformed request body`), and an unexpected exception is logged and answers 500 `internal server error`.
- Do not create generic repository hierarchies or interfaces with a single implementation.
- Schema DDL lives in `db/SchemaInitializer.kt`; nothing else creates tables. There are no migrations: edit the DDL and recreate the database (for the `./start.sh` container: `docker rm -f rio-postgres && docker volume rm rio-postgres-data`, then `./start.sh`).
- Keep the startup schema-drift guard in `SchemaInitializer.initialize`: tables are created only for a fresh database (no relation in the current schema has an expected name); otherwise every expected table must exist and match the current DDL, or startup refuses with reset instructions before running any DDL. A table matches when PostgreSQL describes it the same way as the DDL: relation kind, columns in order with type, `NOT NULL` and default, and the sorted `pg_get_constraintdef` of its constraints. The expected side is rendered by running the DDL in a scratch schema inside a rolled-back transaction; read the live side on the connection's normal `search_path`, never inside that transaction, or its foreign keys render schema-qualified and never match. Indexes that back no constraint, triggers, grants, replica identity and constraint names are not compared. `SchemaInitializerTest` covers it. Do not add code that migrates an old database, completes one that has only some of the tables, or keeps serving it; a mismatch is always a reset.

## Money

- Backend Money is `money/Money.kt`: `Long` minor units plus a `Currency`. Reuse `Money`, `Currency`, `Ratio`, and `MoneyRounding`; do not add a parallel representation.
- The floating-point representations to avoid in this scope are Kotlin `Double` / `Float` and the PostgreSQL `real` / `double precision` column types. Amounts are not `numeric` either: a column that can hold a fraction of a minor unit is not money.
- Use `Currency.precision` for decimal places; never hard-code 2.
- `Money` arithmetic is checked (`Math.addExact` and friends) and throws on mixed currencies. Do not replace it with wrapping arithmetic or catch the mismatch and continue.
- Splitting and allocation must preserve every minor unit: use `Money.split` and `Money.allocate`.
- Percentage calculations use an integer `Ratio` plus an explicit `MoneyRounding` through `Money.multiply`; never `amount * 0.0825`.
- Money travels over HTTP as an integer string plus currency (see `contracts/AGENTS.md`); `cardtransaction/CardTransactionDtos.kt` converts it to `Money` at the edge, and services and repositories only ever see `Money`.
- PostgreSQL stores `amount_minor BIGINT` + `currency TEXT` (`INTEGER` is 32-bit there). That row shape must not leak above the repository.

In SQL, any aggregate, comparison or ordering that involves `amount_minor` must also involve the
`currency` column. Ordering or filtering by other columns (`created_at`, `id`, `status`) needs no
currency scope. Global ordering by `amount_minor` across currencies is meaningless; scope it by
currency or do not offer it.

    -- Bad: mixes USD, EUR and JPY minor units
    SELECT SUM(amount_minor) FROM card_transactions;
    SELECT * FROM card_transactions WHERE amount_minor >= ?;

    -- Good: currency is always part of the semantics
    SELECT currency, SUM(amount_minor) FROM card_transactions GROUP BY currency;
    SELECT * FROM card_transactions WHERE currency = ? AND amount_minor >= ?;

## Transactions, atomicity and concurrency

- Services that coordinate multiple JDBC statements in one logical write extend `db/TransactionalService`, take `JdbcTemplate`, and run the write inside `transactional { tx -> ... }`, constructing every participating repository from `tx`. Outside a service, multi-statement writes go inside `jdbc.withTransaction { tx -> ... }`.
- Every statement in one logical transaction runs on the transaction-bound `tx` executor. A call on the outer `JdbcTemplate` from inside the block opens a second connection and is not part of the transaction. Nested `withTransaction` calls are not supported.
- Any write to consistency-sensitive financial state (a balance, a credit limit, a spending limit, a counter) is one atomic operation. Do not read a value, check it in Kotlin, and then write the new value as separate statements. Put the condition in the statement (`UPDATE ... SET ... WHERE ... AND balance_minor >= ?`), rely on a `UNIQUE` or `CHECK` constraint, or use a version column for optimistic concurrency, and do it inside one transaction.
- Check the row count that `update` returns whenever correctness depends on it: zero affected rows on a conditional update means the condition failed, so raise the domain error instead of reporting success.
- Connections keep PostgreSQL's default READ COMMITTED isolation; do not set another level. A write holds row locks that a concurrent write to the same row or key waits on until commit, and a transaction holds one of the pool's 10 connections (`db/Database.kt`) from start to end. Keep transactions short so they release both, and never make an external network call from inside a transaction. If a workflow needs both, record the intent in the database first and perform the external call afterwards (an outbox or compensation step) rather than stretching the transaction across the network.
- Retry a failed transaction only when the whole block is safe to run again; a block that has already produced a side effect outside the database is not.

## Cross-capability access

- A capability depends on another only through the owner's service and domain types (for card transactions, `cardtransaction/CardTransactionService.kt` and `cardtransaction/CardTransaction.kt`). Never import another capability's repositories, DTOs, routes, or other owned concerns: the owner's business rules run only inside its service.
- `Application.kt` constructs the owner before the consumer and passes the owner's service through the consumer's constructor. Dependencies between capabilities must not form a cycle; when two capabilities each need the other, the shared logic belongs to one of them or to a new capability, and the work item records which.
- To run an owner's write inside the consumer's transaction, the owner exposes a service operation that takes the transaction-bound executor (`fun reserve(tx: JdbcExecutor, ...)`) and constructs its repositories from `tx`; the consumer calls it from inside its own `transactional { tx -> ... }`. The owner's standalone variant wraps the same operation in its own `transactional`. This is the only way to share a transaction: calling the owner's standalone method from inside the block opens a second connection, and nested `withTransaction` is not supported.
- The owner's transaction-bound operation raises its domain error and neither catches it nor commits; the consumer's block decides the outcome, and a failure rolls back both capabilities' statements together.

## Idempotency

`POST /api/card-transactions` is idempotent through a required `Idempotency-Key` header (spec:
`features/idempotent-card-transaction-create.md`). The route reads and validates the header before
the body; `CardTransactionService` validates and normalizes every item, computes
`CardTransactionRequestFingerprint` from the domain values, and inside `transactional {}` claims
the key as the first statement (`CardTransactionIdempotencyRepository.claim`, an `INSERT ... ON
CONFLICT DO NOTHING`). It then inserts the rows and ordered id mapping, replays from stored ids, or
throws `IdempotencyConflictException` (422). A future operation's retry and idempotency behavior
belongs in that work item's specification; do not infer it from this endpoint.

## Tests

- Tests run with `./gradlew test` from `backend/` and use real components: an empty PostgreSQL database per test method from `db/PostgresTestDatabase.kt` for repositories and services, and `testApplication` for routes. The fixture starts `postgres:17` through Testcontainers, so `./gradlew test` needs a running Docker daemon.
- Route tests must validate application-generated JSON responses with `JsonSchemaAssertions` (`assertMatchesSchema` / `assertViolatesSchema`) against the files in `contracts/schemas/`. For bodyless or engine-generated responses, assert the applicable status, headers, or body directly.
- A change to a financial invariant needs automated coverage for the cases that apply: precision and rounding per currency, mixed currencies, zero, negative and boundary amounts, `Long` overflow, transaction rollback, concurrent execution, and duplicate or retried requests. Behaviour that depends on transaction semantics is tested through the database, not with mocks: `CardTransactionServiceTest` forces a mid-transaction failure with a PL/pgSQL trigger and races real threads on one database.
- Financially significant operations stay traceable. If a task adds audit logging, log a structured record with the actor, the operation, amount and currency, the transaction or correlation identifier, the idempotency key if any, and the outcome; never log secrets or full card data.
