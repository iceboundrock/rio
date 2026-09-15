# Frontend rules

Applies to everything under `frontend/`. The root `AGENTS.md` still applies; this file adds the
React / TypeScript rules and the client-side financial rules.

## Architecture

- Raw `fetch` belongs only under `src/api/`. Pages and components call resource endpoint modules there, such as `src/api/cardTransactions.ts`.
- Application JSON responses are runtime-validated against the shared schemas through `src/api/client.ts` before they reach a page. A non-JSON error response is represented by the client's `HTTP_ERROR` fallback because there is no JSON body to validate. Wire types live in `src/api/schemas.ts` and mirror the JSON exactly; resource endpoint modules map them to domain types.
- The validators are precompiled. `scripts/generate-validators.mjs` turns `contracts/schemas/*.schema.json` into `src/api/validators.generated.{js,d.ts}`, and `schemas.ts` re-exports them with their wire types. The generated files are checked in and never hand-edited; `pnpm run generate:validators` regenerates them, and the `test` and `build` scripts run it first. Do not instantiate Ajv or call `ajv.compile` in app code: the production build must run under `Content-Security-Policy: script-src 'self'` (no `unsafe-eval`), which `pnpm run preview` enforces. The tests check it too: `vitest.config.ts` starts every test worker with `--disallow-code-generation-from-strings` (`test.execArgv`), so `pnpm test` fails on a validator that went back to runtime compilation. It also sets `deps.interopDefault: false`, so a default import of a CommonJS dependency yields `module.exports` itself, as it does in Vite's dev pre-bundle and production build; do not turn interop back on to make a test pass, fix the import instead.
- Pages and components receive domain data (`Money` with `bigint`, `Date`) and must not parse wire strings. Form draft state may retain user-entered text until the component converts it to a domain value.
- Keep UI state local (`useState`) unless a task truly requires otherwise.
- Do not add Redux, Zustand, React Query, Tailwind, or a component library unless explicitly required.
- Pages handle the loading, error, contract-error (`ApiContractError` for responses, `RequestContractError` for outgoing bodies), and empty states that apply to their operation. `src/api/client.ts` wraps a rejected `fetch` in `NetworkError` and a rejected `response.text()` in `ResponseBodyError`; those are the only signals `describeError` treats as an unreachable server or a connection lost mid-response, so never classify a bare `TypeError` as a network failure.
- Tests run with `pnpm test --run`; `pnpm run build` type-checks and builds for production. Both regenerate the validators first.
- Tests run in the `node` environment by default: pure views are rendered with `renderToStaticMarkup` and logic is tested through extracted functions. A test that must drive a mounted, stateful component through real events (see `CreateCardTransactionsForm.interaction.test.tsx`) starts with `// @vitest-environment jsdom` and uses `@testing-library/react`; keep that to the interactions the pure view cannot show.

## Money

- Use the frontend `Money` type and helpers in `src/money/money.ts`: `bigint` minor units plus a `CurrencyCode`, with `CURRENCIES[code].precision` for the decimal places.
- Never use JavaScript `number` for money arithmetic or comparison. Use `addMoney`, `subtractMoney`, `compareMoney`; they throw on mixed currencies, so do not catch and work around that.
- Production code converts between wire and domain representations only at the API boundary with `moneyFromJson` / `moneyToJson`. Do not parse or construct monetary wire values inside pages or components.
- User input becomes `Money` through `moneyFromDecimalString`; display goes through `formatMoney` / `formatSignedMoney`. Both respect the currency's precision.
- `bigint` cannot overflow, but the wire format and the backend are signed 64-bit: validate user input against `MAX_WIRE_AMOUNT` before sending it, as `CreateCardTransactionsForm` does.
- Preserve currency semantics when formatting, comparing, filtering, sorting, or aggregating: group by currency, and never offer a cross-currency total or ordering.

## Idempotent client behavior

`POST /api/card-transactions` requires an `Idempotency-Key` header. `createCardTransaction(s)` in `src/api/cardTransactions.ts` take the key as a parameter, and `newIdempotencyKey()` mints one with `crypto.randomUUID()`. `CreateCardTransactionsForm` mints one per logical submission and reuses it (`submissionFor`) while the validated inputs are unchanged and the create has not succeeded. `postJson` in `src/api/client.ts` carries request-specific headers for it. There are still no automatic transport-level retries: surface the error and let the user decide to retry. The rules below apply to this endpoint and to any mutating endpoint a task adds with an idempotency key.

- Generate the key once per logical user action (for example when the user submits a form) and reuse it for every retry of that action; do not generate a new key merely because a transport retry occurred. A new submission gets a new key even if its payload equals an earlier one.
- Keep double-submit protection in the UI (disable the form while `submitting`, as `CreateCardTransactionsForm` does) so retries cannot create a second logical operation, and keep the key of a failed attempt with its validated inputs so the user's own resubmit of the unchanged request reuses it. Bind the key to the validated domain values (what the server fingerprints), not to raw field edits: an edit that does not change the request must still replay.
- Do not add client-side idempotency machinery to endpoints that do not support it.

Database transaction and concurrency rules belong to the backend and are not repeated here.
