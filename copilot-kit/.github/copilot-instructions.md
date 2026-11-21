# Engineering Kit Contract

This file is loaded in every session. Keep it short. Load the linked rule files only when the current task needs them.

## Core Rules

- Optimize for the user's workflow: clear prompts, useful errors, real implementation, no performative ceremony.
- Before asking the user to choose between approaches, present the options and your reasoning in visible text first. Write every option so it stands alone.
- Before implementation, read `README.md` and the relevant files in `docs/` when they exist.
- Use the repo's existing patterns, commands, and public contracts before inventing new ones.
- Prefer YAGNI, KISS, and DRY in that order. Prefer small, focused changes.
- Implement real behavior. No fake data, mocks, or shortcuts added only to satisfy a check.
- Do not hide failing tests, lint, type, build, or syntax errors. Report outcomes as they are.
- Never commit secrets, dotenv files, tokens, private keys, or personal data.

## Session Context

A session-start hook injects `## Paths`, `## Active Plan`, and `## Naming`. That context is given once, so keep following it for the whole session:

- Write plans under the Plans path and reports under the Reports path, using the Naming patterns.
- Keep Markdown you create inside the Plans or Docs path unless the user asks for another location.
- Read the active plan's `plan.md` before working on it.

If that context is missing, the defaults are `plans/`, `docs/`, and `plans/reports/`.

## File Naming

- Use descriptive kebab-case names for JS, TS, shell, and Markdown files. Long names are fine when they say what the file is for.
- Follow each language's convention elsewhere: snake_case for Python, Go, Rust; PascalCase for C#, Java, Kotlin, Swift.
- Never name a report `report.md`, `review.md`, or `notes.md`.

## Guards

A pre-tool hook enforces two limits. Work with them, not around them.

- Heavy directories (`node_modules`, `dist`, `build`, `.git`, `.venv`, and others in `.github/copilot-kit/.kitignore`) cannot be read or scanned. Narrow the path to source files.
- Reading a secret-like file (`.env`, `*.pem`, `*.key`, `*credentials*`) asks the user for permission. If the user declines, continue without that file.

## Workflows

Pick the skill that matches the request. Domain knowledge first, workflow second.

| Intent | Sequence |
|---|---|
| New feature | `/kit-plan` then `/kit-cook` then `/kit-test` then `/kit-code-review` |
| Existing plan | `/kit-cook <plan-path>` |
| Small change | `/kit-cook --fast` |
| Bug or CI failure | `/kit-fix` |
| Investigation | `/kit-scout` then `/kit-debug` then `/kit-brainstorm` then `/kit-plan` |
| Documentation | `/kit-docs` |
| Commit, push, pull request | `/kit-git` |

## Delegation

Custom agents: planner, researcher, tester, debugger, code-reviewer, code-simplifier, fullstack-developer, docs-manager, git-manager, project-manager, brainstormer, journal-writer, ui-ux-designer.

- A subagent knows only what its prompt says. Give it the task, files to read, files it may modify, acceptance criteria, and constraints.
- Run subagents in parallel only when file ownership does not overlap.
- Expect each reply to end with `Status: DONE | DONE_WITH_CONCERNS | BLOCKED | NEEDS_CONTEXT`. On `BLOCKED` or `NEEDS_CONTEXT`, change the context, scope, or approach instead of repeating the same prompt.

## On-Demand Rules

- Implementation and verification: `.github/copilot-kit/rules/development-rules.md`
- Feature and debug workflow shape: `.github/copilot-kit/rules/primary-workflow.md`
- Subagents and parallel work: `.github/copilot-kit/rules/orchestration-protocol.md`
- Plans and docs: `.github/copilot-kit/rules/documentation-management.md`
- Review, audit, and scope cuts: `.github/copilot-kit/rules/review-audit-self-decision.md`
