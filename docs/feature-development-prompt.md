# Feature Development Prompt

Use this prompt when implementing a new full-stack feature in this project.

## Feature Input

**Feature name:** `<FEATURE_NAME>`

**Feature request**

```text
<Describe the user need, business rules, examples, and known constraints here.>
```

**Optional context**

```text
<Related issue, agreed spec, existing API behavior, prior decisions, UI requirements, example data, etc.>
```

---

# Goal

You are a senior full-stack engineer delivering this feature end to end as a verified vertical slice:
spec, API contract, JSON Schema, domain model, DDL, repository, service, route, frontend API
boundary, UI, tests, and `./verify.sh`. Agree on the spec before implementing; once it is agreed, do
not stop at a plan or TODO list.

Before editing, read `AGENTS.md`, `directory-structure.md`, `features/README.md`, every scoped
`AGENTS.md` for the code you will touch, and the README "API" section. They hold the implementation
rules (layering, money, transactions, schema-first contracts, frontend boundary, tests) and are not
repeated here; where this prompt and they disagree, they win. Model new code on the closest existing
capability (`cardtransaction/` in the backend, `src/api/cardTransactions.ts` and
`CreateCardTransactionsForm` in the frontend).

# 1. Spec

A spec is agreed only when the user says so, in the feature input (for example "`features/x.md` is
agreed; implement it") or later in the conversation. A spec file existing is not agreement. If the
spec is agreed, go straight to section 3 without editing it: an agreed spec changes only with the
user's agreement, and never during an AutoForge run.

Otherwise, create or update `features/<feature-name>.md` from the `features/README.md` template.

Requirements must be testable. Not "Support filtering" or "Handle errors correctly", but:

```text
GET /api/card-transactions?status=PENDING returns only transactions whose status is PENDING.
An unknown status returns 400 VALIDATION_ERROR.
Omitting status preserves the current list behavior.
```

Use Non-goals to stop scope creep. Record a decision only when it materially affects implementation
or review: atomicity, concurrency, idempotency, financial correctness, destructive schema changes
(say whether the local database must be reset), ambiguous HTTP semantics, or cross-capability
dependencies. Do not invent trade-offs to make the spec look complete.

Stop once the spec answers the design questions in section 2, list any open decisions, and end by
asking the user to agree the spec before you write code.

# 2. Design questions to answer in the spec

**API contract.** For each endpoint: method, path, parameters, request and response headers, bodies
with concrete examples, success status, and every error status with its application error code.
Never write "return an appropriate error". Distinguish malformed JSON, schema violations, domain
validation failures, missing resources, conflicts, unsupported `Content-Type`, unacceptable
`Accept`, and internal failures. A new error code reuses an existing exception when its HTTP meaning
fits; otherwise follow the error rules in `backend/AGENTS.md`.

**Domain model.** Entities or value objects, invariants, allowed state transitions, derived values,
and ownership. Keep wire, domain, and persistence models distinct. For each rule, ask: if this use
case were invoked without HTTP, would the rule still need to hold? If yes, it belongs in the service
or domain layer, whatever the frontend also checks.

**DDL.** The final table definitions with the constraints that protect real invariants (`NOT NULL`,
`UNIQUE`, `CHECK`, foreign keys, indexes for the access patterns). Do not rely only on Kotlin
validation for integrity.

**Atomicity and concurrency.** For each mutating workflow: what must succeed or fail together, what
happens under concurrent requests, which constraint or conditional statement protects the invariant,
and whether it can be retried safely.

**Idempotency.** Decide per mutating endpoint whether it needs it; do not copy `Idempotency-Key`
by default. If not, say why in one sentence. If so, the spec defines: header and validation, key
lifetime, request identity and normalization before fingerprinting, first use, same-key replay,
same-key conflict, rollback, concurrent use, whether validation failures consume the key, whether
responses are reconstructed or snapshotted, and frontend retry behavior.

# 3. Implement

Work in layer order (schema, domain and DTO mapping, repository, service, route, `Application.kt`
wiring, frontend wire types and API module, UI), writing each layer's tests with it rather than at
the end.

Update every authoritative record the change makes stale, in the same work item: the README "API"
section when a method, path, status, or header changes, and the owning scoped `AGENTS.md` when the
work introduces or changes a repository rule (owners are listed in `directory-structure.md`).
`./verify.sh` does not check Markdown, so nothing else will catch a stale record.

The UI implements the whole flow, not only the happy path: loading, empty, validation error, server
error, contract error, network error, submitting, disabled, double-submit protection, and retry, as
they apply.

Log only what has diagnostic value; `backend/AGENTS.md` says what must never be logged or returned
in an error response.

Before finishing, check the risks that apply: SQL injection, unbounded input, integer overflow,
money precision, unknown JSON properties, duplicate requests, concurrent or partial writes, and
unsafe rendering. Do not turn a focused feature into a repository-wide rewrite.

# 4. Verify

Run the targeted suite while iterating, then `./verify.sh`. When a test fails, find the real cause,
fix the implementation, and rerun. Never get a green build by deleting tests, weakening assertions,
or bypassing contract validation.

# 5. Report

The final report and PR description follow `docs/non-code-rules.md`. Cover:

- **Outcome:** what capability now exists and why it was needed.
- **Contract:** each `METHOD PATH` with request, success response, errors, and important headers.
- **Key decisions:** only what a reviewer cannot infer quickly from the diff, such as a transaction
  boundary, constraint, concurrency strategy, idempotency semantics, or rejected alternative.
- **Risk / review focus:** what deserves careful review; none if none is material.
- **Verification:** the commands actually run and their results.

# Decision rules

When the request leaves details unspecified:

1. Look for precedent in existing code, tests, specs, and the README.
2. Choose the smallest design that satisfies the requirement. Do not expand scope.
3. Record assumptions that affect API semantics, data integrity, or user-visible behavior in the spec
   while it is being agreed; after agreement, list them under Key decisions in the report instead.
4. Make safe local decisions directly instead of returning every minor choice to the user.
5. If two choices produce materially different external behavior and the repository gives no basis
   for choosing, name the open decision instead of inventing a requirement.

The goal is not the most code. It is a clear contract, a minimal implementation that preserves
repository boundaries, and tests that prove the intended behavior.
