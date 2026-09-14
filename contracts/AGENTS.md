# Contract Rules

Applies to `contracts/schemas/`. The root `AGENTS.md` still applies.

- JSON Schema under `contracts/schemas/` is the shared HTTP contract for both sides and the source of truth for every HTTP shape.
- Any API shape change starts here: update the schema first, then the backend DTOs, then the frontend wire types. The backend validates real responses against these files in its route tests; the frontend compiles them into runtime validators.
- Do not copy the schema files anywhere, and do not hand-write a second executable schema or validator in TypeScript or Kotlin: the backend route tests load these files and the frontend compiles them with Ajv. The hand-written DTOs (`backend/src/main/kotlin/ai/project/rio/cardtransaction/CardTransactionDtos.kt`) and wire types (`frontend/src/api/schemas.ts`) are expected: they follow the schema and are updated with it, and the schema tests catch drift.
- API objects use `additionalProperties: false` and explicit enums for every closed set of values (currency codes, transaction types, statuses, error codes).
- Money on the wire is `money.schema.json`: a base-10 integer string of minor units plus a currency code, never a JSON number: `{ "amount": "1250", "currency": "USD" }`. Reference `money.schema.json#/$defs/positive` where an amount must be a magnitude.
- If a task introduces an idempotency key or replay semantics for an endpoint, make it explicit in the schema for that request and response (the key's location, format, and any replay indicator). JSON Schema cannot describe headers, so `POST /api/card-transactions` states its required `Idempotency-Key` header in the `description` of `create-card-transactions-request.schema.json` and the README carries the replay and conflict rules; its error code `IDEMPOTENCY_CONFLICT` is in `api-error.schema.json`. Do not add idempotency fields to endpoints that do not need them.
- A schema change is exercised by both test suites (backend route tests and the frontend validators), so run `./verify.sh` after editing a schema.
- Backend transaction and concurrency details do not belong here; see `backend/AGENTS.md`.
