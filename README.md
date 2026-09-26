# Rio Starter: card transactions

A deliberately small full-stack app: Kotlin, Ktor, plain JDBC and PostgreSQL on the back, React,
TypeScript and Vite on the front, and shared JSON Schema contracts in between.
It exists to be understood in five minutes and extended in twenty. Read `AGENTS.md` before changing anything.

This is a learning project for practising AI-assisted coding, not a product. Unless a task says
otherwise, code changes do not need to stay compatible with earlier versions: paths, tables,
schemas, and types may be renamed freely, and an existing local database is simply deleted.

## Quick start

Prerequisites: Docker (a running daemon: the backend's database runs in a container, and so do the backend tests, see [Tests and verification](#tests-and-verification)), JDK 25 (the default Gradle toolchain; CI also covers 21 and 17, see below), Node `>=24.21.0 <25.0.0` (only the current LTS line is supported, see #71) and pnpm (the version is pinned by `packageManager` in `frontend/package.json`; `corepack enable pnpm` installs it, see #74; bump it with `corepack use pnpm@<version>`, which also refreshes the integrity hash).

```bash
./start.sh               # PostgreSQL, backend and frontend at once; Ctrl+C stops all three
./stop.sh                # the same shutdown, from another terminal
```

`./start.sh` runs PostgreSQL 17 as the container `rio-postgres` on `127.0.0.1:5432` (user, password
and database all `rio`), waits until it accepts connections, then starts the backend and the frontend.
The container is started with `--rm` and keeps its data in the named volume `rio-postgres-data`, so
stopping removes the container but not the rows: the next start has the same card transactions. If
`rio-postgres` is already running, `./start.sh` uses it and leaves it running on exit. On its first
start the backend creates the tables and seeds 9 card transactions.

The database is ready for change data capture (#115): the server runs with `wal_level=logical`, and on
an empty volume the container runs `docker/postgres-rio/init.sql`, which creates the replication role
`rio_cdc` (password `rio_cdc`, local use only), gives it read access to the tables, and creates the
publication `rio_publication` for every table in schema `public`. A volume created before that file
existed has no `rio_cdc`; [reset it](#reset-the-database).

If port 5432 is already taken, `docker run` fails and `./start.sh` exits with Docker's message. There is
no port option; to use another PostgreSQL server, run the backend separately with `DB_URL` (see
[Configuration](#configuration)).

Or separately, from the repository root:

```bash
# Terminal 1 — PostgreSQL on 127.0.0.1:5432; Ctrl+C stops it, the volume keeps the data
docker run --rm --name rio-postgres \
  -p 127.0.0.1:5432:5432 \
  -v rio-postgres-data:/var/lib/postgresql/data \
  -v "$PWD/docker/postgres-rio/init.sql:/docker-entrypoint-initdb.d/init.sql:ro" \
  -e POSTGRES_USER=rio -e POSTGRES_PASSWORD=rio -e POSTGRES_DB=rio \
  postgres:17 -c wal_level=logical

# Terminal 2 — backend on http://localhost:8080, once terminal 1 logs
# "database system is ready to accept connections" (on an empty volume: the one after "PostgreSQL init process complete")
cd backend
./gradlew run            # no variables needed: DB_URL, DB_USER and DB_PASSWORD default to this container

# Terminal 3 — frontend on http://localhost:5173 (proxies /api to :8080)
cd frontend
pnpm install
pnpm run dev
```

Open http://localhost:5173, which redirects to `/card-transactions`.

### Reset the database

Reset after a schema change (there are no migrations: the backend refuses to start on tables that
differ from the current definition, and its message gives these steps), or to give a volume from before
`init.sql` the CDC setup. It deletes every card transaction. Stop Rio (`./stop.sh`, or Ctrl+C in each
terminal), then:

```bash
docker rm -f rio-postgres && docker volume rm rio-postgres-data
./start.sh               # or the three terminals above
```

`docker rm -f` removes a container that is still running, such as one `./start.sh` found running and
left alone, and does nothing if there is none. On the next start the image initializes the empty volume
and runs `init.sql` again, and the backend creates the tables and seeds the 9 card transactions.

## Tests and verification

```bash
cd backend && ./gradlew test          # Money, JdbcTemplate, repository, and route tests (schema-validated)
cd frontend && pnpm test --run        # Money helpers + schema validation with the shared schemas
cd frontend && pnpm run build         # typecheck + production build
cd frontend && pnpm run preview       # serve the build under Content-Security-Policy: script-src 'self' (backend on :8080)
./verify.sh                           # everything CI runs, from the repo root
```

Backend tests need a running Docker daemon. `PostgresTestDatabase` (in the backend's `db` test package)
starts one `postgres:17` container per test run through Testcontainers, gives each test method its own
empty database in it, and the container is removed when the run ends; no `DB_URL` or local container is
involved, and the first run pulls the image. Every test that touches a database uses it, so without Docker
those fail with Testcontainers' `Could not find a valid Docker environment`.

`pnpm test` and `pnpm run build` first regenerate `frontend/src/api/validators.generated.{js,d.ts}` from
`contracts/schemas` (`pnpm run generate:validators`). The generated files are checked in; `./verify.sh`
fails when they do not match the schemas, and runs the frontend tests with Node's
`--disallow-code-generation-from-strings` so any return to runtime schema compilation (`eval` /
`new Function`, which a strict CSP forbids) fails there instead of in a browser.

### Continuous integration

`.github/workflows/verify.yml` runs `./verify.sh` on every pull request and on pushes to
`main`. CI runs the same script you run locally; there is no separate CI-only test sequence.
It runs the script once per JDK (Temurin 25, 21 and 17, overriding the toolchain pin through
the `jdkVersion` Gradle property) on a single Node version (Node only drives the frontend
toolchain; the React app never runs on it). That version is exactly 24.21.0, the floor of the
`engines.node` range in `frontend/package.json`, so a newer-than-floor Node API sneaking into
the build or test setup fails in CI; local development on a newer Node covers the other end.
No secrets and no service containers: backend tests start their own PostgreSQL container through
Testcontainers on the Docker daemon that GitHub's `ubuntu-latest` runners provide. Nothing is cached, so each job pulls `postgres:17`. When a run
fails, the backend HTML and XML test reports are uploaded as a
`backend-test-reports-jdk-<version>` artifact.

The matrix jobs report as `verify (jdk 25)` and friends; a single aggregate job named
`verify` passes only when all of them do. Require `verify`, and only `verify`, in branch
protection, so adding or dropping a JDK version never changes the required check. That setting
is not configurable on this repository today (GitHub restricts branch protection to public
repositories and paid plans); once available, set it under
*Settings → Branches → Add rule → Require status checks to pass → `verify`*.

## Where things are

```text
contracts/schemas/     JSON Schema (Draft 2020-12). Source of truth for application JSON body shapes.
backend/src/main/kotlin/ai/project/rio/
  Application.kt       wiring + main()
  db/                  Database (DB_URL checks, PostgreSQL pool), JdbcExecutor, JdbcTemplate, TransactionalService (service base), SchemaInitializer (DDL, drift guard, seed)
  money/               Currency, Money, Ratio, MoneyRounding
  cardtransaction/     CardTransaction (domain), Repository (SQL), Service (rules), Routes (HTTP), Dtos (wire),
                       IdempotencyRepository (Idempotency-Key SQL), RequestFingerprint (SHA-256 identity of a validated create)
  http/                ApiError, ErrorHandling (exception -> status mapping), JsonConverter and JsonRequest (fastjson2 <-> HTTP bodies),
                        JsonSyntax (RFC 8259 grammar check), EcmaScript (the whitespace set JSON Schema `\s` means)
backend/src/test/...   MoneyTest, JdbcTemplateTest, TransactionalServiceTest, CardTransactionRepositoryTest, CardTransactionRoutesTest,
                       CardTransactionServiceTest (replay, conflict, rollback, restart, parallel keys), CardTransactionIdempotencyRepositoryTest,
                       CardTransactionRequestFingerprintTest, SchemaInitializerTest,
                        contract/JsonSchemaAssertions (networknt Draft 2020-12 validator over ../contracts/schemas, `pattern`s
                        matched by joni in ECMAScript mode), JsonSchemaAssertionsTest, CurrencyContractTest; http/JsonSyntaxTest
frontend/src/
  api/                 client.ts (fetch + validate), schemas.ts (wire types + typed validators), cardTransactions.ts (endpoints),
                        errors.ts, validators.generated.{js,d.ts} (GENERATED by scripts/generate-validators.mjs from ../contracts/schemas; do not edit)
  money/               money.ts (bigint Money, formatting), money.test.ts
  types/               cardTransaction.ts (domain types)
  pages/               CardTransactionListPage, CardTransactionDetailsPage, latestRequest (keeps only the newest in-flight response)
  components/          CardTransactionList, CardTransactionRow, CreateCardTransactionsForm (one or more rows, all-or-nothing)
features/              one Markdown spec per interview work item
docker/postgres-rio/   init.sql: the CDC role, grants and publication, run by the local PostgreSQL container on an empty volume
```

## API

| Method | Path                          | Success                              | Resource-specific errors |
|--------|-------------------------------|--------------------------------------|--------------------------|
| GET    | `/api/card-transactions`      | 200 `{ "items": [CardTransaction] }` | none     |
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

// Application error with a JSON body
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

- On first use the request is processed as described above and answered `201`.
- A replay, the same key with the same logical request, answers `201` with the same body as the first
  time, including ids, `createdAt` and batch order, and inserts nothing. There is no replay indicator.
- A conflict, the same key with a different logical request, answers 422 `IDEMPOTENCY_CONFLICT`
  (`Idempotency-Key was already used with a different request`) and writes nothing.
- The identity of a request is the validated request, not the JSON text: the trimmed description, the
  amount in minor units, the currency, the type, the item order, and whether the body was an object or an
  array (`{...}` and `[{...}]` answer with different shapes, so they are different requests). Member order
  and whitespace do not matter. Two different keys with identical data are two transactions: this is retry
  deduplication, not duplicate-transaction detection.
- A request rejected before the transaction (media types, malformed JSON, invalid fields, blank
  descriptions, non-positive amounts, an invalid batch item) leaves the key unused. The key is claimed
  inside the same database transaction as the rows and the ordered id mapping, so a rollback releases it and
  a commit consumes it even if the response is lost. Keys never expire.
- Under concurrency the primary key on the stored key decides ownership. Parallel requests with the same
  key produce one set of rows; the others wait for the writer and replay it, or get 422 if their payload
  differs.

POST requires `Content-Type: application/json`; missing, blank, or unsupported content types return 415
with `VALIDATION_ERROR`. Responses from the card transaction POST handler advertise `Accept-Post: application/json`.
A malformed `Content-Type` header returns 400 with `malformed Content-Type header`.
A body that cannot be read returns 400 with `malformed request body` and names nothing from the
input. That covers text that is not RFC 8259 JSON (comments, trailing commas and a byte order mark
included, which fastjson2 alone would accept), a missing or unknown field, and the wrong JSON kind
for a field (a number, boolean, object or array where the contract says string).
A description is blank when every character is ECMAScript whitespace, the set the contract's
`pattern: "\S"` means; that includes U+00A0 and U+FEFF and excludes U+001C..U+001F, unlike Kotlin's
`isBlank`. The stored description is trimmed by the same set.
A description containing U+0000 returns 400 `VALIDATION_ERROR` with `description must not contain U+0000`,
because PostgreSQL text cannot store that character; the request schema refuses it too.
Validation error *responses* describe constraints without echoing request values; server logs are
not redacted and still record the full request line.
A malformed `Accept` header returns 400 with `malformed Accept header` before the route runs.
Error responses are explicitly serialized as JSON regardless of `Accept`, including the status the
framework raises on its own: an unroutable method returns 405 `method not allowed` with `Allow`.

Application responses with bodies are `application/json`, so acceptability is decided from
`Accept` alone, *before* any route runs: a request that excludes JSON returns 406 `no acceptable
response media type` and has no side effects (a rejected POST writes nothing).
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
- The header is read against the RFC 9110 grammar, not a lenient approximation of it. A bare `*`
  or a wildcard type with a concrete subtype (`*/json`) is not a media range, since §12.5.1 permits
  `*/*`, `type/*` and `type/subtype`. Either of those, spaces inside a media range, a quoted qvalue
  (`q="0.5"`) or one outside §12.4.2 (`q=abc`, `q=2`, `q=.5`) is a malformed `Accept` and returns
  400 rather than a guessed preference. Quoted-string parameters other than `q` are grammatical and
  may contain `,` or `;`.
- Range parameters other than `q` are parsed but deliberately not matched, a simplification of full
  media-range parameter matching: `application/json;charset=utf-16` is treated as `application/json`,
  because the produced type carries no parameter a range could select between.

The 406 body is itself the JSON `ApiError` shape even though the caller said it would not accept JSON.
Application-generated error responses with bodies use that shape; `HEAD` responses are bodyless.
A request whose headers Netty cannot decode (a control character in a field value, for instance)
gets Netty's plain-text 400 before Ktor sees it. Because application routing depends on `Accept`,
application responses carry `Vary: Accept` (§12.5.5), so a shared cache cannot reuse a stored JSON
body for a request the server would reject.

Unsupported media types return 415 without WARN logs; failure to transform a type explicitly accepted
by the route returns 500 `INTERNAL_ERROR` and logs a server warning.
Media-type matching is case-insensitive and accepts parameters such as `charset=utf-8`;
structured suffix types such as `application/vnd.api+json` are not registered and return 415.

Supported currencies are BRL, CAD, CNY, EUR, JPY and USD across the API, database, and UI.
A local database from before a schema change (a new currency, a new table, a changed column) cannot be
used as is; [reset it](#reset-the-database). Startup creates the tables only for a fresh database (none
of Rio's table names in use). Otherwise it requires every table to be present and to match the current
definition, and it stops with reset instructions before serving requests (and before running any DDL)
if a table is missing or differs, so a database from before a schema change is never quietly extended.
Both sides of the comparison are described by PostgreSQL: the expected tables are created from the DDL
in a scratch schema inside a transaction that is rolled back, so formatting cannot cause a false
mismatch. The check compares relation kind, columns in order with type, `NOT NULL` and default, and
constraint definitions. Indexes that back no constraint, triggers, grants, replica identity,
publication membership and constraint names are not compared, so the CDC setup in `init.sql` does not
affect it.

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
Repository              cardtransaction/CardTransactionRepository.kt (SQL, row <-> CardTransaction; takes a JdbcExecutor)
  ↓
JdbcExecutor            db/JdbcExecutor.kt   (query, queryOne, update, ...: prepare, bind, map, close). Two implementations,
                        both in db/JdbcTemplate.kt: the standalone JdbcTemplate (one connection per call) and the
                        transaction-bound executor that `transactional { tx -> }` hands to the block (one connection
                        for the whole block). The repository cannot tell which one it was given.
  ↓
PostgreSQL              database rio in the rio-postgres container (DB_URL)
```

Application errors with JSON bodies flow back through `http/ErrorHandling.kt` as the shared
`ApiError` shape; the frontend turns them into `ApiError` (server said no) or `ApiContractError`
(response violates the schema).

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
      PostgreSQL
 amount_minor BIGINT
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
| PostgreSQL in a local container that `./start.sh` manages | One command still starts everything, restarts keep data, and PostgreSQL has the logical replication the CDC pipeline (#115) reads from. Tests get an empty database per test method from Testcontainers. |
| `Money` value type, not `Long amount` + `String currency` | Currency can't be dropped or mixed by accident. |
| Integer minor units, never floating point | Exactness. `0.1 + 0.2` is not a thing here. |
| JSON amounts as integer *strings* | JS `number` can't hold all 64-bit values; strings can. |
| `bigint` in the browser | Same reason; formatting is done on digits, not floats. |
| Hand-written DTOs and TS wire types | No code generation for the types; fastjson2 binds the DTO constructors, the schema tests catch drift. |
| Shared JSON Schema, validated on both sides | One contract, executable in backend tests (networknt `json-schema-validator`, Draft 2020-12, `pattern`s matched by joni in ECMAScript mode, which agrees with Ajv on every construct the contracts use; an unescaped `.` is refused at load because joni's dot excludes only LF, RFC 8259 grammar checked before validating) and at frontend runtime (Ajv). |
| Frontend validators precompiled at build time | The one code-generation step: Ajv normally compiles schemas with `new Function`, which a `Content-Security-Policy` without `unsafe-eval` blocks. `scripts/generate-validators.mjs` runs the same Ajv (`allErrors`, `strict`, Draft 2020-12) at build time into `validators.generated.js`; the browser ships only that code plus `ajv/dist/runtime/*`. |
| One `POST /api/card-transactions` accepting an object or an array | No second endpoint to keep in sync; the array form exercises the transactional path end to end. |

## Implementation Rules

`backend/AGENTS.md`, `frontend/AGENTS.md`, and `contracts/AGENTS.md` own implementation rules.
`directory-structure.md` owns file placement. This README describes the current application and
its API rather than prescribing new implementation structure.

## Not implemented on purpose

Search, filtering, sorting, pagination, categories, edit/delete, accounts, balances, transfers,
spending limits, fees, FX, auth, migrations, concurrency control beyond the `Idempotency-Key` claim. See `features/README.md`.

## Configuration

| Variable      | Default                                | Meaning                      |
|---------------|----------------------------------------|------------------------------|
| `DB_URL`      | `jdbc:postgresql://localhost:5432/rio` | JDBC URL of the database; must start with `jdbc:postgresql:` and carry no credentials; never printed |
| `DB_USER`     | `rio`                                  | Role the backend connects as |
| `DB_PASSWORD` | `rio`                                  | Its password; never logged or printed |
| `PORT`        | `8080`                                 | Backend HTTP port            |

The defaults match the `rio-postgres` container, so running Rio locally needs none of them. The role
needs `CREATE` on the database: the startup schema check uses a temporary scratch schema.
