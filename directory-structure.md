# Directory structure

Where code lives in this repository, what belongs in each directory, and how to decide where a new
file goes. Read it together with the `AGENTS.md` of the subtree you are editing: those files hold the
coding rules, this file holds the placement rules. The `AGENTS.md` files are authoritative: where
this file and one of them disagree, the `AGENTS.md` rule holds and this file has a bug.

Where a rule's firmness is not obvious it carries a tag; an untagged statement describes the code
as it is today or restates a rule from an `AGENTS.md` or the README. The tags:

- Decision: confirmed by the maintainer. Holds even where the code has a stray exception.
- Convention: followed consistently by the code, or required by an `AGENTS.md` / README.
- Likely: a clear pattern with only one or two instances behind it. Follow it; do not treat it as absolute.
- Exception: a deliberate departure from the main pattern. Not a template for new code.

## Overview

The repository is a small full-stack monorepo with four owned subtrees. They share nothing but the
JSON Schema contract, and the backend and frontend talk only over HTTP.

| Subtree | What it is | How it is organized |
|---|---|---|
| `backend/` | Kotlin / Ktor HTTP API over SQLite through plain JDBC | Feature-first. One Kotlin package per business feature; Route, Service, Repository and model responsibilities are files inside that package. Cross-cutting code lives in three shared packages: `db/`, `http/`, `money/`. (Decision) |
| `frontend/` | React / TypeScript single-page app (Vite, pnpm) | By kind. `src/api/`, `src/components/`, `src/pages/`, `src/money/`, `src/types/`. It does not mirror the backend's feature packaging. (Convention) |
| `contracts/schemas/` | JSON Schema (Draft 2020-12) for every HTTP shape | One file per shape. Source of truth for both sides; any API shape change starts here. (Convention) |
| `features/` | One Markdown spec per feature | Flat directory, one file per feature. (Convention) |

There are no `routes/`, `services/` or `repositories/` packages anywhere in the backend, and none
should be created.

## Repository structure

Only directories that matter for placement are shown. Build output, dependencies and caches
(`build/`, `.gradle/`, `.kotlin/`, `backend/data/`, `frontend/node_modules/`, `frontend/dist/`) are
git-ignored and omitted.

```text
.
├── AGENTS.md                    repository-wide rules; each subtree adds its own AGENTS.md
├── CLAUDE.md                    includes AGENTS.md (same pattern in every subtree)
├── README.md                    quick start, API reference, "where things are"
├── directory-structure.md       this file
├── settings.gradle.kts          Gradle root that includes :rio-backend from ./backend
├── verify.sh                    everything CI runs; start.sh / stop.sh run both servers
├── .github/workflows/verify.yml runs ./verify.sh
│
├── contracts/
│   └── schemas/                 <kebab-case>.schema.json, one per HTTP shape
│
├── features/                    <kebab-case>.md, one per feature; README.md holds the template
│
├── backend/
│   ├── AGENTS.md
│   ├── build.gradle.kts         Ktor, fastjson2, sqlite-jdbc; tests read ../contracts/schemas in place
│   └── src/
│       ├── main/kotlin/ai/project/rio/
│       │   ├── Application.kt   main() and Application.module(): hand wiring, no DI container
│       │   ├── cardtransaction/ FEATURE: card transactions (see "Backend feature structure")
│       │   ├── db/              shared: Database, JdbcExecutor, JdbcTemplate, TransactionalService, SchemaInitializer
│       │   ├── http/            shared: ApiError + exceptions, ErrorHandling, JsonConverter, JsonRequest, JsonSyntax, EcmaScript
│       │   └── money/           shared domain primitives: Currency, Money, Ratio, MoneyRounding
│       ├── main/resources/      logback.xml
│       └── test/kotlin/ai/project/rio/
│           ├── cardtransaction/ mirrors the feature package: <Class>Test.kt per production class
│           ├── db/  http/  money/
│           └── contract/        test-only: JsonSchemaAssertions (schema oracle), its tests, cross-layer invariants (CurrencyContractTest)
│
└── frontend/
    ├── AGENTS.md
    ├── package.json  vite.config.ts  vitest.config.ts  tsconfig.json
    ├── scripts/generate-validators.mjs   contracts/schemas -> src/api/validators.generated.{js,d.ts}
    └── src/
        ├── main.tsx  App.tsx  styles.css   entry, router, global styles
        ├── api/                 client.ts (fetch + validate), schemas.ts (wire types), cardTransactions.ts (endpoints + mapper),
        │                        errors.ts, validators.generated.{js,d.ts} (GENERATED)
        ├── money/               money.ts: bigint Money, currencies, formatting
        ├── types/               domain types used by pages and components
        ├── pages/               one component per route, plus page-local helpers (latestRequest.ts)
        └── components/          presentational and form components
```

Gradle has two roots: the top-level `settings.gradle.kts` includes `:rio-backend` for IDEs, while
`backend/` has its own wrapper and `settings.gradle.kts` and is what `verify.sh`, CI and the README
actually run (`cd backend && ./gradlew ...`). Only `backend/` carries a `gradlew`.

