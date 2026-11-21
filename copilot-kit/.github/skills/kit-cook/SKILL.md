---
name: kit-cook
description: Implement a feature or execute an existing plan end to end - scout, pin down requirements, plan, implement, test, code review, finalize. Use when scope is known and code should be written, or when given a path to plan.md or a phase file. Asks before committing.
---

# Cook: structured implementation

End-to-end implementation with review gates. Principles: YAGNI, KISS, DRY; concise reports.

## Usage

```
/kit-cook <task description | path to plan.md or phase-NN-*.md> [--fast | --parallel | --auto | --no-test] [--tdd]
```

| Mode | Selected by | Research | Tests | Approval gates |
|------|-------------|----------|-------|----------------|
| interactive (default) | no flag | yes | yes | user approves each stage |
| fast | `--fast`, "quick" | skip | yes | user approves each stage |
| parallel | `--parallel`, 3+ independent features | optional | yes | user approves each stage |
| auto | `--auto`, "trust me" | yes | yes | skipped for low-risk work only |
| no-test | `--no-test`, "skip tests" | yes | skip | user approves each stage |
| code | input is a plan or phase path | skip | yes | starts at implementation |

`--tdd` combines with any mode: per phase, write tests for current behavior first, implement, then prove those tests still pass.

State the detected mode and why before doing anything else.

## Hard gates

1. **Plan before code.** Do not write implementation code until a plan exists and has been reviewed. `--fast` skips research, not the plan. Only an explicit user instruction ("just code it", "skip planning") overrides this.
2. **Scout before asking.** Before planning or asking questions, scan the codebase and tell the user in 3-6 bullets: project type and stack, modules relevant to the task, existing patterns for similar features, related docs and in-flight plans, and public APIs or schemas the task could affect. Skip only when the input is a plan path.
3. **Exact requirements.** Before producing a plan you must be able to state each of these in one concrete sentence; otherwise ask the user, with options grounded in what the scout found:
   - Expected output: the artifacts the user will see (files, behavior, endpoint and payload, command and flags).
   - Acceptance criteria: inputs, outputs and edge cases that must work.
   - Scope boundary: what is out of scope this round.
   - Non-negotiable constraints: stack, locations, naming, compatibility, performance.
   - Touchpoints: which existing files change and which contracts must stay stable.
4. **Review before finalize.** A `code-reviewer` review is mandatory in every mode. Never finalize without it.
5. **No silent side effects.** The work is not done until testing and review show: every acceptance criterion met; all tests pass, including modules sharing files or contracts with the change; no regression in any touchpoint or caller of changed functions; no new lint, type or compile errors; public contracts unchanged unless intentional and called out. With `--no-test`, the test item becomes a warning that you must surface when asking to finalize.
6. **Ask before committing.** Never commit or push without the user saying yes.

When review or testing reveals a regression or side effect: stop. Show what broke, the one-line cause, and 2-4 concrete options (revert the slice and re-plan, keep the change and update dependents, add a compatibility shim at the boundary, accept the old behavior as a bug). Then ask the user. Do not patch around it silently.

When asking the user anything, write the options and your reasoning in visible text first.

### Do not rationalize

- "Too simple to plan" - simple tasks hide the assumptions that cost the most.
- "I already know how" - then writing it down takes seconds.
- "The user wants speed" - plan, implement, done is faster than implement, debug, rewrite.

## Workflow

Track the steps in the todo list. After each step print one line: `Step N: <status> - <key numbers>`. Read `references/workflow-steps.md` from this skill's directory for the per-step detail and mode differences.

```
0 Detect mode -> 1 Scout + requirements -> 2 Research -> [gate] -> 3 Plan -> [gate]
-> 4 Implement (+ simplify if large) -> [gate] -> 5 Test -> [gate] -> 6 Code review -> 7 Finalize
```

1. **Scout and requirements** (gates 2 and 3 above). Use `/kit-scout` for anything beyond a small repo.
2. **Research** (interactive, auto, no-test): delegate to `researcher` custom agents in parallel. Gate: present findings, ask to proceed.
3. **Plan**: delegate to the `planner` custom agent, or run `/kit-plan --fast` (fast mode) or `/kit-plan --parallel` (parallel mode). Gate: present the phase overview and ask the user to approve, validate first (`/kit-plan validate`), revise, or abort. With a plan path as input, load it and skip to step 4.
4. **Implement** phase by phase, following the conformance checklist in the reference. Parallel mode delegates to several `fullstack-developer` custom agents with exclusive file ownership. Use `ui-ux-designer` for frontend design work. Large diffs get a `code-simplifier` pass. Gate: present files changed, ask to proceed.
5. **Test** (unless no-test): delegate to the `tester` custom agent. On failures delegate to `debugger`, fix, re-run. 100% pass required. No fake data, weakened assertions, or skipped tests. Gate: present results.
6. **Code review**: delegate to the `code-reviewer` custom agent with the scout summary and acceptance criteria, checking: (a) every acceptance criterion met, (b) no regression in touchpoints or blast radius, (c) no breaking change to public contracts unless called out, (d) matches existing patterns, (e) no new lint, type or compile errors. Fix critical findings and re-review, at most 3 cycles, then escalate to the user. The reviewer's score is advisory; it never approves by itself.
7. **Finalize** (never skip):
   1. Sync the plan: mark completed items in every phase file, not only the current one, then update the status and progress in `plan.md`. Delegate to `project-manager` for multi-phase plans.
   2. Delegate to `docs-manager` to update project docs if the change affects user-visible behavior, setup, architecture or contracts.
   3. Mark the todo list complete.
   4. Ask the user whether to commit. If yes, delegate to `git-manager` or run `/kit-git cm`.
   5. Report: what changed, verification evidence, unresolved questions.

## Auto mode limits

`--auto` skips approval gates only while all of these hold: tests pass, the reviewer reports no critical findings, and the change is low risk. Treat as high risk, and stop for the user before finalize or commit: auth or permissions, payments, schema or data migrations, deletion of data, public API or contract changes, security-sensitive code, infrastructure or deployment config. After 3 failed fix cycles, stop and ask.

## Delegation rules

- Testing, code review and docs updates must be delegated to the named custom agents. Do not self-review.
- Every delegation prompt includes: task, files to read, files it may modify, acceptance criteria, constraints, work context path, reports path. See `.github/copilot-kit/rules/orchestration-protocol.md`.
- Subagent replies end with `Status: DONE | DONE_WITH_CONCERNS | BLOCKED | NEEDS_CONTEXT`, `Summary:`, `Concerns/Blockers:`. On BLOCKED or NEEDS_CONTEXT change the context, scope or approach; do not resend the same prompt.
- Paths for plans and reports come from `## Paths` and `## Naming` in the session context.

## Workflow position

Follows `/kit-plan` or `/kit-brainstorm`. Precedes `/kit-test` and `/kit-code-review` when run standalone. For bugs use `/kit-fix` instead.
