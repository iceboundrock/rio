# Rio Starter — Card Transactions

A deliberately small full-stack app: **Kotlin + Ktor + plain JDBC + SQLite** on the back,
**React + TypeScript + Vite** on the front, and **shared JSON Schema** contracts in between.
It exists to be understood in five minutes and extended in twenty. Read `AGENTS.md` before changing anything.

This is a learning project for practising AI-assisted coding, not a product. Unless a task says
otherwise, code changes do not need to stay compatible with earlier versions: paths, tables,
schemas, and types may be renamed freely, and an existing local database is simply deleted.

## Quick start

Prerequisites: JDK 25 (the default Gradle toolchain; CI also covers 21 and 17, see below), Node `>=24.21.0 <25.0.0` (only the current LTS line is supported, see #71) and pnpm (the version is pinned by `packageManager` in `frontend/package.json`; `corepack enable pnpm` installs it, see #74; bump it with `corepack use pnpm@<version>`, which also refreshes the integrity hash). No Docker, no external database.

```bash
./start.sh               # both at once; Ctrl+C stops both
./stop.sh                # stop both servers from another terminal
```

Or separately:

```bash
# Terminal 1 — backend on http://localhost:8080
cd backend
./gradlew run            # creates backend/data/rio.db, seeds 9 card transactions on first start

# Terminal 2 — frontend on http://localhost:5173 (proxies /api to :8080)
cd frontend
pnpm install
pnpm run dev
```

Open http://localhost:5173 — it redirects to `/card-transactions`.

## Tests and verification

```bash
cd backend && ./gradlew test          # Money, JdbcTemplate, repository, and route tests (schema-validated)
cd frontend && pnpm test --run        # Money helpers + schema validation with the shared schemas
cd frontend && pnpm run build         # typecheck + production build
cd frontend && pnpm run preview       # serve the build under Content-Security-Policy: script-src 'self' (backend on :8080)
./verify.sh                           # all of the above, from the repo root
```

`pnpm test` and `pnpm run build` first regenerate `frontend/src/api/validators.generated.{js,d.ts}` from
`contracts/schemas` (`pnpm run generate:validators`). The generated files are checked in; `./verify.sh`
fails when they do not match the schemas, and runs the frontend tests with Node's
`--disallow-code-generation-from-strings` so any return to runtime schema compilation (`eval` /
`new Function`, which a strict CSP forbids) fails there instead of in a browser.

### Continuous integration

`.github/workflows/verify.yml` runs `./verify.sh` on every pull request and on pushes to
`main`. CI runs the same script you run locally — there is no separate CI-only test sequence.
It runs the script once per JDK (Temurin 25, 21 and 17, overriding the toolchain pin through
the `jdkVersion` Gradle property) on a single Node version — Node only drives the frontend
toolchain, the React app never runs on it. That version is exactly 24.21.0, the floor of the
`engines.node` range in `frontend/package.json`, so a newer-than-floor Node API sneaking into
the build or test setup fails in CI; local development on a newer Node covers the other end.
No external services and no secrets: backend tests create temporary SQLite files. When a run
fails, the backend HTML and XML test reports are uploaded as a
`backend-test-reports-jdk-<version>` artifact.

The matrix jobs report as `verify (jdk 25)` and friends; a single aggregate job named
**`verify`** passes only when all of them do. Require `verify` — and only `verify` — in branch
protection, so adding or dropping a JDK version never changes the required check. That setting
is not configurable on this repository today (GitHub restricts branch protection to public
repositories and paid plans); once available, set it under
*Settings → Branches → Add rule → Require status checks to pass → `verify`*.

## Where things are

```text
contracts/schemas/     JSON Schema (Draft 2020-12). The single source of truth for HTTP shapes.
backend/src/main/kotlin/ai/project/rio/
  Application.kt       wiring + main()
  db/                  Database (SQLite connection setup), JdbcTemplate, TransactionalService (service base), SchemaInitializer (DDL + seed)
  money/               Currency, Money, Ratio, MoneyRounding
  cardtransaction/     CardTransaction (domain), Repository (SQL), Service (rules), Routes (HTTP), Dtos (wire),
                       IdempotencyRepository (Idempotency-Key SQL), RequestFingerprint (SHA-256 identity of a validated create)
  http/                ApiError, ErrorHandling (exception -> status mapping), JsonConverter (fastjson2 <-> HTTP bodies),
                       JsonSyntax (RFC 8259 grammar check), EcmaScript (the whitespace set JSON Schema `\s` means)
backend/src/test/...   MoneyTest, JdbcTemplateTest, TransactionalServiceTest, CardTransactionRepositoryTest, CardTransactionRoutesTest,
                       CardTransactionServiceTest (replay, conflict, rollback, restart, parallel keys), CardTransactionIdempotencyRepositoryTest,
                       CardTransactionRequestFingerprintTest, SchemaInitializerTest,
                       contract/JsonSchemaAssertions (networknt Draft 2020-12 validator over ../contracts/schemas, `pattern`s
                       matched by joni in ECMAScript mode), JsonSchemaAssertionsTest, JsonSyntaxTest
frontend/src/
  api/                 client.ts (fetch + validate), schemas.ts (wire types + typed validators), cardTransactions.ts (endpoints),
                       validators.generated.{js,d.ts} (GENERATED by scripts/generate-validators.mjs from ../contracts/schemas; do not edit)
  money/               money.ts (bigint Money, formatting), money.test.ts
  types/               cardTransaction.ts (domain types)
  pages/               CardTransactionListPage, CardTransactionDetailsPage
  components/          CardTransactionList, CardTransactionRow, CreateCardTransactionsForm (one or more rows, all-or-nothing)
features/              one Markdown spec per interview feature
```

## API

| Method | Path                          | Success                              | Errors   |
|--------|-------------------------------|--------------------------------------|----------|
| GET    | `/api/card-transactions`      | 200 `{ "items": [CardTransaction] }` | —        |
| GET    | `/api/card-transactions/{id}` | 200 `CardTransaction`                | 404      |
| POST   | `/api/card-transactions`      | 201 `CardTransaction` for an object body; 201 `{ "items": [CardTransaction] }` for an array body; requires `Idempotency-Key` | 400, 415, 422 |

Each path in the table also answers 405 for a method it does not route, with an `Allow` header
listing the methods it supports (`GET, POST, OPTIONS` for the collection, `GET, OPTIONS` for one
card transaction); `OPTIONS` on those paths returns 204 with the same `Allow`. Any other path,
including an unknown sub-path such as `/api/card-transactions/{id}/extra`, stays 404 without
`Allow`. An unsatisfiable `Accept` answers 406 before the route runs.

```json
// CardTransaction
{
  "id": "seed-0002",
  "description": "Blue Bottle Coffee",
  "amount": { "amount": "525", "currency": "USD" },
  "type": "DEBIT",
  "status": "COMPLETED",
  "createdAt": "2026-09-02T15:30:00Z"
}

// POST body: one card transaction
{ "description": "Lunch", "amount": { "amount": "1800", "currency": "USD" }, "type": "DEBIT" }

// POST body: several, created all-or-nothing (answers with the list shape)
[
  { "description": "Lunch", "amount": { "amount": "1800", "currency": "USD" }, "type": "DEBIT" },
  { "description": "Ramen", "amount": { "amount": "1200", "currency": "JPY" }, "type": "CREDIT" }
]

// Any error
{ "code": "VALIDATION_ERROR" | "NOT_FOUND" | "INTERNAL_ERROR" | "IDEMPOTENCY_CONFLICT", "message": "amount must be positive" }
```

`amount.amount` is a base-10 integer string in minor units (`"1800"` = USD 18.00, `"1800"` = JPY 1800).
Decimals, exponents, signs, and symbols are rejected. The server assigns `id`, `status` (`COMPLETED`), and `createdAt`.

An array body must have at least one item and is inserted in one database transaction: if any item is
invalid the request is 400 and nothing is persisted. The message names the failing item by its 0-based
position, followed by the single-object message: `[1]: amount must be positive`. Any other JSON kind
(`true`, `"x"`, `42`, `null`) is `malformed request body`.

POST requires exactly one `Idempotency-Key` header: an opaque, case-sensitive value of 1 to 255 characters
with no control characters and not blank (a UUID per logical submission, reused when the client retries that same request, is the recommended client value). It is
checked before the body: a missing header is 400 `missing Idempotency-Key header`, a blank, over-long or
control-character value is 400 `invalid Idempotency-Key header`, two field lines are 400
`multiple Idempotency-Key headers`, and none of these consume the key. One caveat: the HTTP engine (Netty)
rejects a C0 control character or DEL in any header value while decoding the request, before any route
runs, with its own plain-text 400 rather than the `ApiError` shape; the route's check is what catches the
remaining control characters (C1, such as U+0085). The key names one logical create
operation, not a payload: it exists so a client that never saw the `201` can retry safely.

- **First use**: the request is processed as described above and answered `201`.
- **Replay**: the same key with the same logical request answers `201` with the same body as the first time,
  including ids, `createdAt` and batch order, and inserts nothing. There is no replay indicator.
- **Conflict**: the same key with a different logical request answers 422 `IDEMPOTENCY_CONFLICT`
  (`Idempotency-Key was already used with a different request`) and writes nothing.
- **Identity** is the validated request, not the JSON text: the trimmed description, the amount in minor
  units, the currency, the type, the item order, and whether the body was an object or an array (`{...}` and
  `[{...}]` answer with different shapes, so they are different requests). Member order and whitespace do
  not matter. Two different keys with identical data are two transactions: this is retry deduplication,
  not duplicate-transaction detection.
- **Failure window**: a request rejected before the transaction (media types, malformed JSON, invalid
  fields, blank descriptions, non-positive amounts, an invalid batch item) leaves the key unused. The key
  is claimed inside the same SQLite transaction as the rows and the ordered id mapping, so a rollback
  releases it and a commit consumes it even if the response is lost. Keys never expire.
- **Concurrency**: the primary key on the stored key decides ownership. Parallel requests with the same key
  produce one set of rows; the others wait for the writer and replay it, or get 422 if their payload differs.

POST requires `Content-Type: application/json`; missing, blank, or unsupported content types return 415
with `VALIDATION_ERROR`. Responses from the card transaction POST handler advertise `Accept-Post: application/json`.
A malformed `Content-Type` header returns 400 with `malformed Content-Type header`.
A body that cannot be read - not RFC 8259 JSON (comments, trailing commas and a byte order mark
included, which fastjson2 alone would accept), a missing or unknown field, the wrong JSON kind for a
field (a number, boolean, object or array where the contract says string) - returns 400 with
`malformed request body` and names nothing from the input.
A description is blank when every character is ECMAScript whitespace, the set the contract's
`pattern: "\S"` means; that includes U+00A0 and U+FEFF and excludes U+001C..U+001F, unlike Kotlin's
`isBlank`. The stored description is trimmed by the same set.
Validation error *responses* describe constraints without echoing request values; server logs are
not redacted and still record the full request line.
A malformed `Accept` header returns 400 with `malformed Accept header` before the route runs.
Error responses are explicitly serialized as JSON regardless of `Accept`, including the status the
framework raises on its own: an unroutable method returns 405 `method not allowed` with `Allow`.

Every response this API produces — success or error — is `application/json`, so acceptability is
decided from `Accept` alone, *before* any route runs: a request that excludes JSON returns 406
`no acceptable response media type` and has no side effects (a rejected POST writes nothing).
The selected semantics (RFC 9110 §12.5.1):

- An absent or empty `Accept` expresses no preference and accepts anything.
- Repeated `Accept` field lines are combined in received order (§5.2) and empty list elements are
  ignored (§5.6.1): `Accept: text/plain` followed by `Accept: application/json` is
  `text/plain, application/json`, in either order.
- The most specific matching media range decides: exact `application/json`, then `application/*`,
  then `*/*`. Within one specificity the highest `q` wins, so `application/json;q=0, */*` is rejected
  while `application/json, */*;q=0` is accepted.
- `q=0` excludes; any `q` above zero accepts, since there is nothing to choose between. The parameter
  name is case-insensitive (`Q=0` excludes too).
- The header is read against the RFC 9110 grammar, not a lenient approximation of it: a bare `*`
  or a wildcard type with a concrete subtype (`*/json`) — neither is a media range; §12.5.1 permits
  `*/*`, `type/*`, `type/subtype` — spaces inside a media range, a quoted qvalue (`q="0.5"`) or one
  outside §12.4.2 (`q=abc`, `q=2`, `q=.5`) is a malformed `Accept` and returns 400 rather than a
  guessed preference. Quoted-string parameters other than `q` are grammatical and may contain `,`
  or `;`.
- Range parameters other than `q` are parsed but deliberately not matched, a simplification of full
  media-range parameter matching: `application/json;charset=utf-16` is treated as `application/json`,
  because the produced type carries no parameter a range could select between.

The 406 body is itself the JSON `ApiError` shape even though the caller said it would not accept JSON;
there is no empty error response anywhere in the API. The one response not in that shape comes from the
HTTP engine rather than the API: a request whose headers Netty cannot decode (a control character in a
field value, for instance) gets Netty's plain-text 400 before Ktor sees it. Because every outcome depends on `Accept`,
every response carries `Vary: Accept` (§12.5.5), so a shared cache cannot reuse a stored JSON body
for a request the server would reject.

Unsupported media types return 415 without WARN logs; failure to transform a type explicitly accepted
by the route returns 500 `INTERNAL_ERROR` and logs a server warning.
Media-type matching is case-insensitive and accepts parameters such as `charset=utf-8`;
structured suffix types such as `application/vnd.api+json` are not registered and return 415.

Supported currencies are **BRL, CAD, CNY, EUR, JPY, USD** across the API, database, and UI.
If an existing local database predates this currency set or the idempotency tables, stop the backend and delete the database
file before restarting: `RIO_DB_PATH` if set, otherwise `data/rio.db` relative to the directory the
backend was started from (`backend/data/rio.db` with the commands above). This resets local
card transactions to the deterministic seed data. Schema changes use this reset workflow, not migrations.
Startup creates the tables only for a fresh file. For an existing file it requires every table to be
present and checks the stored DDL against the current definition, and it stops with reset instructions
before serving requests (and before running any DDL) if a table is missing or differs, so a database
from before a schema change is never quietly extended. The check normalizes SQLite's `CREATE TABLE` prefix and
trailing whitespace/semicolon, but compares the column/constraint body exactly. This is not SQL
semantic equivalence: manually reformatted bodies or modified definitions also require a reset.

## How a request flows

```text
React Page              pages/CardTransactionListPage.tsx
  ↓
API module              api/cardTransactions.ts (calls api/client.ts)
  ↓
JSON Schema validation  api/schemas.ts        (Ajv validators precompiled from contracts/schemas/*.json)
  ↓  HTTP /api/...  (Vite proxies to :8080 in dev)
Ktor Route              cardtransaction/CardTransactionRoutes.kt     (DTO <-> domain, status codes)
  ↓
Service                 cardtransaction/CardTransactionService.kt    (business rules, ids, timestamps;
                        extends db/TransactionalService: multi-row writes run in one transaction)
  ↓
Repository              cardtransaction/CardTransactionRepository.kt (SQL, row <-> CardTransaction)
  ↓
JdbcTemplate            db/JdbcTemplate.kt   (prepare, bind, map, close, withTransaction)
  ↓
SQLite                  backend/data/rio.db
```

Errors flow back through `http/ErrorHandling.kt` as the shared `ApiError` shape; the frontend
turns them into `ApiError` (server said no) or `ApiContractError` (response violates the schema).

## Money

```text
                     Money
        integer minor units + Currency
                       │
          ┌────────────┼────────────┐
          │            │            │
          ▼            ▼            ▼
      Kotlin        JSON API      React
       Long          string       bigint
          │                          │
          ▼                          ▼
      Repository                  API mapper
          │
          ▼
      SQLite
 amount_minor INTEGER
 currency TEXT
```

- `Currency` carries precision: BRL/CAD/CNY/EUR/USD 2, JPY 0. Nothing hardcodes `/ 100`.
- Backend `Money(amount: Long, currency)` supports `+`, `-`, `compareTo`, `sumMoney()`, `split(n)`,
  `allocate(weights)`, and `multiply(Ratio, MoneyRounding)`. Every binary op checks currency;
  every op uses checked arithmetic; split/allocate never lose a minor unit.
- A `CardTransaction.amount` is a magnitude (> 0); `type` gives the direction. The UI renders
  `DEBIT 525 USD` as `-$5.25` and `CREDIT` as `+$5.25`.
- Frontend `Money { amount: bigint, currency }` with `moneyToDecimalString` and `formatMoney`
  implemented on strings/bigint, exact for any magnitude.

## Deliberate choices

| Choice | Why |
|---|---|
| SQLite file, no external DB | Zero setup; tests use temp files; restarts keep data. |
| Plain JDBC + explicit `JdbcTemplate` / `JdbcExecutor` | SQL stays visible; standalone and transaction-scoped operations share a small API. |
| `Money` value type, not `Long amount` + `String currency` | Currency can't be dropped or mixed by accident. |
| Integer minor units, never floating point | Exactness. `0.1 + 0.2` is not a thing here. |
| JSON amounts as integer *strings* | JS `number` can't hold all 64-bit values; strings can. |
| `bigint` in the browser | Same reason; formatting is done on digits, not floats. |
| Hand-written DTOs and TS wire types | No code generation for the types; fastjson2 binds the DTO constructors, the schema tests catch drift. |
| Shared JSON Schema, validated on both sides | One contract, executable in backend tests (networknt `json-schema-validator`, Draft 2020-12, `pattern`s matched by joni in ECMAScript mode, which agrees with Ajv on every construct the contracts use; an unescaped `.` is refused at load because joni's dot excludes only LF, RFC 8259 grammar checked before validating) and at frontend runtime (Ajv). |
| Frontend validators precompiled at build time | The one code-generation step: Ajv normally compiles schemas with `new Function`, which a `Content-Security-Policy` without `unsafe-eval` blocks. `scripts/generate-validators.mjs` runs the same Ajv (`allErrors`, `strict`, Draft 2020-12) at build time into `validators.generated.js`; the browser ships only that code plus `ajv/dist/runtime/*`. |
| Thin layers, no interfaces-with-one-impl, no DI | Small enough to hold in your head. |
| `TransactionalService` base class for services that write more than one row | One place that knows how to start a transaction; services stay free of connection handling and repositories stay free of business rules. |
| One `POST /api/card-transactions` accepting an object or an array | No second endpoint to keep in sync; the array form exercises the transactional path end to end. |

## JDBC: baseline and optional operations

Start with `query` (list), `queryOne` (row or null), and `update` (write/DDL).
`CardTransactionRepository` uses these three operations and owns all SQL and row mapping.
`JdbcTemplate` opens a connection per standalone operation. `JdbcExecutor` is also implemented
by the transaction-scoped executor, which reuses one connection for the entire callback.

Services that own multi-statement writes extend `db/TransactionalService`, receive the `JdbcTemplate`,
and run the write inside `transactional { tx -> ... }`, constructing every participating repository
from `tx`. `CardTransactionService.createAll` is the reference implementation:

```kotlin
class TransferService(jdbc: JdbcTemplate) : TransactionalService(jdbc) {
    fun transfer(debit: NewCardTransaction, credit: NewCardTransaction) = transactional { tx ->
        val repository = CardTransactionRepository(tx)
        repository.insert(validated(debit))
        repository.insert(validated(credit))
    }
}
```

A repository built from the template instead of `tx` would auto-commit on its own connection and escape
the rollback, so the base class keeps its template private: inside a member function only `tx` resolves.
A failure anywhere in the block rolls back all statements; success commits them together.
Single-statement reads and writes use a repository built from the constructor parameter in a property
initializer (see `CardTransactionService.repository`); SQLite already makes each statement atomic. Keep SQL in repositories and validation in services. Nested transactions are
outside the starter scope.

Optional operations are `queryForObject` (exactly one row), `extract` (consume a ResultSet),
`batchUpdate` (atomic standalone batch or part of its surrounding transaction), and `execute`
(parameterless DDL). Optional `StatementSettings` configure timeout, row cap, and fetch-size
hints. Defaults leave driver settings untouched; ordinary features need only the baseline API.

## Not implemented on purpose

Search, filtering, sorting, pagination, categories, edit/delete, accounts, balances, transfers,
spending limits, fees, FX, auth, migrations, concurrency control beyond the `Idempotency-Key` claim. These are the
interview exercises; see `features/README.md`.

## Configuration

| Variable       | Default          | Meaning                      |
|----------------|------------------|------------------------------|
| `RIO_DB_PATH` | `data/rio.db`   | SQLite file; the default is relative to the directory the backend is started from (`backend/` with the commands above) |
| `PORT`         | `8080`           | Backend HTTP port            |
