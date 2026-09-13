# Feature: Bulk card transactions (transactional service base)

## Problem

`JdbcTemplate.withTransaction` existed, but no service could start a transaction: `CardTransactionService`
received only a repository (rio issue #4). Future features that write several rows (transfers, fees, limits)
would each have to re-invent the wiring. There was also no way to create several card transactions at once.

## Requirements

- A reusable `db/TransactionalService` base class: services that own multi-statement writes extend it,
  receive `JdbcTemplate`, and run the write inside `transactional { tx -> ... }` with every repository built from `tx`.
- `CardTransactionService` extends it. `createAll(items)` validates and inserts every item in one transaction;
  `create` is the single-item case. Every item in a batch gets the same `createdAt`.
- `POST /api/card-transactions` accepts either one `CreateCardTransactionRequest` object (unchanged: 201 `CardTransaction`)
  or a non-empty array of them (201 `{ "items": [CardTransaction] }`). The array is all-or-nothing.
- Contract: `contracts/schemas/create-card-transactions-request.schema.json` (`oneOf` object / array with `minItems: 1`).
  The single-object schema is unchanged.
- Frontend: `createCardTransactions(inputs)` in `src/api/cardTransactions.ts`; a form on the list page that adds one
  or more rows and re-fetches the list on success. Amount text is parsed with `moneyFromDecimalString` (bigint only).

## Acceptance Criteria

- [x] `TransactionalServiceTest`: `transactional` commits every statement; a later failing statement rolls back the whole block.
- [x] `CardTransactionServiceTest`: `createAll` inserts every item and stamps one instant; rejects an empty list; rolls back every insert when a later item is invalid.
- [x] `CardTransactionRoutesTest`: array body creates every item and returns the list shape; single object still returns a bare `CardTransaction`; array is all-or-nothing; empty array is 400 `items must not be empty`; missing fields report the item index (`[1].amount.amount`); scalar bodies are 400.
- [x] Frontend: schema validator accepts object or non-empty array; `createCardTransactions` posts an array and validates the list response; `moneyFromDecimalString` respects currency precision; the form view renders rows, errors, and a disabled submit while saving.

## Non-goals

- Partial success or per-item status in the response.
- Idempotency keys, CSV import, editing or deleting card transactions.
- A separate batch endpoint.

## Notes / Decisions

- Base class rather than a repository registry: it is ~10 lines, knows only JDBC, and keeps `Route -> Service -> Repository -> JdbcTemplate` explicit.
- `CardTransactionService` now takes `JdbcTemplate` instead of a repository. Breaking for callers; this is a learning project with no compatibility requirement.
- The request body serializer reads the JSON tree to tell object from array, then decodes that tree from its *text* again.
  Only kotlinx's streaming decoder annotates `MissingFieldException` with the JSON path, which `ErrorHandling.kt` turns into
  `[1].amount.currency`; decoding the tree directly would report a bare `currency`. Cost: the body is parsed twice.
- Validation happens inside the transaction, per item, so a bad item N rolls back inserts 1..N-1 without a separate pre-pass.
- The list page re-fetches after a successful create instead of merging the returned items: the server assigns `createdAt` and the order.
