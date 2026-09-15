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
| `backend/` | Kotlin / Ktor HTTP API over SQLite through plain JDBC | `backend/AGENTS.md` |
| `frontend/` | React / TypeScript single-page app; talks to the backend only over HTTP | `frontend/AGENTS.md` |
| `contracts/schemas/` | JSON Schema for application JSON request and response bodies | `contracts/AGENTS.md` |
| `features/` | One Markdown spec per work item; agree on it before implementing | `features/README.md` |

## File placement

`directory-structure.md` is the source of truth for file placement and placement-related naming.
The scoped `AGENTS.md` files own implementation rules for their subtrees; do not copy those rules
into other documents.

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

Anything a task produces that is not code (design docs, specs, plans, research notes, assessments) must end up on GitHub. A copy on disk alone does not count.

- Write non-code artifacts in English (see the Language rule above).
- Post the artifact as a comment on the relevant issue. If the work has no issue yet, create one first; if the artifact is about changes already under review, post it to the PR instead.
- Post the full content, not a summary or a file path. Several child repos keep planning notes in gitignored local directories (for example `__ref__/plan/` in `ltbase.api`, see #497); a local working copy is fine, but it is invisible to everyone else and does not survive the branch.
- Do not force-add gitignored planning files to make them shareable. The issue comment is the sharing mechanism.
- Say in the comment which artifact it is and where the working copy lives, so a later reader knows whether they are looking at a plan, a spec, or a review.
- Anything that must become a durable repository convention still belongs in that repo's `docs/` (an ADR, runbook, or reference page). The issue comment records the thinking; `docs/` records the decision.

## PR rules

- Do not merge a PR unless I explicitly ask you to.
- When reviewing a PR, post everything (findings, spec and standards checks, assessment, observations, verification, summary) as one comment on the PR.
- When I ask you to merge a PR, squash-merge by default unless I ask for something else.
- After a PR is merged, clean up local branches and worktrees, fast-forward main, then update and close related issues.

## Git conventions

Never include AI attribution in commit messages, PR titles, or PR descriptions, in any form. That means no

- `Co-Authored-By: Claude`
- `Generated with ...` footers
- sign-offs or footers naming an LLM or AI agent (OpenAI, GPT, Claude, Anthropic, and the like)
- `Claude-Session:` trailers or session URLs (`https://claude.ai/code/session_...`), even when a tool inserts them automatically

When squash-merging, write a clean commit message that describes only the change itself.