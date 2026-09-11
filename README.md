# Rio Starter — Transactions

A deliberately small full-stack app: **Kotlin + Ktor + plain JDBC + SQLite** on the back,
**React + TypeScript + Vite** on the front, and **shared JSON Schema** contracts in between.
It exists to be understood in five minutes and extended in twenty. Read `AGENTS.md` before changing anything.

## Quick start

Prerequisites: JDK 25 (the Gradle toolchain pins 25), Node 20+ (with npm). No Docker, no external database.

```bash
./start.sh               # both at once; Ctrl+C stops both
```

Or separately:

```bash
# Terminal 1 — backend on http://localhost:8080
cd backend
./gradlew run            # creates backend/data/rio.db, seeds 8 transactions on first start

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
| POST   | `/api/transactions`       | 201 `Transaction`                | 400 |

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

- `Currency` carries precision: USD 2, EUR 2, JPY 0. Nothing hardcodes `/ 100`.
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
| Plain JDBC + 90-line `JdbcTemplate` | SQL stays visible; real transactions; nothing to learn. |
| `Money` value type, not `Long amount` + `String currency` | Currency can't be dropped or mixed by accident. |
| Integer minor units, never floating point | Exactness. `0.1 + 0.2` is not a thing here. |
| JSON amounts as integer *strings* | JS `number` can't hold all 64-bit values; strings can. |
| `bigint` in the browser | Same reason; formatting is done on digits, not floats. |
| Hand-written DTOs and TS wire types | No code generation step; the schema tests catch drift. |
| Shared JSON Schema, validated on both sides | One contract, executable in backend tests and at frontend runtime. |
| Thin layers, no interfaces-with-one-impl, no DI | Small enough to hold in your head. |

## Not implemented on purpose

Search, filtering, sorting, pagination, categories, edit/delete, accounts, balances, transfers,
spending limits, fees, idempotency, concurrency control, FX, auth, migrations. These are the
interview exercises; see `features/README.md`.

## Configuration

| Variable       | Default          | Meaning                      |
|----------------|------------------|------------------------------|
| `RIO_DB_PATH` | `data/rio.db`   | SQLite file (relative to `backend/`) |
| `PORT`         | `8080`           | Backend HTTP port            |
