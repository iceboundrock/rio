# Features

Each interview feature gets one Markdown file in this directory. The file is the shared
human/AI surface: agree on it first, then implement against it. Keep specs short and concrete.

Suggested naming:

```text
features/add-card-transaction-filter.md
features/add-pagination.md
features/add-categories.md
features/add-transfer.md
features/add-spending-limit.md
features/add-percentage-fee.md
```

## Template

```markdown
# Feature: <name>

## Problem

What user or engineering problem are we solving?

## Requirements

- ...

## Acceptance Criteria

- [ ] ...

## Non-goals

- ...

## Notes / Decisions

...
```

## Checklist for any feature that touches the API

1. Update `contracts/schemas/*.schema.json` first.
2. Update backend DTOs, route, service, repository (in that order of visibility).
3. Add or extend route tests; they must schema-validate real responses.
4. Update frontend wire types in `src/api/schemas.ts` and the mapper in `src/api/cardTransactions.ts`.
5. Update pages/components.
6. Run `./verify.sh`.

## Rules that apply to every feature

See `AGENTS.md`. In particular: Money is never a float, never mixed across currencies, and
never a JSON number.
