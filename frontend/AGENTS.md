# Frontend Rules

Applies to everything under `frontend/`. The root `AGENTS.md` still applies; this file adds the
React / TypeScript rules and the client-side financial rules.

## Architecture

- Raw `fetch` belongs only under `src/api/`. Pages and components call the functions in `src/api/cardTransactions.ts`.
- Every fetched response is runtime-validated against the shared schemas through `src/api/client.ts` before it reaches a page. Wire types live in `src/api/schemas.ts` and mirror the JSON exactly; the mapping to domain types happens in `src/api/cardTransactions.ts`.
- Pages and components receive domain values (`Money` with `bigint`, `Date`); they must not parse wire strings.
- Keep UI state local (`useState`) unless a task truly requires otherwise.
- Do not add Redux, Zustand, React Query, Tailwind, or a component library unless explicitly required.
- Every page handles loading, error, contract-error (`ApiContractError` for responses, `RequestContractError` for outgoing bodies), and empty states.
- Tests run with `npm test -- --run`; `npm run build` type-checks and builds for production.

## Money

- Use the frontend `Money` type and helpers in `src/money/money.ts`: `bigint` minor units plus a `CurrencyCode`, with `CURRENCIES[code].precision` for the decimal places.
- Never use JavaScript `number` for money arithmetic or comparison. Use `addMoney`, `subtractMoney`, `compareMoney`; they throw on mixed currencies, so do not catch and work around that.
- Convert between wire and domain representations only at the API boundary with `moneyFromJson` / `moneyToJson`. Do not parse or construct monetary wire values inside pages or components.
- User input becomes `Money` through `moneyFromDecimalString`; display goes through `formatMoney` / `formatSignedMoney`. Both respect the currency's precision.
- `bigint` cannot overflow, but the wire format and the backend are signed 64-bit: validate user input against `MAX_WIRE_AMOUNT` before sending it, as `CreateCardTransactionsForm` does.
- Preserve currency semantics when formatting, comparing, filtering, sorting, or aggregating: group by currency, and never offer a cross-currency total or ordering.

## Idempotent client behavior

The API has no idempotent mutations today: `POST /api/card-transactions` creates a new record on every call. Do not add automatic transport-level retries to a mutation the contract does not declare idempotent; surface the error and let the user decide to retry. When a task adds a mutating endpoint that explicitly supports an idempotency key:

- Generate the key once per logical user action (for example when the user submits a form) and reuse it for every retry of that action; do not generate a new key merely because a transport retry occurred.
- Keep double-submit protection in the UI (disable the form while `submitting`, as `CreateCardTransactionsForm` does) so retries cannot create a second logical operation.
- Do not add client-side idempotency machinery to endpoints that do not support it.

Database transaction and concurrency rules belong to the backend and are not repeated here.
