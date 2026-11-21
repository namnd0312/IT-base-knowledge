# Cook Workflow Steps

Detail for each step. Gates marked [gate] need user approval and are skipped only in auto mode for low-risk work.

## Flow by mode

```
interactive: 0 -> 1 -> 2 -> [gate] -> 3 -> [gate] -> 4 -> [gate] -> 5 -> [gate] -> 6 (user approves) -> 7
auto:        0 -> 1 -> 2 -> 3 -> 4 -> 5 -> 6 (auto if clean and low risk) -> 7 -> next phase
fast:        0 -> 1 -> skip -> 3 (fast plan) -> [gate] -> 4 -> [gate] -> 5 -> [gate] -> 6 (single pass) -> 7
parallel:    0 -> 1 -> 2? -> [gate] -> 3 (parallel plan) -> [gate] -> 4 (multi-agent) -> [gate] -> 5 -> [gate] -> 6 -> 7
no-test:     0 -> 1 -> 2 -> [gate] -> 3 -> [gate] -> 4 -> [gate] -> skip -> 6 (user approves) -> 7
code:        0 -> skip -> skip -> load plan -> 4 -> [gate] -> 5 -> [gate] -> 6 (user approves) -> 7
```

## Step 0: Mode detection

1. A path ending in `plan.md` or `phase-NN-*.md` means code mode. Read the plan, and run `node .github/copilot-kit/scripts/set-active-plan.cjs <plan-dir>`.
2. Otherwise explicit flags win, then keywords in the request, then interactive.
3. Add the workflow steps to the todo list.

## Step 1: Scout and requirements

- Scan the code (use `/kit-scout` beyond a small repo), read `README.md` and relevant project docs, list in-flight plans touching the same area.
- Print the 3-6 bullet context summary.
- Pin down the five requirement items (expected output, acceptance criteria, scope boundary, constraints, touchpoints). Ask until each is concrete. Ground every option in real files and patterns, for example "extend `src/api/users.ts` (existing pattern) or add `src/api/profile.ts`?".

## Step 2: Research

- Delegate to `researcher` custom agents in parallel, each on a distinct question. Reports stay under 150 lines and go to the reports path.
- Parallel mode: optional, at most 2 researchers.
- [gate] Present a summary. Options: proceed to planning, more research, abort.

## Step 3: Plan

- Interactive, auto, no-test: delegate to `planner` with the requirements and research report paths. Output is `plan.md` plus `phase-NN-<name>.md` files in the plan directory from the session context.
- Fast: `/kit-plan --fast` with scout results only.
- Parallel: `/kit-plan --parallel` to get a dependency graph and file-ownership matrix.
- [gate] Present phases. Options: approve, validate first (`/kit-plan validate <plan-dir>`), request revisions, abort.

## Step 4: Implement

Turn each unchecked plan item into a todo entry in priority order; mark it in progress when you start and complete when it is done.

Conformance checklist, before writing code for a phase:

1. Read the project's code standards doc and confirm naming, structure and error-handling conventions.
2. Read adjacent code in the files being changed and match its imports, logging and error style.
3. Look for existing helpers before adding a utility.
4. Confirm interface contracts so new code extends the current surface instead of creating a parallel one.
5. Cross-check the phase's file list so every listed file is addressed.

After each file:

- run the project's compile or type-check command,
- confirm the code matches adjacent conventions,
- confirm no circular dependency or dead import was introduced.

Rules for all modes: implement real behavior, no stubs or simulated results; keep changes inside the agreed scope; update existing files rather than creating "enhanced" copies.

### Tests-first (`--tdd`)

Per phase:

1. Write tests that capture the current behavior of the code about to change.
2. Implement the change.
3. Re-run those tests plus compile checks. A failure means the change broke existing behavior; fix it before continuing.

### Parallel mode

- Delegate each independent phase to a `fullstack-developer` custom agent.
- Each agent gets an exclusive file list. No file is edited by two agents.
- Wait for the whole parallel group before starting phases that depend on it.

### Simplify pass

After implementation, measure the uncommitted diff (`git diff --numstat HEAD`). If it exceeds roughly 400 changed lines, 8 files, or 200 added lines in one file, delegate to `code-simplifier` scoped to the modified files with the instruction to preserve behavior exactly. Note whether it changed anything. Do not loop.

[gate] Present files changed and key decisions. Options: proceed to testing, request changes, abort.

## Step 5: Test (skip in no-test mode)

- Cover happy path, edge cases and error paths for the new behavior.
- Delegate the run to the `tester` custom agent. Give it the changed files and acceptance criteria.
- On failure delegate diagnosis to `debugger`, fix the cause, re-run. Repeat until 100% pass.
- Forbidden: fake data or mocks added only to pass, commented-out tests, loosened assertions, skipping the delegated run.
- [gate] Present results. Options: proceed to review, request fixes, abort.

## Step 6: Code review

Delegate to `code-reviewer` with this content:

```
Review the changes against these mandatory checks:
(a) every acceptance criterion is met;
(b) no regression to business logic in touchpoints or blast radius;
(c) no breaking change to public contracts (signatures, schemas, APIs, env vars) unless explicitly called out;
(d) follows existing patterns found during scouting;
(e) no new lint, type or compile errors anywhere.
Context - scout summary: <summary>; acceptance criteria: <criteria>; plan: <path>.
Return critical findings, warnings, suggestions, an advisory score out of 10,
and explicitly flag any side effect.
```

- Interactive, parallel, code, no-test: show findings, then ask the user: fix critical findings, approve, or abort. After fixes, re-run tests and re-review. Maximum 3 cycles, then escalate.
- Auto: fix critical findings automatically, maximum 3 cycles. Proceed without asking only when there are no critical findings, tests pass, and the change is low risk. Otherwise stop and ask.
- Fast: one review pass, no fix loop; the user approves or aborts.
- Any flagged side effect triggers the stop-and-ask procedure in the main skill file.

## Step 7: Finalize

1. Plan sync: sweep every `phase-NN-*.md` in the plan directory, tick each completed item, set each phase status, then update the status and phase table in `plan.md` from the real checkbox state. Report any completed work that maps to no phase. For multi-phase plans delegate this to `project-manager`.
2. Docs: delegate to `docs-manager` when behavior, setup, commands, architecture or public contracts changed. See `.github/copilot-kit/rules/documentation-management.md`.
3. Tell the user about any new environment variables, keys or setup steps the change requires.
4. Ask whether to commit. Only on yes: delegate to `git-manager` (conventional commit, no AI attribution, secret scan first). Push only if the user asked for it.
5. Final report: summary, files changed, test and review evidence, unresolved questions.

Auto mode continues with the next phase from Step 4. Other modes ask the user before starting the next phase.

## Completion check

The run is incomplete if any of these is missing: a reviewed plan, a delegated test run (unless no-test), a delegated code review, the plan sync, and the commit question.
