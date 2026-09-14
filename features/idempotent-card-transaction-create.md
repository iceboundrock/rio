# Feature: Idempotent card transaction create (`Idempotency-Key`)

Tracks rio issue #48.

## Problem

`POST /api/card-transactions` persists rows, but a client that loses the connection after the server
committed cannot tell whether the write happened. Retrying today creates a second card transaction
(or a second batch) for one intended operation. The endpoint needs a client-supplied key that makes
a retry replay the committed result instead of writing again.

## Requirements

### HTTP contract

- Every `POST /api/card-transactions` request carries exactly one `Idempotency-Key` header.
  Opaque, case-sensitive, 1..255 characters, no control characters, not whitespace-only. UUIDs are
  the recommended client value; the server attaches no meaning to it.
- Missing, blank, malformed, over-long, or repeated header → 400 `VALIDATION_ERROR`
  (`missing Idempotency-Key header` / `invalid Idempotency-Key header` /
  `multiple Idempotency-Key headers`). Nothing is persisted and the key is not consumed.
  Caveat: Netty rejects C0 control characters and DEL in any header value while decoding, with its
  own plain-text 400, before Ktor runs; the route's check covers C1 controls and everything else.
- First valid use of a key: create as today, answer 201 with the existing representation.
- Same key + same logical request after commit: answer 201 with the *same* representation (same ids,
  `createdAt`, order); insert nothing. No replay indicator, no 200.
- Same key + different logical request: 422 `IDEMPOTENCY_CONFLICT`
  (`Idempotency-Key was already used with a different request`), no write.
- `api-error.schema.json` gains `IDEMPOTENCY_CONFLICT`; the request schema's description names the
  required header.

### Request identity

- The fingerprint is a SHA-256 hex digest of a canonical string built from the *validated domain
  inputs*: request shape (`ONE` for an object body, `MANY` for an array), item order, and per item the
  trimmed description (length-prefixed so the encoding is unambiguous), amount in minor units,
  currency code and type. Generated ids and timestamps are excluded.
- `{...}` and `[{...}]` with the same item are different shapes and therefore conflict.
- JSON member order and whitespace cannot influence the fingerprint because it is computed from
  domain values, never from the JSON text. No `hashCode()`.
- The fingerprint code lives beside the service in `cardtransaction/`, not in `db/`.

### Persistence and atomicity

- Two tables in `SchemaInitializer`, created after `card_transactions`:
  `card_transaction_idempotency (idempotency_key PK, request_fingerprint, request_shape CHECK IN ('ONE','MANY'), created_at)`
  and `card_transaction_idempotency_items (idempotency_key FK ON DELETE CASCADE, item_index >= 0, card_transaction_id FK, PK (key, index))`.
  The startup drift guard covers all three tables.
- SQL lives in a new `CardTransactionIdempotencyRepository`; no generic idempotency abstraction.
- Service flow: validate and normalize every item → compute fingerprint → `transactional { tx -> }`:
  first statement is `INSERT ... ON CONFLICT(idempotency_key) DO NOTHING`. Claimed → insert the card
  transactions and the ordered item mapping, commit. Not claimed → load the record; fingerprint or
  shape differ → throw (rollback) `IdempotencyConflictException`; else load the original transactions
  in item order and return them.
- Every statement runs on the transaction-bound executor. A batch stays all-or-nothing together
  with its idempotency rows.

### Failure semantics

- Rejected before the transaction (Accept, Content-Type, malformed JSON, DTO, unsupported
  currency/type, blank description, non-positive amount, invalid batch item): key not consumed.
- Transaction rollback for any reason rolls back the claim; the key may be retried.
- After commit the key is consumed even if the response is lost; the next request replays.
- No expiry or cleanup: keys live as long as the database file. Reset-only schema evolution.

### Concurrency

- The primary key on `idempotency_key` is the only authority. No JVM locks, no ordering assumptions.
- Same key + same payload in parallel: one creates, the rest wait on the SQLite writer and replay.
  All success responses carry the same ids; exactly one row set exists.
- Same key + different payloads in parallel: one wins; the others get 422 and persist nothing.

### Frontend

- `postJson` accepts optional request headers; card-transaction functions set `Idempotency-Key`.
- `createCardTransaction(input, idempotencyKey)` / `createCardTransactions(inputs, idempotencyKey)`
  take the key explicitly so a retry of the same operation can reuse it.
- `CreateCardTransactionsForm` generates one `crypto.randomUUID()` per submission. No automatic
  transport retries are added.
- `ApiErrorJson.code` includes `IDEMPOTENCY_CONFLICT`.

### Documentation

- README API section: header required, replay and conflict behaviour, 422 in the error table, and
  that idempotency is keyed by client intent, not payload deduplication. Remove "idempotency" from
  the "Not implemented on purpose" list.
- `backend/AGENTS.md`, `frontend/AGENTS.md`, `contracts/AGENTS.md`: replace "no idempotency
  mechanism today" with the mechanism this endpoint has and the rules for extending it.

## Acceptance Criteria

- [x] POST without `Idempotency-Key` → 400, nothing persisted.
- [x] Blank / control-character / >255-char / repeated keys → 400, nothing persisted.
- [x] First single-object POST → 201, exactly one row.
- [x] Replay with the same key → 201, byte-identical body, no new row.
- [x] First batch POST creates all items atomically; replay returns the same ids in the same order, no new rows.
- [x] Object vs one-element array with the same key → 422.
- [x] Same key, different request → 422 `IDEMPOTENCY_CONFLICT`, no write.
- [x] Same payload, different keys → distinct rows.
- [x] JSON whitespace / member order alone → replay, not conflict.
- [x] Failed validation does not consume the key; the key then works with a valid request.
- [x] A rolled-back batch leaves no idempotency record and the key can be retried.
- [x] Replay works after reopening the same SQLite file with a new service instance.
- [x] Parallel same-key/same-payload → one logical creation, all successes identical.
- [x] Parallel same-key/different-payload → only the winner persisted, losers 422.
- [x] All real HTTP responses still validate against `contracts/schemas`.
- [x] Frontend sends a fresh key per submission and maps 422 to `ApiError` with code `IDEMPOTENCY_CONFLICT`.
- [x] README, this spec and the scoped `AGENTS.md` files describe the contract.
- [x] `./verify.sh` passes.

## Non-goals

- Business-level duplicate detection: two keys with identical data are two transactions.
- Redis, ORM, distributed locks, in-memory caches, automatic client retries, TTL/cleanup,
  migrations, a generic idempotency framework, exactly-once guarantees beyond this endpoint's rows.

## Notes / Decisions

- The header is checked before the body is read: the key identifies the operation, so a client that
  forgot it gets that message regardless of what else is wrong.
- Item validation moves from inside the transaction to a pre-pass so the fingerprint is computed from
  normalized values and the key claim is the first statement. An invalid item still leaves nothing
  behind, now because no transaction is opened.
- Replay reconstructs the response from the stored transaction ids because card transactions are
  immutable. Mutable transactions would require storing a response snapshot instead.
- Testing a mid-transaction rollback uses a SQLite trigger created by the test
  (`RAISE(ABORT)` on a marker description) rather than a mock, keeping the rule that transaction
  behaviour is tested through the database.
- The `created_at` of the idempotency record is the same instant stamped on the created transactions.
