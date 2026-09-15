# Features

Each interview work item gets one Markdown file in this directory. The file is the shared
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

## API Changes

For an application JSON body-shape change, follow `contracts/AGENTS.md`. For other API changes,
follow the applicable scoped `AGENTS.md` and README API rules. The work-item spec records the
change-specific requirements rather than repeating those rules.

## Completed Specs

Completed specs are historical records of their work items. A later spec can supersede behavior it
explicitly changes, but completed acceptance criteria do not create repository-wide rules. Use the
task's active spec for new work, the scoped `AGENTS.md` files for implementation rules, and the
README for current API behavior.
