# Repository rules

This repository is optimized for short AI-assisted coding exercises. This file holds only the rules
that apply to every change. Read it before the scoped `AGENTS.md` for the code you are editing; the
most specific applicable `AGENTS.md` wins.

## Purpose

This project exists to learn and practise AI coding. It is not a product and has no external users.
Unless a task explicitly says otherwise, do not preserve compatibility across code changes: renaming
an API path, a table, a schema, or a type is fine without shims, migrations, legacy-detection code, or
deprecation periods. Document breaking changes in the PR description and move on.

## General

- Inspect existing patterns before editing. Copy the neighbouring style.
- Prefer minimal diffs. Do not refactor unrelated code.
- Do not add dependencies unless the task requires them.
- Keep the ownership boundaries in the map below explicit. Do not move logic across them to save a file.
- Run the relevant tests after modifying code; each scope's `AGENTS.md` names what to run. `./verify.sh` runs everything CI runs.

## Repository map

| Path | What lives there | Rules |
|---|---|---|
| `backend/` | Kotlin / Ktor HTTP API over PostgreSQL through plain JDBC | `backend/AGENTS.md` |
| `frontend/` | React / TypeScript single-page app; talks to the backend only over HTTP | `frontend/AGENTS.md` |
| `contracts/schemas/` | JSON Schema for application JSON request and response bodies | `contracts/AGENTS.md` |
| `features/` | One Markdown spec per work item; agree on it before implementing | `features/README.md` |

## File placement

`directory-structure.md` is the source of truth for file placement and placement-related naming.
The scoped `AGENTS.md` files own implementation rules for their subtrees; do not copy those rules
into other documents.

## Feature development workflow

For substantial new features or changes that span API contracts, persistence, backend behavior, and frontend behavior, follow [`docs/feature-development-prompt.md`](docs/feature-development-prompt.md) as the implementation workflow. Use it to drive the work from feature specification and API design, agreed before any implementation, through JSON Schema, domain and database modeling, routing, service and repository implementation, frontend integration, tests, and final verification. It is a workflow guide, not a second source of repository policy: this file, the applicable scoped `AGENTS.md` files, `directory-structure.md`, the shared schemas, and the work item's `features/*.md` specification remain authoritative for their respective concerns, and the most specific repository rule wins if the workflow document conflicts with them.

## Money

Money is a domain value: an integer amount in the currency's minor unit plus the currency. This
invariant holds in every scope; each scope's `AGENTS.md` says how its code expresses it.

- Never represent money as a floating-point number, and never assume every currency has 2 decimal places.
- Currency is part of the value. Never add, subtract, compare, aggregate, sort, or filter amounts across different currencies as if they were one.
- A `CardTransaction` `amount` is a magnitude (> 0); direction comes from `type` (CREDIT/DEBIT).
- No FX conversion unless a task explicitly requires it.

## AutoForge

- Do not edit the active feature specification during an AutoForge execution run.

## Non-code artifacts

Issues, PR descriptions, specs, plans, reviews, and every other non-code artifact give readers the
context and judgment the diff cannot, not a narrated diff or filler, and are published in full on
GitHub. The full rules:

@docs/non-code-rules.md

## PR rules

- Merge a PR only when I explicitly ask; squash-merge unless I say otherwise.
- When reviewing a PR, post everything (findings, spec and standards checks, assessment, observations, verification, summary) as one comment on the PR.
- After a PR is merged, clean up local branches and worktrees, fast-forward main, then update and close related issues.

## Git conventions

Never include AI attribution in commit messages, PR titles, or PR descriptions, in any form: no
`Co-Authored-By: Claude`, `Generated with ...` footers, sign-offs naming an AI agent or vendor
(Claude, Anthropic, GPT, OpenAI, …), or `Claude-Session:` trailers and session URLs — even when a
tool inserts them automatically. When squash-merging, write a clean commit message that describes
only the change itself.