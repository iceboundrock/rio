# Rio Starter — Transactions

A deliberately small full-stack app: **Kotlin + Ktor + plain JDBC + SQLite** on the back,
**React + TypeScript + Vite** on the front, and **shared JSON Schema** contracts in between.
It exists to be understood in five minutes and extended in twenty. Read `AGENTS.md` before changing anything.

## Quick start

Prerequisites: JDK 25 (the Gradle toolchain pins 25), Node `^20.19.0 || >=22.12.0` (with npm). No Docker, no external database.

```bash
./start.sh               # both at once; Ctrl+C stops both
```

Or separately:

```bash
# Terminal 1 — backend on http://localhost:8080
cd backend
./gradlew run            # creates backend/data/rio.db, seeds 9 transactions on first start

# Terminal 2 — frontend on http://localhost:5173 (proxies /api to :8080)
cd frontend
npm install
npm run dev
```

Open http://localhost:5173 — it redirects to `/transactions`.

## Tests and verification

```bash
cd backend && ./gradlew test          # Money, JdbcTemplate, repository, and route tests (schema-validated)
cd frontend && npm test -- --run      # Money helpers + schema validation with the shared schemas
cd frontend && npm run build          # typecheck + production build
./verify.sh                           # all of the above, from the repo root
```

### Continuous integration

`.github/workflows/verify.yml` runs `./verify.sh` on every pull request and on pushes to
`main`. CI runs the same script you run locally — there is no separate CI-only test sequence.
It provisions Temurin JDK 25 and runs the script once per Node version in the supported range
(22.x, 24.x, 26.x). No external services and no secrets: backend tests create temporary SQLite
files. When a run fails, the backend HTML and XML test reports are uploaded as a
`backend-test-reports-node-<version>` artifact.

The matrix jobs report as `verify (node 22.x)` and friends; a single aggregate job named
**`verify`** passes only when all of them do. Require `verify` — and only `verify` — in branch
protection, so adding or dropping a Node version never changes the required check. That setting
is not configurable on this repository today (GitHub restricts branch protection to public
repositories and paid plans); once available, set it under
*Settings → Branches → Add rule → Require status checks to pass → `verify`*.

## Where things are

```text
contracts/schemas/     JSON Schema (Draft 2020-12). The single source of truth for HTTP shapes.
backend/src/main/kotlin/ai/project/rio/
  Application.kt       wiring + main()
  db/                  Database (SQLite connection setup), JdbcTemplate, SchemaInitializer (DDL + seed)
  money/               Currency, Money, Ratio, MoneyRounding
  transaction/         Transaction (domain), Repository (SQL), Service (rules), Routes (HTTP), Dtos (wire)
  http/                ApiError, ErrorHandling (exception -> status mapping)
backend/src/test/...   MoneyTest, JdbcTemplateTest, TransactionRepositoryTest, TransactionRoutesTest,
                       contract/JsonSchemaAssertions (loads ../contracts/schemas)
frontend/src/
  api/                 client.ts (fetch + validate), schemas.ts (Ajv validators), transactions.ts (endpoints)
  money/               money.ts (bigint Money, formatting), money.test.ts
  types/               transaction.ts (domain types)
  pages/               TransactionListPage, TransactionDetailsPage
  components/          TransactionList, TransactionRow
features/              one Markdown spec per interview feature
```

## API

| Method | Path                      | Success | Errors                  |
|--------|---------------------------|---------|-------------------------|
| GET    | `/api/transactions`       | 200 `{ "items": [Transaction] }` | — |
| GET    | `/api/transactions/{id}`  | 200 `Transaction`                | 404 |
| POST   | `/api/transactions`       | 201 `Transaction`                | 400, 415 |

Any path also answers 405 for a method it does not route and 406 for an unsatisfiable `Accept`.

```json
// Transaction
{
  "id": "seed-0002",
  "description": "Blue Bottle Coffee",
  "amount": { "amount": "525", "currency": "USD" },
  "type": "DEBIT",
  "status": "COMPLETED",
  "createdAt": "2026-09-02T15:30:00Z"
}

// POST body
{ "description": "Lunch", "amount": { "amount": "1800", "currency": "USD" }, "type": "DEBIT" }

// Any error
{ "code": "VALIDATION_ERROR" | "NOT_FOUND" | "INTERNAL_ERROR", "message": "amount must be positive" }
```

`amount.amount` is a base-10 integer string in minor units (`"1800"` = USD 18.00, `"1800"` = JPY 1800).
Decimals, exponents, signs, and symbols are rejected. The server assigns `id`, `status` (`COMPLETED`), and `createdAt`.

