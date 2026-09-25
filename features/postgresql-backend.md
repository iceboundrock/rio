# Feature: PostgreSQL backend

Tracks rio issue #93 (Phase 1 of epic #115). Implemented by #94, #95, #96 and #97; verified by #98.

## Problem

Rio stores everything in SQLite, which has no logical replication, so Debezium cannot capture Rio's
writes and the CDC playground in #115 has no source. The maintainer decided that Rio moves to
PostgreSQL only: SQLite is removed rather than kept alongside (T1), `created_at` columns become
`timestamptz` (T4), and backend tests run PostgreSQL in Docker containers (T5). Docker therefore
becomes a prerequisite for running and testing the backend.

Three design questions follow from those decisions, and this spec settles them before any code is
written: who owns the table DDL (T2), how the startup drift guard works without `sqlite_master`
(T3), and how local development gets a PostgreSQL server (T15). The API must behave as it did on
SQLite (NG4). The one exception is text PostgreSQL cannot store (N1 below).

Each requirement below reads as "do X → expect Y" and names the issue that delivers it. The
PostgreSQL behaviours that the design depends on were checked by hand against a throwaway
PostgreSQL 17.11 server while this spec was written: drift-guard rendering, timestamp rounding,
`Instant` binding, U+0000 and the concurrent claim. The implementing PRs prove them again with
tests.

## Requirements

### One database (T1)

- After #95: `git grep -n -i sqlite -- ':!features/' ':!README.md'` → no match; after #96, the same
  without the README exclusion. `features/` keeps its history. `org.xerial:sqlite-jdbc` and the
  `--enable-native-access=ALL-UNNAMED` flag, which exists only for sqlite-jdbc, are gone from
  `backend/build.gradle.kts`.
