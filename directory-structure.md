# Directory Structure

This file is the source of truth for file placement and placement-related naming. It does not own
implementation behavior, HTTP semantics, contract contents, test coverage, or feature acceptance
criteria. Those concerns have the canonical owners listed below.

The tables describe the placement conventions to follow now. The repository currently has one
backend business capability and one frontend resource, so they do not establish how the structure
must scale indefinitely. Decisions that need evidence from another capability or resource are
tracked in [issue #79](https://github.com/iceboundrock/rio/issues/79).

## Authority

| Concern | Canonical owner |
|---|---|
| Repository-wide instructions and precedence | `AGENTS.md` |
| File placement and placement-related naming | this file |
| Backend implementation, dependencies, transactions, and tests | `backend/AGENTS.md` |
| Frontend implementation, API boundary, and tests | `frontend/AGENTS.md` |
| Contract authoring and validator generation | `contracts/AGENTS.md` |
| Application JSON body shapes | `contracts/schemas/*.schema.json` |
| Current API methods, paths, statuses, and headers | `README.md` under "API" |
| Work-item requirements and acceptance criteria | the applicable `features/*.md` spec |
| Unresolved architecture decisions | issue #79 |

Other documents may refer to a rule where context requires it, but they must not become a second
owner or define divergent policy. Existing source is evidence of a convention, not by itself a rule
for future code.

## Repository Map

| Path | Responsibility |
|---|---|
| `backend/` | Kotlin/Ktor application and backend tests |
| `frontend/` | React/TypeScript application and frontend tests |
| `contracts/schemas/` | Shared JSON request and response body schemas |
| `features/` | Work-item specifications |
| `README.md` | Setup and current application/API behavior |
| `verify.sh` | Repository verification run by CI |

Generated output, dependencies, caches, and runtime data are not source directories. Examples
include `build/`, `.gradle/`, `backend/data/`, `frontend/node_modules/`, and `frontend/dist/`.

## Backend Placement

Backend source paths below are relative to
`backend/src/main/kotlin/ai/project/rio/`. The current `cardtransaction/` package is the
representative business-capability package.

The current package-name example is lowercase with no separator (`cardtransaction`). There is no
multiword capability-package example, so do not infer a spelling convention beyond the task or an
explicit decision.

| When adding | Place it in |
|---|---|
| Domain types for a capability | `<capability>/<Feature>.kt` |
| Request or response DTOs and their domain mapping | `<capability>/<Feature>Dtos.kt` |
| Ktor routes for a capability | `<capability>/<Feature>Routes.kt` |
| Business rules or use-case orchestration | `<capability>/<Feature>Service.kt` |
| Feature query/DML SQL and row mapping | `<capability>/<Feature>Repository.kt` or `<capability>/<Feature><Concern>Repository.kt` |
| Another capability-owned concern | `<capability>/<Feature><Concern>.kt` |
| Process entry and manual feature wiring | `Application.kt` |
| JDBC or SQLite mechanics | `db/` |
| Table DDL and seed rows | `db/SchemaInitializer.kt` |
| HTTP, Ktor, or JSON mechanics | `http/` |
| A current card-transaction application exception exposed as an HTTP error | `http/ApiError.kt`, mapped in `http/ErrorHandling.kt` |
| Money and currency mechanics | `money/` |

The current capability-package convention is flat. Do not add repository-wide `routes/`,
`services/`, `repositories/`, or `models/` packages. A capability uses only the files it needs; the
five `<Feature>*.kt` names above are a naming pattern, not a required one-to-one inventory.
Additional owned concerns stay in the capability package even when another capability also needs
them. Whether packages remain flat at a larger size, and how a consumer may depend on the owner,
are tracked in issue #79.

Feature-agnostic mechanics go to the package named for their responsibility, even when they have
one current caller. Reuse count does not decide placement. The current tree has no generic
`common/`, `shared/`, or `util/` package; do not use one merely to avoid choosing an owner.

A capability-agnostic domain concept outside the existing `db/`, `http/`, and `money/` concerns
has no established destination. Record its ownership decision in the work-item before creating a
general-purpose package.

`Application.kt` composes the application by constructing services and registering routes.
`SchemaInitializer.kt` centralizes DDL and seed data. Both may therefore refer to capability code
for their respective responsibilities; neither relationship is a general dependency permission.

### Backend Tests

Backend tests use the matching package path under `backend/src/test/kotlin/ai/project/rio/`.
A dedicated test for a production class is named `<Class>Test.kt`; this does not require one test
file per production class. Test-only schema assertions and cross-layer contract invariants go in
`contract/`.

Test behavior and setup are owned by `backend/AGENTS.md`. The placement examples are:

| Test concern | Package |
|---|---|
| Pure capability/domain behavior | the matching `<capability>/` package |
| JDBC mechanics | `db/` |
| Repository behavior against the application schema | the repository's capability package |
| Route/application behavior | the route's capability package |
| Schema oracle or cross-layer contract invariant | `contract/` |

Backend runtime configuration belongs in `backend/src/main/resources/`; `logback.xml` is the
current example. Root verification and development scripts live at the repository root, and the CI
workflow lives in `.github/workflows/`.

## Frontend Placement

Frontend source paths below are relative to `frontend/src/`.

| When adding | Place it in |
|---|---|
| HTTP client mechanics or a resource endpoint module | `api/` |
| Wire types | `api/schemas.ts` |
| Generated validators | `api/validators.generated.{js,d.ts}` through the generator |
| Domain types | `types/` |
| Routed screens | `pages/<Name>Page.tsx`, registered in `App.tsx` |
| Page-local helpers | beside the page in `pages/` |
| Reusable UI or forms | `components/<Name>.tsx` |
| Money types, parsing, arithmetic, or formatting | `money/money.ts` |
| Frontend tests | beside the source as `<name>.test.ts(x)` |
| DOM-driven component tests | beside the source as `<Name>.interaction.test.tsx` |
| Build-time tooling | `scripts/` outside `src/` |

The browser entry point, route registration, and global stylesheet are currently `main.tsx`,
`App.tsx`, and `styles.css` at the `src/` root.

`MoneyJson` and its wire/domain conversion remain in `money/money.ts`; this is the current
placement exception to other wire types living in `api/schemas.ts`. See `frontend/AGENTS.md` for
the API boundary and test behavior. Issue #79 tracks whether the by-kind layout should change when
the frontend gains another resource; do not invent a feature-folder layout before that decision.

## Contracts And Specs

| When adding | Place it in |
|---|---|
| Application JSON request, response, or reusable body shape | `contracts/schemas/<kebab-case>.schema.json` |
| Work-item specification | `features/<kebab-case>.md` |

Follow `contracts/AGENTS.md` for schema-first authoring and validator generation. Follow
`features/README.md` for the feature-spec workflow. A work-item spec records requirements for that
change; it is not a second owner for repository-wide placement rules.

## Adding A Backend Capability

For a new capability named `Transfer`, create `backend/src/main/kotlin/ai/project/rio/transfer/`
and add the domain, DTO, Route, Service, and Repository files that the capability actually needs.
Put its DDL and optional seed rows in `db/SchemaInitializer.kt`, then construct its service and
register its routes in `Application.kt`. Put its backend tests under the matching `transfer/` test
package.

This example answers where files go, not how capabilities call each other, share a transaction, or
place new capability-specific HTTP exceptions. Those decisions have no second-capability evidence
and remain open in issue #79.
