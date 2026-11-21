# Plan Review Gates

Two gates harden a plan before implementation. Red team first, validation second. Neither gate writes implementation code.

## Gate 1: Red-team review

Hostile reviewers try to break the plan. You adjudicate; the user decides what is applied.

### Steps

1. Read `plan.md` and every `phase-*.md` in full.
2. Choose reviewers by plan size:

   | Phases | Reviewers | Lenses |
   |--------|-----------|--------|
   | 1-2 | 2 | Security adversary, Assumption destroyer |
   | 3-5 | 3 | plus Failure-mode analyst |
   | 6+ | 4 | plus Scope and complexity critic |

3. Delegate to that many `code-reviewer` custom agents in parallel. Each prompt contains: the lens, the plan file paths, the reports path, and the instruction to attack the plan rather than improve its wording.

   - **Security adversary**: auth gaps, injection, data exposure, privilege escalation, secrets handling.
   - **Assumption destroyer**: claims about the codebase, APIs, data shape or libraries that are unproven or false.
   - **Failure-mode analyst**: partial failure, concurrency, migration and rollback, missing error paths, ordering between phases.
   - **Scope and complexity critic**: over-engineering, unneeded abstractions, phases that can merge, work that can be deferred.

   Each finding must have: severity (Critical, High, Medium), the plan section it targets, evidence with at least one `path/to/file.ext:line` citation, and a suggested fix.

4. Collect findings, remove duplicates, sort by severity, keep at most 15.
5. Evidence filter: a finding with no `file:line` citation is rejected with the reason "No codebase evidence". Do not weigh its merit.
6. For each remaining finding propose Accept or Reject with a one-line reason.
7. Show the adjudicated list in visible text, then ask the user:
   - Apply accepted findings
   - Review each one (then per finding: apply, reject, or modify the suggested fix)
   - Reject all
8. Apply accepted findings to the affected phase files. Add a `## Red Team Review` section to `plan.md` with a table: finding, severity, disposition, files changed.
9. Run the whole-plan consistency sweep (below).

Report: findings by severity, accepted versus rejected, files modified, sweep result.

## Gate 2: Validation interview

Confirm the plan's assumptions and decisions with the user before code is written.

### Steps

1. Read `plan.md` and every phase file. Collect decision points, assumptions, risks and trade-offs.
2. Verification pass. Check the plan's claims against the repository before asking anything:

   | Phases | Depth |
   |--------|-------|
   | 1-2 | Spot-check about 5 claims per phase: file paths and symbols exist |
   | 3-4 | About 10 per phase: also interfaces, endpoints and contracts between phases |
   | 5+ | About 15 per phase: also dependency order and test commands |

   Record each claim as VERIFIED, FAILED or UNVERIFIED. Try to resolve every `[UNVERIFIED]` tag. Never auto-correct the plan: every failure becomes an interview question, with the closest real alternative offered as the recommended option. If a red-team review already verified the plan, only resolve the remaining `[UNVERIFIED]` tags.
3. Write 3 to 8 questions, fewer for a simple plan. Each has 2 to 4 concrete options with the recommended one marked. Ask only about real decision points that could change the implementation.
4. Before the first question, print a 5 to 10 bullet recap of the plan: phases, key decisions, and the assumptions the interview will probe. The user may not have the plan in front of them.
5. Ask the user, at most 4 related questions at a time. Name the plan section each question refers to so it stands alone.
6. Append a `## Validation Log` section to `plan.md`:

   ```
   ### Session <date>
   - Q: <question> -> A: <answer> -> Impact: <phase files affected>
   ### Verification results
   - Claims checked: N | Verified: N | Failed: N | Unverified: N
   - Failures: <list with file:line evidence>
   ```

7. Propagate each decision into the affected phase files.
8. Run the whole-plan consistency sweep.

Report: questions asked, decisions confirmed, phase files changed, sweep result, and a recommendation to proceed or revise. If any verification failure is still open, the plan is not ready: revise first.

## Whole-plan consistency sweep

Run after either gate changes a plan file. Local edits leave stale claims elsewhere.

1. Turn the accepted findings or validation answers into a list of decision deltas (old term or assumption, new one).
2. Re-read `plan.md` and every `phase-*.md`.
3. Search all plan files for the old terms, rejected assumptions, renamed files, APIs or fields, and superseded steps.
4. Reconcile overview, phase table, dependencies, requirements, implementation steps, success criteria and earlier review logs.
5. Where the same query, command or contract appears in both prose and an embedded draft, update every copy.
6. Append `### Whole-Plan Consistency Sweep` under the gate's section in `plan.md` with: deltas applied, files touched, contradictions remaining.
7. If contradictions remain, list them, ask the user, and do not present the plan as ready.

## After a gate

End by asking the user for the next step: run the other gate, start `/kit-cook <absolute-path>/plan.md`, or stop. Add `--auto` to the cook command only when the user explicitly asks for autonomous implementation.
