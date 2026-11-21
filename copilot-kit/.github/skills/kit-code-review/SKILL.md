---
name: kit-code-review
description: Review code for bugs, regressions, security problems, spec mismatch and maintainability with file:line evidence. Use after implementing or fixing code, before commit or merge, or to review pending changes, a PR number, a commit hash, or the whole codebase. Also covers handling review feedback received.
---

# Code Review

Production-readiness review. Technical correctness over social comfort. YAGNI, KISS, DRY. Be direct and concise.

Assume reviewed code may be machine-written: do not trust polished structure, confident comments or happy-path tests. Verify behavior, rule compliance and scope discipline from evidence. No rubber stamps.

## Usage

```
/kit-code-review                 recent changes in this session
/kit-code-review --pending       staged and unstaged changes
/kit-code-review #123 | <PR URL> a pull request
/kit-code-review <commit-hash>   one commit (7+ hex characters)
/kit-code-review codebase        full codebase scan
/kit-code-review codebase parallel   several scoped reviewers in parallel
```

With no arguments and no recent changes in context, ask the user what to review: pending changes, a PR number, a commit hash, or the codebase.

## Step 1: Resolve what is being reviewed

| Mode | Get the diff | Intent source |
|------|--------------|---------------|
| Pending | `git diff` and `git diff --cached`, plus untracked files from `git status` | Conversation or active plan |
| PR | `gh pr view <n> --json title,body,baseRefName,headRefName,files` then `gh pr diff <n>` | PR title and body |
| Commit | `git show <hash> --stat` then `git show <hash>` | Commit message |
| Default | Changes made this session; fall back to pending | Conversation or active plan |
| Codebase | No diff. Read the docs' codebase summary, then scan by module | Project docs and standards |

State the mode, the file count and the intent you are reviewing against before starting.

## Step 2: Edge-case scout

Before judging the diff, find what it touches that the diff does not show. For 3 or more changed files use `/kit-scout` with this focus:

- callers and dependents of every changed function, type and export,
- data flows into and out of the changed code,
- error paths and boundary conditions (empty, null, max size, concurrent access),
- tests covering the affected area, and gaps.

## Step 3: Stage 1 - spec compliance

Run when a plan, phase file, issue or explicit request defines the work. It must pass before stage 2.

1. Read the spec. List every requirement and acceptance criterion.
2. Mark each against the code: PASS, MISSING, or EXTRA (present but not requested).
3. Verdict: any MISSING is a fail, to be fixed and re-reviewed. Unjustified EXTRA is a warning to raise with the user.

Well-written code that does not match the request is still wrong.

## Step 4: Stage 2 - code quality

Read `references/review-checklist.md` from this skill's directory and apply both passes: critical (blocking) and informational.

- Small diffs: review directly.
- Larger diffs or when an independent read matters: delegate to the `code-reviewer` custom agent with what was implemented, the plan or requirements, the diff range (base and head SHA, or "pending"), the scout findings, and the reports path.
- `codebase parallel`: split by module or layer, delegate one `code-reviewer` per scope with no overlap, then merge and de-duplicate findings.
- Also check project rules: `.github/copilot-kit/rules/development-rules.md` and the project's code standards doc.

Every finding cites `file:line`, states the problem in one line and the fix in one line. Findings without evidence are dropped.

## Step 5: Report

```
Review: <mode> - N findings (X critical, Y important, Z minor)
Spec compliance: PASS | FAIL | not applicable

CRITICAL (blocking)
- [file:line] problem
  Fix: ...

IMPORTANT
- [file:line] problem
  Fix: ...

MINOR
- ...

Verification gaps: <what was not or could not be checked>
Verdict: ready | ready after fixes | not ready
Unresolved questions: ...
```

Critical findings block merge. This skill reports; it does not change code unless the user asks for fixes.

## Step 6: Fix and verify (when asked to fix)

1. Fix critical findings first, then important ones.
2. Re-run the relevant tests, type-check, lint and any manual reproduction.
3. Re-review the fixes. At most 3 fix and re-review cycles, then escalate to the user.

For reviews spanning 3 or more files, track scout, review, fix and verify as todo list items in that order.

## Verification gate

**No completion claim without fresh evidence.** Identify the command, run it in full, read the output, then make the claim.

- "Tests pass" needs output showing zero failures.
- "Compiles" needs exit code 0.
- "Bug fixed" needs the original reproduction passing.
- "Requirements met" needs the checklist from stage 1.

"Should", "probably", "seems to", or trusting a subagent's summary without reading its output are red flags.

## Receiving feedback

Pattern: read, understand, verify, evaluate, respond, implement.

- When feedback is unclear, stop and ask before changing anything.
- Verify each point against the code before acting. Reviewers can be wrong.
- No performative agreement. If a point is wrong, push back with evidence.
- Do not reverse a decision already verified by source, tests or an empirical check because of an abstract concern; state the verification briefly.
- If feedback would undo an explicit user decision, present the decision, the concern, the trade-off and the options, then ask the user.
- See `.github/copilot-kit/rules/review-audit-self-decision.md`.

## Workflow position

Follows `/kit-cook`, `/kit-fix` and `/kit-test`. Precedes `/kit-git` (commit or PR).
