# Contract Rules

Applies to `contracts/schemas/`. The root `AGENTS.md` still applies.

- JSON Schema under `contracts/schemas/` is the shared HTTP contract for both sides and the source of truth for every HTTP shape.
- Any API shape change starts here: update the schema first, then the backend DTOs, then the frontend wire types. The backend validates real responses against these files in its route tests; the frontend compiles them into runtime validators.
- Do not copy or duplicate schemas anywhere. Do not hand-write a second version in TypeScript or Kotlin.
- API objects use `additionalProperties: false` and explicit enums for every closed set of values (currency codes, transaction types, statuses, error codes).
- Money on the wire is `money.schema.json`: a base-10 integer string of minor units plus a currency code, never a JSON number: `{ "amount": "1250", "currency": "USD" }`. Reference `money.schema.json#/$defs/positive` where an amount must be a magnitude.
- If a task introduces an idempotency key or replay semantics for an endpoint, make it explicit in the schema for that request and response (the key's location, format, and any replay indicator). Do not add idempotency fields to endpoints that do not need them.
- A schema change is exercised by both test suites (backend route tests and the frontend validators), so run `./verify.sh` after editing a schema.
- Backend transaction and concurrency details do not belong here; see `backend/AGENTS.md`.