POST requires `Content-Type: application/json`; missing, blank, or unsupported content types return 415
with `VALIDATION_ERROR`. Responses from the transaction POST handler advertise `Accept-Post: application/json`.
A malformed `Content-Type` header returns 400 with `malformed Content-Type header`.
Missing required JSON fields return 400 naming the DTO fields, qualified by their JSON path when the
field belongs to a nested object (`amount.currency`); other malformed JSON or invalid JSON shapes
return 400 with `malformed request body`. Validation error *responses* describe constraints without
echoing request values; server logs are not redacted and still record the full request line.
A malformed `Accept` header returns 400 with `malformed Accept header` before the route runs.
Error responses are explicitly serialized as JSON regardless of `Accept`, including the statuses the
framework raises on its own: an unroutable method returns 405 `method not allowed`, and a successful
response that no acceptable media type can represent returns 406 `no acceptable response media type`
*after* the route has run — for POST the transaction is written and the caller cannot see it (#13).
Unsupported media types return 415 without WARN logs; failure to transform a type explicitly accepted
by the route returns 500 `INTERNAL_ERROR` and logs a server warning.
Media-type matching is case-insensitive and accepts parameters such as `charset=utf-8`;
structured suffix types such as `application/vnd.api+json` are not registered and return 415.

Supported currencies are **BRL, CAD, CNY, EUR, JPY, USD** across the API, database, and UI.
If an existing local database predates this currency set, stop the backend and delete
`backend/data/rio.db` (or your configured `RIO_DB_PATH`) before restarting. This resets local
transactions to the deterministic seed data. Schema changes use this reset workflow, not migrations.
Startup checks the stored table DDL against the current definition and stops with reset instructions
if they differ, before serving requests. The check normalizes SQLite's `CREATE TABLE` prefix and
trailing whitespace/semicolon, but compares the column/constraint body exactly. This is not SQL
semantic equivalence: manually reformatted bodies or modified definitions also require a reset.

## How a request flows

```text
React Page              pages/TransactionListPage.tsx
  ↓
API module              api/transactions.ts  (calls api/client.ts)
  ↓
JSON Schema validation  api/schemas.ts        (Ajv, contracts/schemas/*.json)
  ↓  HTTP /api/...  (Vite proxies to :8080 in dev)
Ktor Route              transaction/TransactionRoutes.kt   (DTO <-> domain, status codes)
  ↓
Service                 transaction/TransactionService.kt  (business rules, ids, timestamps)
  ↓
Repository              transaction/TransactionRepository.kt (SQL, row <-> Transaction)
  ↓
JdbcTemplate            db/JdbcTemplate.kt   (prepare, bind, map, close, transaction)
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
- A `Transaction.amount` is a magnitude (> 0); `type` gives the direction. The UI renders
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
| Hand-written DTOs and TS wire types | No code generation step; the schema tests catch drift. |
| Shared JSON Schema, validated on both sides | One contract, executable in backend tests and at frontend runtime. |
| Thin layers, no interfaces-with-one-impl, no DI | Small enough to hold in your head. |

## JDBC: baseline and optional operations

Start with `query` (list), `queryOne` (row or null), and `update` (write/DDL).
`TransactionRepository` uses these three operations and owns all SQL and row mapping.
`JdbcTemplate` opens a connection per standalone operation. `JdbcExecutor` is also implemented
by the transaction-scoped executor, which reuses one connection for the entire callback.

Single-statement writes use the repository directly; SQLite already makes each statement atomic.
`TransactionService` receives a `TransactionRepository`, which can also be bound to an outer
transaction's executor. A future service owning multi-statement writes can receive `JdbcTemplate`
and construct every participating repository from `tx`. For example:

```kotlin
jdbc.transaction { tx ->
    val repository = TransactionRepository(tx)
    repository.insert(firstTransaction)
    repository.insert(secondTransaction)
}
```

Every repository participating in that write must be constructed with `tx`. A failure rolls
back all statements; success commits them together. Keep SQL in repositories and validation
in services. Nested transactions are outside the starter scope.

Optional operations are `queryForObject` (exactly one row), `extract` (consume a ResultSet),
`batchUpdate` (atomic standalone batch or part of its surrounding transaction), and `execute`
(parameterless DDL). Optional `StatementSettings` configure timeout, row cap, and fetch-size
hints. Defaults leave driver settings untouched; ordinary features need only the baseline API.

## Not implemented on purpose

Search, filtering, sorting, pagination, categories, edit/delete, accounts, balances, transfers,
spending limits, fees, idempotency, concurrency control, FX, auth, migrations. These are the
interview exercises; see `features/README.md`.

## Configuration

| Variable       | Default          | Meaning                      |
|----------------|------------------|------------------------------|
| `RIO_DB_PATH` | `data/rio.db`   | SQLite file (relative to `backend/`) |
| `PORT`         | `8080`           | Backend HTTP port            |