## Backend feature structure

### Feature is the primary package boundary (Decision)

Backend business code belongs to a feature package under `backend/src/main/kotlin/ai/project/rio/`,
named after the business capability in lower case with no separators (`cardtransaction`). The
package holds everything that capability needs, split by responsibility into files. Today there is one
feature, and it is the representative to copy:

```text
cardtransaction/
├── CardTransaction.kt                      domain model: CardTransaction, NewCardTransaction, the enums
├── CardTransactionDtos.kt                  wire DTOs mirroring contracts/schemas + domain <-> wire mapping
├── CardTransactionRoutes.kt                Ktor routes: fun Route.cardTransactionRoutes(service)
├── CardTransactionService.kt               business rules; extends db/TransactionalService
├── CardTransactionRepository.kt            all SQL for card_transactions; row <-> domain mapping
├── CardTransactionIdempotencyRepository.kt SQL for the feature's idempotency tables (+ IdempotencyRecord)
└── CardTransactionRequestFingerprint.kt    feature-local helper (SHA-256 identity of a validated create)
```

Points to take from it:

- Files are flat inside the feature. There is no `model/`, `dto/`, `route/` or other sub-package,
  and none should be introduced. (Convention, root `AGENTS.md`; README "Where things are"
  documents the same layout. Whether a size threshold ever changes this is tracked in #79.)
- A feature may own more than one Repository and more than one helper. Anything only this feature
  uses stays in its package, even if the concept sounds generic: the idempotency repository is
  deliberately "the card-transaction create's own bookkeeping, not a generic idempotency store".
  (Likely: one instance, backed by an explicit code comment.)
- The public entry points of a feature are its `Route.<feature>Routes(...)` extension and its
  Service class. `Application.module()` constructs the service and registers the routes by hand.

### Route

- HTTP in, HTTP out. The file header says it: "Routes only translate HTTP <-> DTO <-> service call.
  Errors are mapped in http/ErrorHandling.kt."
- In code that means Ktor `route {}` / `get` / `post` registration under the API path, reading
  headers and path parameters, header validation that has no business meaning (the
  `Idempotency-Key` shape check), receiving the body through `http/JsonRequest.receiveJson`,
  calling DTO -> domain mappers, calling the Service, choosing the status code, and calling
  `methodNotAllowed()` for the 405/OPTIONS fallback.
- The file is `<Feature>Routes.kt` in the feature package, declared as
  `fun Route.<feature>Routes(service: <Feature>Service)` and registered in `Application.module()`
  inside `routing { }`. Private helpers for the route (like `ApplicationCall.idempotencyKey()`)
  stay in the same file.
- Business rules, ids, timestamps and normalization belong to the Service. SQL and repository
  access do not happen here; the route never touches a Repository. Exception -> status mapping is
  `http/ErrorHandling.kt`. (Convention: `backend/AGENTS.md` "Keep the layering explicit: Route ->
  Service -> Repository".)

### Service

- Business rules and use-case orchestration. "HTTP parsing happens before this layer; SQL happens
  after it."
- In code that means validation and normalization of domain input, assigning ids, status and
  `createdAt` (with an injectable `Clock`), transaction coordination through
  `transactional { tx -> ... }`, composing several repositories inside one transaction, and throwing
  the domain exceptions that `http/` maps (`ValidationException`, `NotFoundException`,
  `IdempotencyConflictException`).
- The file is `<Feature>Service.kt` in the feature package. A service that writes more than one
  row extends `db/TransactionalService` and takes `JdbcTemplate` in its constructor; standalone
  single-statement reads use a repository built from that template in a property initializer.
  (Convention, required by `backend/AGENTS.md`.)
- The chain is Route -> Service -> Repository. The Service is the only thing a Route calls, and
  the only production code that constructs the feature's Repositories, with one exception:
  `db/SchemaInitializer.seedIfEmpty` builds `CardTransactionRepository` to insert the demo rows
  (see "Shared and infrastructure code"). The Service never sees DTOs; Routes convert before
  calling it.

### Repository

- All SQL for a table (or a small group of the feature's tables), and the mapping between the row
  shape and the domain type. "All card transaction SQL lives here."
- The file is `<Feature>Repository.kt` (and `<Feature><Concern>Repository.kt` for a second table
  group) in the feature package. It is a concrete class with the constructor
  `(private val jdbc: JdbcExecutor)`, so the same class works standalone (given a `JdbcTemplate`)
  or inside a transaction (given `tx`).
- No interface, no `Impl`, no generic base repository (`backend/AGENTS.md` forbids "generic
  repository hierarchies or interfaces with a single implementation"). SQL is written in the
  repository with `?` parameters. The SQLite row shape (`amount_minor` + `currency`) must not leak
  above the repository; it maps rows straight to the domain type, so there is no separate
  persistence entity class.
- Business validation belongs to the Service and table DDL to `db/SchemaInitializer.kt` (see
  below); HTTP types do not appear here.

### Model

The "model" responsibility exists, but as files rather than a `model/` sub-package. Do not create
one. (Convention for the one feature; consistent with the README.)

| Kind of model | Where | Example |
|---|---|---|
| Domain model | `<Feature>.kt` in the feature package | `CardTransaction`, `NewCardTransaction`, `CardTransactionType`, `CardTransactionStatus`, `RequestShape` |
| Wire / HTTP DTOs (request and response) | `<Feature>Dtos.kt` in the feature package, with the `toDto()` / `toNewCardTransaction()` mapping functions and any custom fastjson2 reader | `CardTransactionDto`, `CreateCardTransactionRequest`, `CardTransactionListResponse`, `CreateCardTransactionsBody` |
| Persistence entity | none: repositories map `ResultSet` rows directly to the domain type | `CardTransactionRepository.mapCardTransaction` |
| Repository-local record | in the repository file that reads it | `IdempotencyRecord` in `CardTransactionIdempotencyRepository.kt` |
| Shared domain primitive | `money/` | `Money`, `Currency` |
| Error shape | `http/ApiError.kt` (one shape for every error) | `ApiError` |

DTOs mirror `contracts/schemas` exactly and are hand-written; the schema tests catch drift. Money
enters as `MoneyDto` (integer string + code) and is converted to `Money` in `<Feature>Dtos.kt`;
services and repositories only ever see `Money`.

## Shared and infrastructure code

Three packages hold code that is not owned by a feature. Each has a narrow, named job; none is a
dumping ground.

### `db/`: JDBC and SQLite infrastructure

`Database` (the one place connections are configured), `JdbcExecutor` (the operations repositories
use), `JdbcTemplate` (one connection per call, `withTransaction`), `TransactionalService` (base class
for services that own multi-statement writes), `SchemaInitializer` (all table DDL, the schema-drift
guard, and the seed rows).

- `JdbcTemplate` "must not learn about Money or any domain type". (Convention, `backend/AGENTS.md`.)
- `TransactionalService` lives here, not in a feature, even though its name says "Service": it knows
  only JDBC and is the base class for services that own multi-statement writes (`backend/AGENTS.md`
  "Transactions"); a service that only reads, or writes one row, does not need it. (Exception to
  "services live in features", deliberate.)
- `SchemaInitializer` is the one shared file that imports a feature: it holds the `CREATE TABLE`
  statements for every table, including the feature's, and seeds through
  `CardTransactionRepository`. Table DDL is therefore centralized in `db/`, not feature-local.
  This is required by `backend/AGENTS.md` ("Schema DDL lives in `db/SchemaInitializer.kt`") and is
  the intended place for a new feature's tables. (Exception to feature-locality, deliberate.)

### `http/`: Ktor and JSON integration

`ApiError`, the application exception types that map to HTTP statuses (`ValidationException`,
`NotFoundException`, `NotAcceptableException`, `IdempotencyConflictException`) and the `atItemIndex`
helper that prefixes a `ValidationException` with the array index, `ErrorHandling` (StatusPages,
Accept validation, `methodNotAllowed`), `JsonConverter` (the fastjson2 ContentNegotiation
converter), `JsonRequest` (`receiveJson`), `JsonSyntax` (RFC 8259 check),
`EcmaScript` (the whitespace set JSON Schema `\S` means).

- Every application-defined exception that becomes an HTTP status is declared in `http/ApiError.kt`
  and mapped in `http/ErrorHandling.kt`, even one raised by a single feature
  (`IdempotencyConflictException`). The trade-off chosen here is one place for the exception ->
  status table. (Convention, root `AGENTS.md`. Whether a second feature keeps its exception there
  is a revisit tracked in #79; until then this is the rule, see "Uncertainties".)
- `http/ErrorHandling.kt` also maps exceptions and statuses the application does not define: Ktor's
  `BadRequestException`, `UnsupportedMediaTypeException` and `CannotTransformContentToTypeException`,
  and the bodiless 404 / 405 / 406 statuses that routing and content negotiation raise, so every
  Ktor-produced error response that has a body carries the `ApiError` shape (a `HEAD` response has
  none whatever its status, by HTTP semantics). Netty can reject a request before Ktor
  runs (C0 control characters in a header value) with its own plain-text 400; that is outside this
  mapping and outside any project file.
- `http/` imports nothing from features, `db/` or `money/`. (Convention, verified from imports.)

### `money/`: shared domain primitives

`Currency`, `Money`, `Ratio`, `MoneyRounding`. Used by every feature and by `db/SchemaInitializer`.
Depends on nothing else in the backend. New money behaviour goes here; a parallel representation
elsewhere is forbidden by `backend/AGENTS.md`.

### Feature-local vs shared

Prefer feature-local placement. Promote code to `db/`, `http/` or `money/` only when it represents a
genuine cross-feature concern of that package's kind: JDBC mechanics, HTTP/JSON mechanics, or money
arithmetic. The code shows this in both directions: `CardTransactionRequestFingerprint` and
`CardTransactionIdempotencyRepository` stayed in the feature although "fingerprint" and
"idempotency" sound reusable, while `TransactionalService` was extracted to `db/` precisely because
the feature spec said future features "would each have to re-invent the wiring". Do not create a
`common/`, `shared/`, `util/` or `core/` package; the three existing shared packages are named after
what they contain.

## Directory responsibilities

### `backend/src/main/kotlin/ai/project/rio/<feature>/`

Everything one business capability needs on the server: `<Feature>.kt` (domain), `<Feature>Dtos.kt`
(wire), `<Feature>Routes.kt`, `<Feature>Service.kt`, one or more `*Repository.kt`, and feature-local
helpers. JDBC plumbing, JSON/HTTP plumbing, money arithmetic, table DDL and code another feature
also needs do not go here.

For example, a new `GET /api/card-transactions/{id}/receipt` handler goes in
`CardTransactionRoutes.kt`; the rule "a receipt exists only for COMPLETED" goes in
`CardTransactionService.kt`; a new `card_transaction_receipts` query goes in a repository in this
package; the `CREATE TABLE` for it goes in `db/SchemaInitializer.kt`.

### `backend/src/main/kotlin/ai/project/rio/Application.kt`

Process entry and wiring: environment variables, `Database.open`, schema initialization and seed,
plugin installation, constructing each feature's Service, registering each feature's routes. There
is no DI container and no plugin auto-discovery; add a line here for each new feature. Handlers,
business rules, or configuration that belongs to one feature do not go here.

### `backend/src/main/kotlin/ai/project/rio/db/`, `http/`, `money/`

See "Shared and infrastructure code". Add to them only for a JDBC, HTTP/JSON or money concern
respectively.

### `backend/src/test/kotlin/ai/project/rio/`

All backend tests. Mirrors the main package tree; see "Tests".

### `contracts/schemas/`

The HTTP contract: one `<kebab-case>.schema.json` per request, response or shared shape, each with
`$id` `https://rio.local/schemas/<file>`. API objects use `additionalProperties: false` and explicit
enums for every closed set of values (`contracts/AGENTS.md`); a union such as
`create-card-transactions-request.schema.json` carries neither at its top level. Copies
of schemas, hand-written validators, header semantics beyond a `description`, and backend or
frontend code do not go here. For example, a new endpoint response gets
`card-transaction-receipt.schema.json`; a shape reused by several files (like `money.schema.json`)
is its own file referenced by `$ref`.

### `features/`

One Markdown spec per feature, agreed before implementation, following the template in
`features/README.md`. Named `<kebab-case>.md` after the feature (`idempotent-card-transaction-create.md`).
Architecture documentation (this file), API reference (README) and code do not go here.

### `frontend/src/api/`

The only place that talks HTTP. It holds `client.ts` (fetch, status handling, schema validation,
the `ApiError` / `ApiContractError` / `RequestContractError` classes), `schemas.ts` (wire types
that mirror the JSON exactly, typed re-exports of the generated validators), one endpoint module
per resource (`cardTransactions.ts`, which also maps wire -> domain), `errors.ts` (error -> user
message), and the generated `validators.generated.{js,d.ts}`. React, UI state and hand-edited
validators do not go here. For example, a `GET /api/accounts` client goes in `src/api/accounts.ts`
with its wire types added to `schemas.ts`. The one wire type outside this directory is `MoneyJson`
in `src/money/money.ts`, together with `moneyFromJson` / `moneyToJson`: the money wire shape and its
conversion stay with the money helpers, and only `src/api/` calls them. (Exception, deliberate:
`frontend/AGENTS.md` "Money".)

### `frontend/src/pages/`

One component per router path, registered in `App.tsx`; it owns loading / error / contract-error /
empty state and calls `src/api/`. A page may export its pure view as a named export for rendering
tests (`Details`). Page-only helpers sit beside the pages (`latestRequest.ts`). `fetch`, wire
parsing and reusable UI do not go here.

### `frontend/src/components/`

Reusable or presentational UI and forms. Props are domain values (`Money` with `bigint`, `Date`).
A form may keep what the user typed as text in its draft state (`FormRow.amount` stays a string
until submit) and converts it through the money helpers (`moneyFromDecimalString`) before it
becomes a domain or API input; it never parses wire strings. A stateful component exports its pure
view and logic as named exports
(`CreateCardTransactionsFormView`, `validateRows`, `submissionFor`) with the mounted component as
the default export. `fetch`, wire types and routes do not go here.

### `frontend/src/money/` and `frontend/src/types/`

`money/money.ts` is the frontend `Money` (bigint + currency) with parsing, formatting, and the
`MoneyJson` wire shape with its `moneyFromJson` / `moneyToJson` conversion; it depends on nothing
else. `types/` holds the domain types pages and components consume; today one file per resource
(`cardTransaction.ts`). Wire types do not go in `types/`; endpoint wire types go in `api/schemas.ts`
and the shared money wire type stays in `money.ts`.

### `frontend/scripts/`

Build-time tooling only (`generate-validators.mjs`). Never imported by `src/`.

### Root scripts and CI

`verify.sh` is the single verification entry; `.github/workflows/verify.yml` only calls it.
`start.sh` / `stop.sh` run the two dev servers. New checks go in `verify.sh`, not in the workflow.

## File placement guide

| When adding... | Place it in... | Notes |
|---|---|---|
| Ktor route for an existing feature | `<feature>/<Feature>Routes.kt` | Extend the existing `Route.<feature>Routes()`; no global routes package |
| Business rule, normalization, id or timestamp assignment | `<feature>/<Feature>Service.kt` | Multi-row writes go inside `transactional { tx -> }` |
| SQL query or row mapping | `<feature>/<Feature>Repository.kt` (or a second `<Feature><Concern>Repository.kt`) | Concrete class taking `JdbcExecutor`; `?` parameters only |
| Domain type or enum for a feature | `<feature>/<Feature>.kt` | No `model/` sub-package |
| Request/response DTO and its mapping | `<feature>/<Feature>Dtos.kt` | Mirror the schema in `contracts/schemas/` exactly |
| Feature-local helper (fingerprint, parser, policy) | the feature package, `<Feature><Concern>.kt` | Keep it there while it carries feature knowledge; promote only mechanics that belong to no feature |
| New table DDL or seed rows | `db/SchemaInitializer.kt` | Centralized on purpose; reset the DB file, no migrations |
| New application exception that maps to an HTTP status | `http/ApiError.kt` + a handler in `http/ErrorHandling.kt` | Root `AGENTS.md` rule; revisiting it for a second feature is tracked in #79 |
| JDBC or transaction mechanics | `db/` | Must not learn domain types |
| JSON / content negotiation / header parsing | `http/` | Must not import features |
| Money arithmetic, rounding, currency | `money/` | No parallel money representation |
| New backend feature | `ai/project/rio/<feature>/` | See "Adding a new backend feature" |
| Backend test | `src/test/kotlin/ai/project/rio/<same package>/<Class>Test.kt` | Real components, no mocks; a temporary SQLite file when the class touches the database |
| Schema oracle or cross-layer contract invariant test | `src/test/.../contract/` | Test-only package |
| HTTP shape (new or changed) | `contracts/schemas/<kebab-case>.schema.json` | First step of any API change; then DTOs, then wire types |
| Feature spec | `features/<kebab-case>.md` | Use the template in `features/README.md` |
| Frontend endpoint function | `frontend/src/api/<resource>.ts` | Only `src/api/` calls `fetch`; map wire -> domain here |
| Frontend wire type | `frontend/src/api/schemas.ts` | Mirrors the JSON; validators are generated. `MoneyJson` is the one exception and stays in `money/money.ts` |
| Frontend domain type | `frontend/src/types/<resource>.ts` | Uses `Money`, `Date` |
| Page for a new route | `frontend/src/pages/<Name>Page.tsx` + a `<Route>` in `App.tsx` | Handles loading / error / contract-error / empty |
| Reusable UI or form | `frontend/src/components/<Name>.tsx` | Receives domain values as props; a form draft may hold the user's text, converted through `money.ts` before submit |
| Page-only helper | next to the page in `frontend/src/pages/` | e.g. `latestRequest.ts` |
| Money formatting or parsing (frontend) | `frontend/src/money/money.ts` | bigint only |
| Frontend test | next to the file as `<name>.test.ts(x)`; DOM-driven tests as `<Name>.interaction.test.tsx` | vitest picks up `src/**/*.test.{ts,tsx}` |
| Generated code | `frontend/src/api/validators.generated.{js,d.ts}` via `pnpm run generate:validators` in `frontend/` | Checked in; never hand-edited |
| Build-time script | `frontend/scripts/` | Not imported by `src/` |

## Adding a new backend feature

1. Confirm it is a distinct business capability and not a new operation on card transactions. A
   new endpoint under `/api/card-transactions` belongs to the existing feature.
2. Write or update the spec in `features/<kebab-case>.md`, and add or change the schemas in
   `contracts/schemas/` first (`features/README.md` checklist).
3. Create `backend/src/main/kotlin/ai/project/rio/<feature>/`, lower case, no separators, named
   after the capability (`cardtransaction` is the pattern).
4. Copy the representative file set from `cardtransaction/`: `<Feature>.kt`, `<Feature>Dtos.kt`,
   `<Feature>Routes.kt`, `<Feature>Service.kt`, `<Feature>Repository.kt`. Omit what the feature does
   not need (a read-only feature has no `New<Feature>` type); do not add sub-packages.
5. Keep Route, Service, Repository and model inside that package. Do not create `routes/`,
   `services/`, `repositories/` or `models/` packages at any level.
6. Put the feature's `CREATE TABLE` statements and any seed rows in `db/SchemaInitializer.kt`
   (append to `TABLES` in dependency order). There are no migrations; a local database is reset.
7. If the Service writes more than one row, extend `db/TransactionalService`. If it raises a new
   kind of HTTP-visible error, declare the exception in `http/ApiError.kt` and map it in
   `http/ErrorHandling.kt` (root `AGENTS.md`; the revisit is tracked in #79).
8. Wire it in `Application.module()`: construct the Service from `jdbc`, then call
   `<feature>Routes(service)` inside `routing { }`.
9. Add tests under `src/test/kotlin/ai/project/rio/<feature>/`: `<Feature>RepositoryTest`,
   `<Feature>ServiceTest`, and `<Feature>RoutesTest` that schema-validates real responses with
   `JsonSchemaAssertions`.
10. Leave feature-specific helpers in the feature. Promote to `db/`, `http/` or `money/` only code
    that is pure JDBC, HTTP/JSON or money mechanics with no feature knowledge. That can be true
    before a second feature exists (`TransactionalService` was extracted with one), and it is never
    true of code that carries a feature's rules, however reusable its name sounds.
11. Run `./verify.sh`.

## Feature and module organization

### Backend

See "Backend feature structure". The representative feature is `cardtransaction/`.

### Frontend

The frontend is organized by kind of module, not by feature. A feature such as "card transactions"
is spread across `api/cardTransactions.ts`, `types/cardTransaction.ts`,
`pages/CardTransactionListPage.tsx`, `pages/CardTransactionDetailsPage.tsx`,
`components/CardTransactionList.tsx`, `components/CardTransactionRow.tsx` and
`components/CreateCardTransactionsForm.tsx`: files are named after the resource, but no prefix is
prescribed and no folder groups them. A new resource follows the same spread: one endpoint module,
one types file, pages, components. (Convention: the root `AGENTS.md` frontend rules place every
file by kind.) Do not introduce `src/features/<name>/` folders; there is no example of that shape
and it would split the `api/` boundary that `frontend/AGENTS.md` relies on. Whether a second
resource changes this layout is a revisit tracked in #79.

### Contracts

One schema file per shape; shared shapes (`money.schema.json`, `api-error.schema.json`) are
referenced by `$ref` from the others. The generator names validators `validate<PascalCaseStem>`
(`card-transaction-list-response.schema.json` -> `validateCardTransactionListResponse`), so a file
stem must be unique after PascalCasing.

## Dependency rules

### Backend

The boundaries are feature packages and the three shared packages, not a repository-wide
Route/Service/Repository layering. Verified from imports today:

```text
Application.kt ──> cardtransaction, db, http
                       │
cardtransaction ──> db, http, money        (feature depends on shared)
db              ──> money, cardtransaction (SchemaInitializer only: DDL + seed)
http            ──> (nothing internal)
money           ──> (nothing internal)
```

Inside a feature, responsibilities flow one way:

```text
<feature>/
├── Routes      ──> Service, Dtos          (never Repository)
├── Service     ──> Repository, domain     (never Dtos, never Ktor types)
├── Repository  ──> domain, db/JdbcExecutor
└── domain / Dtos                          (Dtos -> domain via mappers; domain knows no HTTP)
```

- Allowed: a feature -> `db/`, `http/`, `money/`; `Application.kt` -> anything.
- Required: Route -> Service -> Repository -> `JdbcExecutor` -> SQLite (`backend/AGENTS.md`), where
  the executor is a standalone `JdbcTemplate` or the transaction-bound executor a
  `transactional { tx -> }` block hands out. A repository constructor takes `JdbcExecutor`, never
  `JdbcTemplate`; otherwise it cannot be built from `tx` and its statements escape the transaction.
- Forbidden: `http/` or `money/` importing a feature; `db/JdbcTemplate` importing a domain type;
  a Route calling a Repository; a Repository containing business validation. (Convention, from
  `backend/AGENTS.md` and the code.)
- The one exception is `db/SchemaInitializer`, which imports `cardtransaction` for DDL and seed
  data. Extend that file for new tables; do not use it as licence for other `db/` code to import
  features.
- There is one feature, so no feature-to-feature import exists to learn from. When a second
  feature arrives, prefer depending on the other feature's Service (its public entry point) or on
  a `money/`-style shared primitive, and avoid reaching into another feature's Repository or Dtos.
  This is guidance consistent with the feature-first decision, not an established rule; see
  "Uncertainties".

### Frontend

```text
main.tsx ──> App.tsx ──> pages ──> components, api, types, money
                                   components ──> api, types, money
                                   api ──> money, types (wire types in api/schemas.ts; MoneyJson in money.ts)
                                   types ──> money
                                   money ──> (nothing)
```

- Forbidden: `fetch` outside `src/api/`; pages or components parsing wire strings or importing
  `validators.generated.js` directly; `src/` importing from `scripts/`. (Convention, `frontend/AGENTS.md`.)
- Allowed: tests importing `@schemas/*.schema.json` through the `@schemas` alias to assert
  against the contract.

### Across subtrees

Backend and frontend import nothing from each other. Both reach `contracts/schemas/` only through
configuration: the Gradle `contracts.schemas.dir` system property (tests read the files in place)
and the frontend's `@schemas` alias plus the generator script. Never copy a schema into either
subtree.

## Tests

### Backend

- Tests live in `backend/src/test/kotlin/ai/project/rio/`, in the same package as the production
  class, named `<Class>Test.kt`. The feature's tests sit in `cardtransaction/`; `db/`, `http/` and
  `money/` tests sit in their packages. (Convention.)
- All kinds share one source set and are told apart by file. `<Feature>RoutesTest` is API tests
  through the real Ktor pipeline (`testApplication { application { module(Database.open(dbFile)) } }`),
  every application-generated JSON response schema-validated (a bodiless response, the 204 to
  `OPTIONS` or any `HEAD` response, is asserted on status and headers). `<Feature>ServiceTest` is service integration
  tests over a temporary SQLite file with a fixed `Clock`, including rollback and concurrency.
  `<Feature>RepositoryTest` is SQL round-trips. `MoneyTest`, `JsonSyntaxTest` and
  `CardTransactionRequestFingerprintTest` are pure unit tests. There is no separate integration or
  e2e source set. (Convention.)
- Real components only. A test whose class touches the database opens `Database.open` on
  `Files.createTempFile(...)` and deletes the file in `@AfterTest`. A test that exercises the
  application schema (the feature tests, `SchemaInitializerTest`) runs `SchemaInitializer.initialize`;
  a `db/` mechanics test creates the minimal table it needs instead (`JdbcTemplateTest`,
  `TransactionalServiceTest` and their `items` table). A pure test (`MoneyTest`, `JsonSyntaxTest`,
  `CardTransactionRequestFingerprintTest`) needs no database. No mocking library and no mocks;
  behaviour that depends on transaction semantics is tested through the database. (Convention,
  `backend/AGENTS.md`.)
- The test-only package `contract/` holds the one shared test utility, `JsonSchemaAssertions.kt`
  (the schema oracle), its own tests, and cross-layer contract invariants that belong to no
  production package (`CurrencyContractTest`: the schema's currency enum equals the backend
  `Currency` set). Test data is declared inline in each test class
  (`lunch`, `cardTransaction(id)`); there is no fixtures directory or shared builder. Keep it that
  way until two test classes actually need the same builder.
- Tests read `../contracts/schemas` in place via the `contracts.schemas.dir` system property set
  in `build.gradle.kts`. Do not copy schemas into `src/test/resources`.

### Frontend

- Tests sit next to the source file: `money/money.test.ts`, `api/schemas.test.ts`,
  `pages/CardTransactionDetailsPage.test.tsx`. They are picked up by `src/**/*.test.{ts,tsx}`. (Convention.)
- The default environment is `node`: pure views are rendered with `renderToStaticMarkup`
  and logic is tested through the named exports (`validateRows`, `submissionFor`, `Details`). A
  test that must mount a stateful component and fire DOM events is a separate
  `<Name>.interaction.test.tsx` starting with `// @vitest-environment jsdom`. Network is stubbed
  with `vi.fn()` on `fetch`; there are no fixture files.
- Test workers run with `--disallow-code-generation-from-strings`, so any validator that
  compiles at runtime fails in tests.

## Naming conventions

Only what affects where a file goes and how it is found.

| Thing | Convention | Example |
|---|---|---|
| Backend package root | `ai.project.rio` | |
| Feature package | lower case, no separators, the capability's name | `cardtransaction` |
| Domain model file | `<Feature>.kt` | `CardTransaction.kt` |
| DTO file | `<Feature>Dtos.kt` (plural) | `CardTransactionDtos.kt` |
| Route file and function | `<Feature>Routes.kt` (plural), `fun Route.<feature>Routes(service)` | `cardTransactionRoutes` |
| Service | `<Feature>Service.kt`, `class <Feature>Service` | `CardTransactionService` |
| Repository | `<Feature>Repository.kt`, `class <Feature>Repository`; a second one `<Feature><Concern>Repository.kt` | `CardTransactionIdempotencyRepository` |
| Feature helper | `<Feature><Concern>.kt`, usually an `object` | `CardTransactionRequestFingerprint` |
| No suffixes | no `Impl`, no `Interface`, no `Entity`, no `Controller` | |
| Shared packages | named after the concern: `db`, `http`, `money` | |
| Backend test | `<Class>Test.kt`, same package | `CardTransactionRoutesTest.kt` |
| SQLite tables | `snake_case`, prefixed by the feature's noun | `card_transactions`, `card_transaction_idempotency_items` |
| Schema file | `<kebab-case>.schema.json`; `$id` = `https://rio.local/schemas/<file>` | `create-card-transactions-request.schema.json` |
| Generated validator | `validate<PascalCaseStem>` | `validateCreateCardTransactionsRequest` |
| Feature spec | `features/<kebab-case>.md` | `idempotent-card-transaction-create.md` |
| Frontend page | `<Name>Page.tsx`, default export | `CardTransactionListPage.tsx` |
| Frontend component | `<Name>.tsx`, default export; pure view as `<Name>View` named export | `CreateCardTransactionsForm.tsx` |
| Frontend non-component module | `camelCase.ts` | `cardTransactions.ts`, `latestRequest.ts`, `money.ts` |
| Frontend wire type | `<Name>Json` in `api/schemas.ts` | `CardTransactionJson` |
| Frontend test | `<name>.test.ts(x)`; DOM tests `<Name>.interaction.test.tsx` | |
| Generated file | `*.generated.*`, header `GENERATED FILE - DO NOT EDIT` | `validators.generated.js` |

## Adding new code

### Backend

1. Which business feature owns this behaviour? If one exists, the code goes in its package.
2. Inside that package, is it HTTP translation (Routes), a business rule or orchestration
   (Service), SQL (a Repository), a domain type (`<Feature>.kt`), a wire shape (`<Feature>Dtos.kt`),
   or a feature-local helper (its own `<Feature><Concern>.kt`)?
3. Do not group code by technical type across features. Two features' routes live in two feature
   packages, never in one `routes/` package.
4. Only if the code is JDBC mechanics, HTTP/JSON mechanics or money arithmetic with no feature
   knowledge does it go to `db/`, `http/` or `money/`; caller count is not the test. Table DDL
   always goes to `db/SchemaInitializer.kt`; application exceptions that map to an HTTP status go
   to `http/ApiError.kt` (root `AGENTS.md`).
5. Before adding a new kind of file or a sub-package, check `cardtransaction/`: if it has no such
   thing, the feature probably does not need it either.
6. Before adding a new package under `ai.project.rio`, confirm that neither a feature package nor
   `db/`, `http/`, `money/` can express its responsibility.

### Frontend

1. Does it call HTTP or handle a wire shape? `src/api/`.
2. Is it money parsing, formatting or arithmetic? `src/money/money.ts`.
3. Is it a domain type shared by pages and components? `src/types/`.
4. Is it a routed screen? `src/pages/`, plus a route in `App.tsx`. A helper only that page uses
   sits next to it.
5. Otherwise it is UI: `src/components/`.
6. Tests go next to the file they test.

### Contracts and specs

Any change to an HTTP shape starts in `contracts/schemas/`, then backend DTOs, then frontend wire
types, then `pnpm run generate:validators` in `frontend/` (there is no root `package.json`). A new
feature gets a spec in `features/` before code.

## Exceptions and legacy areas

- There is no layer-first legacy. The backend has never had global `routes/`, `services/` or
  `repositories/` packages. The only historical package, `transaction/`, was renamed wholesale to
  `cardtransaction/` (commit `6a1eefa`) and left no residue.
- `db/TransactionalService.kt` is a "Service" file outside a feature. It is an infrastructure base
  class that knows only JDBC; feature services extend it. Not a template for putting services in `db/`.
- `db/SchemaInitializer.kt` imports the feature. Table DDL and seed data for every feature are
  centralized here by rule (`backend/AGENTS.md`), which makes `db/` the one shared package that
  depends on a feature package. Intentional; extend it for new tables.
- `http/ApiError.kt` holds a feature-specific exception. `IdempotencyConflictException` exists
  for one endpoint but lives with the other HTTP exceptions so `ErrorHandling.kt` has one status
  table. Intentional, and the rule for the next one too (root `AGENTS.md`); see "Uncertainties".
- There are two Gradle roots. The top-level `settings.gradle.kts` (`rio-monorepo`) includes
  `:rio-backend`; the backend also has its own `settings.gradle.kts` and wrapper, and every script
  and document builds from `backend/`. Harmless, but new Gradle configuration goes in
  `backend/build.gradle.kts`.
- `backend/data/` and the root `build/` directory are runtime and Gradle output. Ignored; never
  a place for source.

## Uncertainties

Every rule above is in force as written. This section records where the code behind a rule is
thin, so a reader knows which rules the maintainer expects to revisit when a second feature or
resource lands. The revisits are tracked in #79; until one is decided, the current rule applies.

- Cross-feature dependencies have no example. With one feature, no rule about feature A importing
  feature B can be read from the code, and no `AGENTS.md` states one. The guidance in "Dependency
  rules" (depend on the other feature's Service, not its Repository or Dtos) follows from the
  feature-first decision and is the only item here that is guidance rather than a rule.
- HTTP-mapped exceptions go in `http/ApiError.kt` (root `AGENTS.md`). The cost is that `http/`
  accumulates knowledge of every feature's failure modes; the maintainer may later prefer
  feature-local exception classes with handlers still registered in `http/ErrorHandling.kt`.
- Feature packages are flat (root `AGENTS.md`). `cardtransaction/` has seven files; nothing says
  at what size, if any, a `model/` split would become acceptable.
- The frontend is laid out by kind (root `AGENTS.md`) with one resource in it. The maintainer may
  later prefer feature folders for a second resource.
