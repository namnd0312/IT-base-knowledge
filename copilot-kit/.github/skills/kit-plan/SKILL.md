---
name: kit-plan
description: Create a phased implementation plan (plan.md plus phase files) through scope challenge, research, codebase analysis and solution design, or run the red-team / validate gates on an existing plan. Use when work needs phases, architecture or a roadmap before coding. Never writes implementation code.
---

# Planning

Produce a self-contained implementation plan that another session can execute without this conversation. Honor YAGNI, KISS, DRY. Be honest, blunt and concise.

## Hard rules

- **Never implement.** This skill writes plan files only. No source code edits, no scaffolding.
- **Always end by asking the user** which next step they want (see "Handoff"). Do not start `/kit-cook` on your own.
- Write plans only under the plans directory given in the session context `## Paths`. Never invent a location.
- Before any decision question, write the options, trade-offs and your recommendation in visible text, then ask the user. Each option must stand alone.

## Usage

```
/kit-plan <task> [--fast | --hard | --deep | --parallel | --two] [--tdd]
/kit-plan validate [plan-dir]
/kit-plan red-team [plan-dir]
```

No arguments or unclear intent: ask the user whether to create a plan, validate a plan, or red-team a plan.

## Modes

With no mode flag, pick one from the task and say which and why. Ask the user if unsure.

| Mode | Pick when | Research | Red team | Validate |
|------|-----------|----------|----------|----------|
| `--fast` | Simple, clear scope, no unknowns | skip | skip | skip |
| `--hard` | Complex, unfamiliar domain or new tech | 2 researchers | yes | offer |
| `--deep` | Major refactor across 5+ areas | 2-3 researchers + scout per phase | yes | yes |
| `--parallel` | 3+ independent features or layers | 2 researchers | yes | offer |
| `--two` | Several valid approaches | 2 researchers | after user picks | offer |

`--tdd` combines with any mode: every implementation phase gets a tests-first structure (see the template reference).

## Workflow

Track these steps in the todo list.

### 1. Check existing plans

- Read `## Active Plan` in the session context. If a plan is active, ask the user: continue it, or create a new one.
- Scan unfinished plans in the plans directory. If one overlaps the new work (same files, same feature area, shared dependency), record the relationship in both `plan.md` files with `blockedBy` / `blocks` frontmatter. If the relationship is ambiguous, show the overlap and ask the user.

### 2. Scope challenge

Skip with `--fast`, or when the task is trivial (single-file change, unambiguous one-liner request).

Answer briefly, in visible text:

1. What already exists that solves part of this? Flag anything the plan would rebuild.
2. What is the minimum change set? Name the nice-to-haves that can be deferred.
3. Complexity smells: more than 8 files, more than 2 new classes or services, more than 3 phases. Justify or shrink.

Then ask the user to choose: **expand scope**, **hold scope**, or **reduce scope**. Expansion suggests `--hard` or `--two`; reduction suggests `--fast`. Raise scope concerns once here, then respect the choice. Never silently shrink or grow scope later.

### 3. Research (skip in fast mode or when research reports were supplied)

Delegate to at most 2 `researcher` custom agents in parallel (3 in deep mode), each on a different aspect. Give each the plan directory and the reports path. Collect the report file paths.

### 4. Codebase analysis

Read `README.md` and the project docs (`codebase-summary.md`, `code-standards.md`, `system-architecture.md` in the docs directory). If docs are stale or missing, run `/kit-scout` for the affected area. In deep mode, scout once per planned phase to inventory files to create, modify or delete, existing tests, and risky dependencies.

### 5. Write the plan

Read `references/plan-file-templates.md` from this skill's directory, then delegate to the `planner` custom agent with the task, scope decision, mode, research and scout report paths, and the plan directory. Create the directory using the plan-dir pattern in `## Naming`:

```
plans/<timestamp>-<slug>/
  plan.md
  phase-01-<name>.md
  phase-02-<name>.md
  reports/
```

Requirements:

- `plan.md` stays under 80 lines: status, phase table with links, dependencies, acceptance criteria.
- Every phase file names exact files to create, modify or delete, ordered steps, and verifiable success criteria.
- Mark any claim about the codebase that was not checked as `[UNVERIFIED]`.
- `--parallel`: each file is owned by exactly one phase; `plan.md` carries a dependency graph and file-ownership matrix.
- `--two`: write both approaches with trade-offs and a recommendation, present them, ask the user to pick, then keep only the chosen one.

When the files exist, run:

```
node .github/copilot-kit/scripts/set-active-plan.cjs <plan-dir>
```

### 6. Review gates

Read `references/plan-review-gates.md` from this skill's directory.

- Hard, deep, parallel, two: run the red-team review first, then validation (deep: always; others: ask the user whether to validate now).
- Fast: skip both.
- Red team runs before validation because it may change the plan, and validation must confirm the final plan.

### 7. Whole-plan consistency sweep

Mandatory whenever a gate edited any plan file. Re-read `plan.md` and every phase file. Search for stale terms, rejected assumptions, renamed files or APIs, superseded decisions, and duplicated drafts that now disagree. Reconcile them. If contradictions remain, list them and ask the user. Do not recommend implementation while any remain.

### 8. Handoff (always the last step)

Report the absolute plan path and a short summary, list unresolved questions, then ask the user what to do next. Put the recommended option first and label it "(Recommended)".

| Option | Recommend when |
|--------|----------------|
| `/kit-plan validate <plan-dir>` | Moderate or complex plan with unconfirmed assumptions |
| `/kit-plan red-team <plan-dir>` | Touches security, auth, payments, data integrity, public APIs or infrastructure |
| `/kit-cook <absolute-path>/plan.md` | Small, well-understood, low-risk plan |
| Stop here | User wants to read or share the plan first |

Omit a gate that already ran in this session. Append `--parallel` or `--tdd` to the cook command when the plan was created with that flag. Suggest starting implementation in a fresh session, which is why the path must be absolute. Add `--auto` to the cook command only if the user explicitly asks for autonomous implementation.

## Subcommands

`validate` and `red-team` resolve the plan from the argument, else from `## Active Plan`; if neither exists, ask the user for a path. Both are defined in `references/plan-review-gates.md`, both finish with the consistency sweep, and both end by asking the user for the next step.

## Quality bar

- Specific enough for a junior developer to execute.
- Validated against existing code patterns, not assumed.
- Security and performance concerns addressed where relevant.
- Follows `.github/copilot-kit/rules/development-rules.md` and `documentation-management.md`.

## Workflow position

Follows `/kit-brainstorm` or `/kit-scout`. Precedes `/kit-cook`, then `/kit-test` and `/kit-code-review`.