- `cd backend && ./gradlew dependencies --configuration runtimeClasspath` → lists
  `org.postgresql:postgresql` and `com.zaxxer:HikariCP`, both pinned (#94).
- The pool keeps HikariCP's defaults (10 connections), and connections keep PostgreSQL's default
  isolation, READ COMMITTED. No Rio code sets another level; the concurrency rules below depend on it.

### Configuration

| Variable      | Default                                | Meaning                                              |
|---------------|----------------------------------------|------------------------------------------------------|
| `DB_URL`      | `jdbc:postgresql://localhost:5432/rio` | JDBC URL of the database; must be `jdbc:postgresql:`; never printed |
| `DB_USER`     | `rio`                                  | Role Rio connects as                                 |
| `DB_PASSWORD` | `rio`                                  | Its password; never logged or printed                |
| `PORT`        | `8080`                                 | Unchanged                                            |

The defaults match the local container (T15), so a developer never sets a variable.

- Local container up, no variables set → `cd backend && ./gradlew run` starts; on an empty database
  `GET /api/card-transactions` returns the 9 seed rows.
- `DB_URL=jdbc:mysql://h/rio` or `DB_URL=` (empty) → startup fails before the HTTP port is bound,
  exit code non-zero, message `DB_URL must start with jdbc:postgresql:`.
- `DB_URL` carrying a `user` or `password` query parameter → startup fails with
  `DB_URL must not carry credentials; use DB_USER and DB_PASSWORD`. Credentials have one source.
- Database unreachable, or credentials rejected → startup fails after one connection attempt
  (no retry loop). The message gives the host, port and database, the `DB_USER` value, the variable
  names `DB_URL` and `DB_USER`, and says that `./start.sh` starts the local container.
- No message or log line prints the value of `DB_URL` or `DB_PASSWORD`. The URL can hold anything,
  and pgJDBC reads secrets from it that the check above does not reject (`sslpassword`, the client
  key's password). A message that needs to identify the database prints the host, port and database
  parsed from the URL, as the drift-guard message does.
- `RIO_DB_PATH` set → ignored. No code detects it (root `AGENTS.md`: no legacy-detection code), and
  `backend/data/` is no longer created.
- Backend stopped normally → the pool is closed.

### Code placement

- `db/Database.kt` remains the one place connections are configured. It validates the URL, builds a
  `HikariDataSource`, and returns a `JdbcTemplate(dataSource::getConnection)` together with a way to
  close the pool. There is no `DatabaseFactory`, because one database leaves nothing to select.
  `Application.kt` reads the environment variables and passes them in, as it does for `PORT` today.
  #94 adds the PostgreSQL builder next to the SQLite `open(path)`, and #95 removes `open(path)`.
- Layering is unchanged apart from the last hop: Route → Service → Repository → JdbcExecutor →
  PostgreSQL. No ORM (D1).

### Table DDL and its owner (T2)

`db/SchemaInitializer.kt` stays the only owner of table DDL. `docker/postgres-rio/init.sql` (#96)
creates no tables. It holds only the CDC role, its grants, and a publication declared
`FOR TABLES IN SCHEMA public`, so the publication covers tables the backend creates later.

The tables, in creation order (#95):

```sql
CREATE TABLE card_transactions (
    id TEXT PRIMARY KEY,
    description TEXT NOT NULL CHECK (length(trim(description)) > 0),
    amount_minor BIGINT NOT NULL CHECK (amount_minor > 0),
    currency TEXT NOT NULL CHECK (currency IN ('BRL', 'CAD', 'CNY', 'EUR', 'JPY', 'USD')),
    type TEXT NOT NULL CHECK (type IN ('CREDIT', 'DEBIT')),
    status TEXT NOT NULL CHECK (status IN ('PENDING', 'COMPLETED', 'DECLINED')),
    created_at TIMESTAMPTZ NOT NULL
)

CREATE TABLE card_transaction_idempotency (
    idempotency_key TEXT PRIMARY KEY,
    request_fingerprint TEXT NOT NULL,
    request_shape TEXT NOT NULL CHECK (request_shape IN ('ONE', 'MANY')),
    created_at TIMESTAMPTZ NOT NULL
)

CREATE TABLE card_transaction_idempotency_items (
    idempotency_key TEXT NOT NULL REFERENCES card_transaction_idempotency(idempotency_key) ON DELETE CASCADE,
    item_index INTEGER NOT NULL CHECK (item_index >= 0),
    card_transaction_id TEXT NOT NULL REFERENCES card_transactions(id),
    PRIMARY KEY (idempotency_key, item_index)
)
```

Only two types change from the SQLite DDL. `amount_minor` becomes `BIGINT`, because PostgreSQL's
`INTEGER` is 32-bit and amounts above 2,147,483,647 minor units would fail (D6, R4). Both
`created_at` columns become `TIMESTAMPTZ` (T4). Every `CHECK`, `PRIMARY KEY`, `REFERENCES` and
`ON DELETE CASCADE` is kept. `item_index` stays `INTEGER` because it is a list position. `trim()`
strips only spaces on both databases, so the description check means the same thing. No column is
boolean or auto-incremented (ids are `seed-000n` and `UUID.randomUUID()`), so the requirement's §6.1
rows about `bool()` and `autoIncrement()` need no work (D1).

- Fresh database, backend started → `\d card_transactions` shows `amount_minor bigint`,
  `created_at timestamp with time zone` and five `CHECK` constraints.
- `POST /api/card-transactions` with `"amount": {"amount": "9000000000", "currency": "JPY"}` → 201
  with `"amount": "9000000000"`, and `GET` returns the same value.

### Drift guard (T3)

PostgreSQL keeps no DDL text to compare, so the guard compares catalog descriptions instead. The
live tables are described from the catalog. The expected value is PostgreSQL's own rendering of the
current DDL, not a hand-written copy.

- At startup, `SchemaInitializer.initialize` looks in the connection's current schema (`public`) for
  relations of any kind named after the three tables.
  - None found → fresh database: create all three in one transaction, then run the check below.
  - Any found → compare two descriptions of each table: its relation kind; its columns in order,
    each with its `format_type` type, `NOT NULL` flag and default expression; and the sorted
    `pg_get_constraintdef` of its constraints. The live description is read under the connection's
    normal `search_path`. The expected description comes from running the current DDL in one
    transaction, in a scratch schema placed first on `search_path`, reading the same description
    there, and rolling back. Any difference → refuse.
- Each side is read while a bare table name resolves to its own tables: the live side before the
  scratch transaction starts or after it is rolled back, the expected side inside it.
  `pg_get_constraintdef` (and `pg_get_expr` for defaults) schema-qualifies any relation or
  non-`pg_catalog` function that a bare name would not resolve to on the current `search_path`.
  Read while the scratch schema is first on the path, the live foreign keys render as
  `REFERENCES public.card_transactions(id)` and the scratch ones as
  `REFERENCES card_transactions(id)`, so every start would refuse, including the one that just
  created the tables. Nothing is normalized after rendering.
- The DDL constant is the single source of truth. Both sides are rendered by PostgreSQL, so DDL
  formatting and PostgreSQL's normalization cannot cause a false mismatch. For example, `IN (...)` is
  stored as `= ANY (ARRAY[...])`.
- The guard does not compare non-constraint indexes, triggers, grants, `REPLICA IDENTITY`,
  publication membership or constraint names. Tests add a trigger (#95), and Phase 2 may set replica
  identity or publications. None of these changes what Rio reads or writes.
- Refusing means startup fails with a non-zero exit before any DDL touches the existing tables. The
  message names the table and ends with reset instructions that name the database, not a file:
  `Stop the backend and recreate database rio on localhost:5432 (for the ./start.sh container:
  docker rm -f rio-postgres && docker volume rm rio-postgres-data, then ./start.sh). This resets
  local card transactions to demo data; back up data you need first.` Host, port and database come
  from `DB_URL`, and the password never appears. A mismatch is always a reset, never a migration.
  Missing tables are never created in a database that is not fresh.
- The role Rio connects as needs `CREATE` on the database for the scratch schema. The local
  container's `rio` role and the Testcontainers role both have it.

Checks (#95 ports the existing `SchemaInitializerTest` cases, and #97 adds the type regressions):

- Tables created by a previous start, restart → starts normally and creates nothing. This is the
  check that fails if the live side is read while the scratch schema is on `search_path`.
- `ALTER TABLE card_transactions DROP COLUMN status`, restart → exits non-zero, naming
  `card_transactions` and giving the reset steps; the table and its rows are unchanged.
- `amount_minor` altered to `integer`, or `created_at` altered to `text` → refused in the same way.
- `DROP TABLE card_transaction_idempotency_items`, restart → refused; the table is not recreated.
- A view named `card_transactions` in an otherwise empty schema → refused; the view survives.
- `CREATE INDEX` on a column, a trigger, or `ALTER TABLE card_transactions REPLICA IDENTITY FULL` →
  starts normally.
- After any start, successful or refused, no scratch schema remains.

### Timestamps (T4)

- `CardTransactionService` stamps `Instant.now(clock).truncatedTo(ChronoUnit.MICROS)`. The created
  response, the stored rows, the idempotency record and every replay then carry the same instant, so
  a replay stays byte-identical to the first response.
- `JdbcTemplate` binds a `java.time.Instant` as an `OffsetDateTime` at UTC, truncated to
  microseconds. Repositories read `created_at` with
  `getObject(column, OffsetDateTime::class.java).toInstant()`. The bind has to truncate because
  PostgreSQL rounds: `18:00:00.9999996Z` would be stored as `18:00:01Z`. pgJDBC cannot bind an
  `Instant` directly. The session time zone affects neither the stored nor the read instant.
- The API's `createdAt` format is unchanged. It is still `Instant.toString()`, it still matches the
  `card-transaction.schema.json` pattern, and seed rows print exactly as before
  (`2026-09-01T09:00:00Z`). Only digits after the sixth fractional digit are lost.
- `ORDER BY created_at DESC, id DESC` now orders by time instead of by ISO-8601 text. The TEXT
  column held `Instant.toString()`, which prints the fraction in groups of three digits, so text
  order was wrong only when, within one second, the shorter rendering is a prefix of the longer:
  `Z` sorts after `.` and after every digit, so `…:00Z` sorted after `…:00.123Z`, and `…:00.500Z`
  after `…:00.500001Z`. Fractions of different length that differ in a shared digit (`…:00.500Z`
  vs `…:00.123456Z`) were already ordered correctly.

Checks (#97):

- Service clock at `2026-09-10T18:00:00.123456789Z`, create then replay → equal objects, and
  byte-identical HTTP bodies with `createdAt` `2026-09-10T18:00:00.123456Z`.
- Repository writes `…:00.9999996Z` → reads back `…:00.999999Z`.
- Two rows at `…:00Z` and `…:00.123Z` → the `.123` row is listed first; two rows at `…:00.500Z` and
  `…:00.500001Z` → the `.500001` row first. Both pairs are ones the TEXT column mis-sorted, so the
  check fails against any text ordering, such as `created_at::text`.

### Idempotency under concurrency

The claim stays the first statement of the create transaction:
`INSERT … ON CONFLICT(idempotency_key) DO NOTHING`. Under READ COMMITTED:

- Same key already in flight → the second claim waits on the first transaction's uncommitted row.
  If the first commits, the second claim affects 0 rows. Its next statement takes a new snapshot,
  sees the committed record and rows, and returns the replay (same payload) or 422 (different
  payload). If the first rolls back, the second claim affects 1 row and the second request creates.
- Key already committed → the claim affects 0 rows without waiting, then replay or 422 as above.
- No deadlock is possible. A waiting request holds no locks before its first statement, and a
  transaction uses exactly one connection, so an exhausted pool can delay requests but not deadlock
  them.
- The primary key remains the only authority: no SELECT fast path, JVM lock or advisory lock.

Checks: `parallel same key and payload create once` and
`parallel same key and different payloads keep only the winner` pass on PostgreSQL (#95). #97 adds
many threads on one key with mixed payloads (exactly one insert; the others replay or get 422) and
many threads with distinct keys (all 201, none 500).

### Text PostgreSQL cannot store (N1, proposed)

PostgreSQL `text` cannot hold U+0000. Inserting it fails with SQLSTATE 22021, which Rio would answer
as a 500. SQLite stores and returns the character today. `description` is the only column whose text
comes from the client. The `Idempotency-Key` header cannot carry U+0000, because the route already
rejects a key containing any ISO control character before it reads the body
(`CardTransactionRoutes.idempotencyKey`).

- `POST` with `"description": "a\u0000b"` → 400 `VALIDATION_ERROR`
  `description must not contain U+0000`. The check runs in `CardTransactionService` normalization with
  the other description rules, so nothing is written and the key is not consumed.
  `create-card-transaction-request.schema.json` gets the same rule, so the frontend's validator
  refuses the request first. Delivered in #95, because the cutover is what introduces the 500.
- Per `contracts/AGENTS.md`, the schema changes first, `validators.generated.{js,d.ts}` are
  regenerated and committed, and the README API section records the error. `description` already
  carries `pattern: \S`, and a schema object takes one `pattern`, so the U+0000 rule is a second
  subschema (`not` with a pattern, or `allOf`) whose escape joni and Ajv read alike;
  `JsonSchemaAssertionsTest` gets a case for the new construct (#68).

### Local development (T15)

`./start.sh` runs one PostgreSQL container with `docker run`. No compose file is involved, and
nothing depends on `docker-compose.cdc.yml`.

| Setting   | Value                                                                        |
|-----------|------------------------------------------------------------------------------|
| Image     | `postgres:17`, the same tag the test fixture uses                            |
| Container | `rio-postgres`, run with `--rm`                                              |
| Data      | Named volume `rio-postgres-data` at `/var/lib/postgresql/data`               |
| Port      | `127.0.0.1:5432:5432`; loopback only, because the password is a fixed default |
| Env       | `POSTGRES_USER=rio`, `POSTGRES_PASSWORD=rio`, `POSTGRES_DB=rio`              |
| Server    | `-c wal_level=logical`; Phase 2 needs it, and until then it only adds WAL volume |
| Init      | `docker/postgres-rio/init.sql` mounted into `/docker-entrypoint-initdb.d/` (#96) |

- Docker CLI missing or daemon not running → `./start.sh` exits non-zero with `Docker is required`
  before it starts anything.
- `rio-postgres` not running → start it, then wait up to 30 s for `pg_isready -h 127.0.0.1` inside
  the container before starting the backend and frontend. The check goes over TCP because on first
  start the image initializes the database with a temporary server that accepts only socket
  connections. Not ready in time → stop the container and exit non-zero.
- `rio-postgres` already running → reuse it, and leave it running at shutdown.
- Shutdown (Ctrl+C, `./stop.sh`, or either server exiting) → stop the container if this run started
  it. `--rm` removes the container and the volume keeps the data, so `./stop.sh` then `./start.sh` →
  the same rows as before.
- Host port 5432 already taken → `docker run` fails and `./start.sh` exits non-zero with Docker's
  message. There is no port override; run the backend against the other server with `DB_URL`
  instead. The Phase 2 `postgres-rio` compose service uses the same port, so the two run one at a
  time.
- Separate terminals (README, #96): the same `docker run` command, then `cd backend && ./gradlew run`
  with no variables set.
- Reset: `docker rm -f rio-postgres && docker volume rm rio-postgres-data`, then `./start.sh` → the 9
  seed rows, and `init.sql` runs again.

### Tests (T5)

- Backend tests start their own `postgres:17` through Testcontainers (#94): one container per test
  JVM, and a fresh database per test class. `./gradlew test` and `./verify.sh` need a running Docker
  daemon and nothing else: no `DB_URL` and no local container.
- Docker stopped, `cd backend && ./gradlew test` → fails with Testcontainers'
  `Could not find a valid Docker environment`, not a hang.
- Every backend test class that exists before #95 still exists and passes after it; none is deleted
  to get green. The SQLite `RAISE(ABORT)` trigger in `CardTransactionServiceTest` becomes a PL/pgSQL
  trigger function, and "replay survives reopening the database" reopens with a new pool.
- CI runs on GitHub's `ubuntu-latest`, which provides Docker, and all three `verify (jdk …)` jobs run
  the PostgreSQL tests.

### Records that change with the behaviour

The PR that changes a behaviour also updates its authoritative record. The T1 grep above is the
check that none is missed:

- README (#96): Quick start, prerequisites, configuration table, reset instructions and "Where things
  are". The API section changes only for the N1 error (#95).
- `backend/AGENTS.md` (#95):
  - the layering line;
  - the DDL-and-reset rule;
  - the drift-guard paragraph;
  - the `REAL` rule, which becomes PostgreSQL `real` / `double precision` / floating `numeric`;
  - `amount_minor BIGINT`;
  - the busy-timeout paragraph, which becomes row locks and the pool;
  - Tests.
- Root `AGENTS.md` (#95): the repository map's `backend/` row says "over SQLite". #96 adds the
  `docker/` row (T10).
- `directory-structure.md` (#95): the `db/` placement row says "JDBC or SQLite mechanics", and
  `backend/data/` is removed from the runtime-data examples. #96 adds the `docker/` row (T10).
- `frontend/src/money/money.ts` (#95): the `MAX_WIRE_AMOUNT` comment justifies the limit by
  "SQLite INTEGER"; it becomes PostgreSQL `BIGINT`. A comment is not a frontend change under NG4.
- CI workflow comment and `verify.sh` header (#94): Docker is required.

## Acceptance Criteria

- [ ] With Docker running and no Rio container or volume: `./start.sh` → the container, backend and
      UI start; `curl -s localhost:8080/api/card-transactions | jq '.items | length'` → `9`
      (AC1 as revised).
- [ ] Nothing set, container up, `cd backend && ./gradlew run` → serves the same 9 rows.
- [ ] Invalid `DB_URL`, credentials in `DB_URL`, or an unreachable database → non-zero exit with the
      messages above; neither the password nor the URL value is printed.
- [ ] The tables match the DDL above; a 9,000,000,000 JPY amount round-trips.
- [ ] Every drift-guard check above behaves as stated.
- [ ] Create and replay with a nanosecond clock → byte-identical bodies with a microsecond
      `createdAt`; same-second rows are listed in time order.
- [ ] Concurrent same-key and distinct-key creates behave as stated, with no 500.
- [ ] N1: a U+0000 description → 400 `VALIDATION_ERROR`, nothing persisted, key not consumed.
- [ ] `./stop.sh` then `./start.sh` → rows kept; the reset steps → the 9 seed rows again.
- [ ] `git grep -n -i sqlite -- ':!features/'` → no match, and no pre-existing test class was
      deleted.
- [ ] `./verify.sh` with Docker running passes locally and in all three CI jobs.

## Non-goals

- Any API, contract or frontend change (NG4), except N1.
- Keeping SQLite in any form, or importing data from an existing `backend/data/rio.db`.
- A migration tool (Flyway, Liquibase) or any in-place schema migration. Reset stays the only way a
  schema evolves.
- Configurable pool size, isolation level or local container port.
- CDC, Kafka, Flink and Superset design. Phase 1 only makes the local database CDC-ready
  (`wal_level=logical`, and `init.sql`'s role, grants and publication in #96).
- `features/` specs for Phases 2–5. They change no application behaviour and use their issues as
  the spec (T11).

## Breaking changes

These go in the description of the PR that introduces each one:

- Existing `backend/data/rio.db` files are abandoned, with no import (#95).
- Docker becomes a prerequisite for running the backend (`./start.sh`, or the documented
  `docker run`) and for testing it (`./gradlew test`, `./verify.sh`) (#94, #95).
- `RIO_DB_PATH` is removed; `DB_URL`, `DB_USER` and `DB_PASSWORD` replace it (#95).
- A local volume created before #96 has no CDC role, because `init.sql` runs only on an empty data
  directory. Reset it (#96).

## Superseded requirement items

| Item | Original | New wording |
|---|---|---|
| G1 | PostgreSQL as an optional backend alongside SQLite, switched by configuration. | PostgreSQL replaces SQLite as Rio's only database, configured by `DB_URL`. |
| G3 | The default start is unchanged: SQLite, no external dependencies. | The default start stays one command (`./start.sh`) and does not need the pipeline's compose file; it needs Docker for the local PostgreSQL container. |
| NG1 | PostgreSQL as the default database. | Withdrawn: PostgreSQL is the only database. |
| AC1 | `./gradlew run` still starts on SQLite and behaves exactly as today. | `./start.sh` starts a local PostgreSQL container, the backend and the UI, and the API behaves exactly as it did on SQLite. |
| P1.2 | A `DatabaseFactory` selects SQLite or PostgreSQL from `DB_URL`. | `db/Database.kt` builds the PostgreSQL pool from `DB_URL`; there is nothing to select. |
| P1.3 | Review Exposed table definitions on both databases. | Review the raw DDL in `db/SchemaInitializer.kt` and the repositories' SQL (D1). |
| P1.4 | PostgreSQL init SQL (`docker/postgres-rio/init.sql`): tables, publication, CDC user and grants. | `init.sql` holds the CDC role, its grants and a schema-wide publication; tables come from `SchemaInitializer` (T2). |
| P1.5 | Core API behaves identically on PostgreSQL and SQLite. | Core API behaves identically to the pre-migration behaviour, pinned by the ported suite (#95) and PostgreSQL regression tests (#97). |

## Open decisions

Each decision has a proposed answer, and the requirements above are written against it. Agreeing
this spec accepts all four.

- **T2, DDL ownership.** Proposed: `SchemaInitializer` owns tables, and `init.sql` holds only the CDC
  role, grants and publication.
  - Alternative: tables in `init.sql`, as P1.4 says. This would reverse a rule in `backend/AGENTS.md`
    and `directory-structure.md`, make every test container run `init.sql`, and leave the guard with
    DDL in a SQL file that the Kotlin code must read.
  - Why the proposal: it keeps one DDL source that tests already exercise, at the cost of a
    schema-wide publication.
- **T3, drift guard.** Proposed: compare catalog descriptions, with the expected side rendered from
  the DDL in a rolled-back scratch schema.
  - Alternative A: compare against a hand-written expected description. That duplicates the DDL in
    PostgreSQL's normalized form, which is easy to let drift.
  - Alternative B: store a version marker or DDL hash in a table. That misses a manual `ALTER TABLE`,
    which #95's acceptance criteria require the guard to catch.
  - Alternative C: drop the guard. That breaks a `backend/AGENTS.md` rule.
  - Cost of the proposal: `CREATE` on the database, and a few catalog queries at startup.
- **T15, local PostgreSQL.** Proposed: `./start.sh` manages a `docker run --rm` container with a
  named volume.
  - Alternative A: a dev compose file, a second compose setup next to `docker-compose.cdc.yml` for
    one service.
  - Alternative B: a natively installed PostgreSQL, whose version and settings vary per machine and
    which CDC needs configured by hand.
- **N1, U+0000 in `description`.** Proposed: 400 `VALIDATION_ERROR`, with the rule in both the service
  and the request schema.
  - Alternative A: reject in the service only. The schema would then accept a value the backend
    refuses, unlike the existing `\S` rule, which both enforce.
  - Alternative B: accept the new 500, which contradicts NG4 for a value SQLite handled.

## Notes / Decisions

- Rows sharing a `created_at` (items of one batch) are tie-broken by `id DESC` under the database's
  collation instead of SQLite's byte order. The ids are random UUIDs, so this order was arbitrary
  before and stays deterministic now. Nothing depends on it.
- If PostgreSQL goes away while the backend runs, requests wait up to HikariCP's connection timeout
  (30 s) and then answer 500 `internal server error`. They recover once it is back, with no restart.
- The `postgres:17` tag appears twice: in `start.sh` and in the test fixture. A tag bump changes
  both in the same PR, and from `postgres:18` also the T15 mount point: that image moved the data
  directory to `/var/lib/postgresql/18/docker` and documents mounting the volume at
  `/var/lib/postgresql`.
