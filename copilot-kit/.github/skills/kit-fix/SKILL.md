---
name: kit-fix
description: Fix a concrete bug, error, failing test, type or lint error, or CI failure by scouting, proving the root cause, applying a minimal fix, then verifying and adding a regression guard. Use when something is broken and should be repaired. Stops after 3 failed fix attempts.
---

# Fix

Root-cause-first bug fixing. A patched symptom is a failed fix.

## Usage

```
/kit-fix <issue description, error output, failing test, or CI run> [--auto | --review | --quick | --parallel]
```

| Mode | Use for | Behavior |
|------|---------|----------|
| `--auto` (default) | Simple to moderate issues | Proceeds without pausing while verification is clean and risk is low |
| `--review` | Critical or production code | Pauses for user approval after diagnosis, after the fix, and before finalize |
| `--quick` | Type errors, lint, trivial bugs | Short scout, diagnose, fix, verify cycle |
| `--parallel` | 2+ independent issues | One `fullstack-developer` custom agent per issue, exclusive files each |

If the request names no mode and the issue is not obviously trivial, say which mode you recommend and why, then ask the user to pick.

## Hard gates

1. **No fix before scout and diagnosis.** Steps 1 and 2 always run. `--quick` shortens them; it does not skip them.
2. **Prove the root cause.** Do not propose a fix until each of these is one concrete sentence:
   - Exact symptom: the error text or failing assertion, copied verbatim.
   - Reproduction: the minimal commands, inputs and environment that trigger it.
   - Expected versus actual behavior.
   - Root cause: the specific defect, with `file:line` evidence. Not the place the error surfaces.
   - Why now: the commit, data shape, environment or dependency change that exposed it.
   - Blast radius: every code path that depends on the broken behavior or shares the cause.

   If any item contains "probably" or "I think", investigate more or ask the user for logs, repro steps or environment details. Never guess.
3. **Three strikes.** If 3 fix attempts fail verification, stop. Question the design, summarize what each attempt showed, and discuss with the user before trying anything else.
4. **No silent side effects.** The fix is done only when: the original repro no longer fails; tests in changed files and affected modules pass; nothing in the blast radius regressed; no new lint, type or compile errors; public contracts unchanged unless intentional and called out.
5. **Ask before committing.**

If verification finds a regression or side effect: stop. Show what broke, the one-line cause, and 2-4 options (revert and try another angle, keep the fix and update dependents, narrow the fix, accept because the old behavior was the bug). Ask the user. Write options and reasoning in visible text before asking.

### Do not rationalize

- "I can see the problem" - seeing the symptom is not knowing the cause.
- "Quick patch now, investigate later" - later does not come.
- "It's probably X" - probably means untested.
- "One more attempt" after two failures - the approach is wrong.
- "Tests pass, done" - without a regression guard the same bug class returns.

## Workflow

Track steps in the todo list. After each step print `Step N: <result>`. Read `references/diagnosis-and-prevention.md` from this skill's directory before step 2.

### Step 1: Scout (mandatory)

Collect before forming any hypothesis:

1. Project type, language, framework.
2. The files where the symptom surfaces, plus their direct callers and dependents.
3. Tests covering the affected area.
4. Recent commits touching those files (`git log --oneline -20 -- <files>`).
5. How similar code is written elsewhere, so the fix matches.

Use `/kit-scout` for a wide search. Then tell the user what you found in 3-6 bullets. Quick mode: the affected file and its direct dependencies only.

### Step 2: Diagnose (mandatory)

1. Capture the broken state: exact error output, failing test command and output, stack trace, relevant logs. This is the baseline for step 5.
2. Follow `/kit-debug`: observe, form explicit hypotheses with confirming and refuting evidence, test each against the code, trace backward from symptom to origin.
3. For a hard or multi-component issue delegate the investigation to the `debugger` custom agent.
4. If two hypotheses are refuted, step back and ask what would cause this deliberately, vary scale, consider timing, environment and concurrency.
5. Write the diagnosis: root cause, evidence chain, affected scope, recommended fix.

Review mode: present the diagnosis and ask for approval before fixing.

### Step 3: Classify

| Level | Signs | Path |
|-------|-------|------|
| Simple | One file, clear error, type or lint | Fix directly, verify |
| Moderate | Several files, cause needed tracing | Full steps 4-6 with a todo list |
| Complex | System-wide or design impact | Present 2-3 fix approaches, ask the user; consider `/kit-plan` before fixing |
| Parallel | 2+ unrelated issues | One `fullstack-developer` agent per issue, then verify together |

### Step 4: Fix

Change the root cause, not the place the error appears. Smallest change that works. Match existing patterns. No unrelated cleanup.

### Step 5: Verify and prevent (mandatory)

1. Re-run the exact commands captured in step 2 and compare before and after. No claim without fresh output.
2. Add or update a regression test that fails without the fix and passes with it (optional in quick mode when the type checker is the test).
3. Sweep the blast radius: run its tests and walk dependent code paths; confirm contracts are unchanged.
4. Run type-check, lint, compile and the test suite. Delegate the test run to `tester` for moderate and complex fixes.
5. Delegate to `code-reviewer` (moderate and complex) with the scout summary and diagnosis, asking: is the root cause addressed rather than masked; any broken logic in the blast radius; any new failure mode; does it match existing patterns.
6. Add guards where they apply (see the reference).

On failure go back to step 2 with the new information and count the attempt. Third failure: gate 3.

### Step 6: Finalize

1. Report: root cause, changes and files, before/after evidence, tests and guards added, side-effect sweep result, confidence.
2. If the fix belongs to an active plan, update the matching phase file and `plan.md` status.
3. Delegate to `docs-manager` only if behavior, setup or contracts changed.
4. Mark the todo list complete.
5. Ask the user whether to commit; if yes delegate to `git-manager` or run `/kit-git cm`.

High-risk fixes (auth, payments, data migrations or deletion, public contracts, infrastructure) always stop for user approval before finalize, even in auto mode.

## Workflow position

Follows `/kit-debug` or `/kit-scout`. Precedes `/kit-test` and `/kit-code-review`. For new features use `/kit-cook`.
