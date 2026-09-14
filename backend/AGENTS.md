# Backend Rules

Applies to everything under `backend/`. The root `AGENTS.md` still applies; this file adds the
Kotlin / Ktor / JDBC rules and the server-side financial-correctness rules.

Source paths below are relative to `backend/src/main/kotlin/ai/project/rio/`; the database file is named from the repository root.

## Architecture

- Ktor only; do not introduce Spring.
- Plain JDBC only; do not introduce an ORM, Exposed, jOOQ, or a DI container.
- Keep the layering explicit: Route -> Service -> Repository -> JdbcTemplate -> SQLite.
- SQL lives in concrete repositories (`cardtransaction/CardTransactionRepository.kt`). Always bind values with `?` parameters; never interpolate.
- `db/JdbcTemplate.kt` handles JDBC mechanics only. It must not learn about Money or any domain type.
- Business rules belong in services (`cardtransaction/CardTransactionService.kt`).
- HTTP translation belongs in routes and `http/ErrorHandling.kt`. Throw `ValidationException` (400) or `NotFoundException` (404).
- Do not create generic repository hierarchies or interfaces with a single implementation.
- Schema DDL lives in `db/SchemaInitializer.kt`. There are no migrations: edit the DDL and delete `backend/data/rio.db`. Do not add code to detect or migrate old databases.

## Money

- Backend Money is `money/Money.kt`: `Long` minor units plus a `Currency`. Reuse `Money`, `Currency`, `Ratio`, and `MoneyRounding`; do not add a parallel representation.
- The floating-point representations to avoid in this scope are Kotlin `Double` / `Float` and the SQLite `REAL` column type.
- Use `Currency.precision` for decimal places; never hard-code 2.
- `Money` arithmetic is checked (`Math.addExact` and friends) and throws on mixed currencies. Do not replace it with wrapping arithmetic or catch the mismatch and continue.
- Splitting and allocation must preserve every minor unit: use `Money.split` and `Money.allocate`.
- Percentage calculations use an integer `Ratio` plus an explicit `MoneyRounding` through `Money.multiply`; never `amount * 0.0825`.
- Money travels over HTTP as an integer string plus currency (see `contracts/AGENTS.md`); `cardtransaction/CardTransactionDtos.kt` converts it to `Money` at the edge, and services and repositories only ever see `Money`.
- SQLite stores `amount_minor INTEGER` + `currency TEXT`. That row shape must not leak above the repository.

In SQL the currency column is part of every aggregate, comparison and ordering. Global ordering by
`amount_minor` across currencies is meaningless; scope it by currency or do not offer it.

    -- Bad: mixes USD, EUR and JPY minor units
    SELECT SUM(amount_minor) FROM card_transactions;
    SELECT * FROM card_transactions WHERE amount_minor >= ?;

    -- Good: currency is always part of the semantics
    SELECT currency, SUM(amount_minor) FROM card_transactions GROUP BY currency;
    SELECT * FROM card_transactions WHERE currency = ? AND amount_minor >= ?;

## Transactions, atomicity and concurrency

- Services that write more than one row extend `db/TransactionalService`, take `JdbcTemplate`, and run the write inside `transactional { tx -> ... }`, constructing every participating repository from `tx`. Outside a service, multi-statement writes go inside `jdbc.withTransaction { tx -> ... }`.
- Every statement in one logical transaction runs on the transaction-bound `tx` executor. A call on the outer `JdbcTemplate` from inside the block opens a second connection and is not part of the transaction. Nested `withTransaction` calls are not supported.
- Any write to consistency-sensitive financial state (a balance, a credit limit, a spending limit, a counter) is one atomic operation. Do not read a value, check it in Kotlin, and then write the new value as separate statements. Put the condition in the statement (`UPDATE ... SET ... WHERE ... AND balance_minor >= ?`), rely on a `UNIQUE` or `CHECK` constraint, or use a version column for optimistic concurrency, and do it inside one transaction.
- Check the row count that `update` returns whenever correctness depends on it: zero affected rows on a conditional update means the condition failed, so raise the domain error instead of reporting success.
- SQLite serializes writers and connections wait up to the busy timeout configured in `db/Database.kt`. Keep transactions short so they do not hold that lock, and never make an external network call from inside a transaction. If a workflow needs both, record the intent in the database first and perform the external call afterwards (an outbox or compensation step) rather than stretching the transaction across the network.
- Retry a failed transaction only when the whole block is safe to run again; a block that has already produced a side effect outside the database is not.

## Idempotency

The API has no idempotency mechanism today, and adding one to an endpoint that does not need it is out of scope. When a task adds a server-side operation that may be retried and has a financial or otherwise non-repeatable side effect (a charge, a refund, a transfer, a webhook or event consumer, a retryable mutation endpoint), design its idempotency explicitly:

- Persist the idempotency key together with enough of the request (for example a hash of the body) to detect the same key being reused for a different request, and reject that reuse.
- Enforce key uniqueness in the database with a `UNIQUE` constraint, inserted inside the same transaction as the side effect, so two concurrent requests with the same key cannot both execute.
- A replay of the same logical request returns the stored result and performs no new side effect.
- Define retention and cleanup for stored keys when the table is introduced, in the DDL and in the spec.
- If an external provider supports idempotency, pass the key through and store the provider's identifier alongside the local record.

## Tests

- Tests run with `./gradlew test` from `backend/` and use real components: `Database.open` on a temporary SQLite file for repositories and services, and `testApplication` for routes.
- Route tests must validate real HTTP responses with `JsonSchemaAssertions` (`assertMatchesSchema` / `assertViolatesSchema`) against the files in `contracts/schemas/`.
- A change to a financial invariant needs automated coverage for the cases that apply: precision and rounding per currency, mixed currencies, zero, negative and boundary amounts, `Long` overflow, transaction rollback, concurrent execution, and duplicate or retried requests. Behaviour that depends on transaction semantics is tested through the database, not with mocks.
- Financially significant operations stay traceable. If a task adds audit logging, log a structured record with the actor, the operation, amount and currency, the transaction or correlation identifier, the idempotency key if any, and the outcome; never log secrets or full card data.
