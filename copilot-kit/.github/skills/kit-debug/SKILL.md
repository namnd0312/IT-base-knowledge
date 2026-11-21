---
name: kit-debug
description: Investigate a bug, test failure, CI failure, performance problem or unexpected system behavior and prove its root cause with evidence before any fix. Use when the cause is unknown or disputed and must be established first. Produces a diagnosis, not a fix.
---

# Debug

Systematic investigation. The output is a proven root cause and a recommended fix, handed to `/kit-fix`.

## Iron laws

1. **No fixes without root cause investigation first.** Random changes waste time and add bugs.
2. **No completion claims without fresh verification evidence.** Run the command, read the output, then state the result.

## Usage

```
/kit-debug <error, failing test, CI run, or description of the behavior>
```

Use for code-level problems (failing tests, bugs, compile failures, integration problems) and system-level problems (server errors, CI pipeline failures, slow queries, latency, log analysis).

## Pick the path

| Situation | Path |
|-----------|------|
| Code bug or failing test | Four phases below |
| Error deep in a call stack, bad data of unknown origin | Four phases, with backward tracing in phase 1 |
| Server incident, multi-component failure, CI failure, slowness | System investigation in `references/investigation-techniques.md` |
| Cause already proven | Stop here and run `/kit-fix` |

Read `references/investigation-techniques.md` from this skill's directory for backward tracing, test-pollution bisection, log and CI analysis, performance diagnostics and the report template.

## The four phases

Finish each phase before the next. Track them in the todo list.

### Phase 1: Root cause investigation

1. Read the error and the whole stack trace. Do not skip warnings.
2. Reproduce it reliably and write down the exact steps. If you cannot reproduce it, gather more data; do not guess.
3. Check what changed: `git diff`, recent commits, new dependencies, config and environment.
4. In a multi-component system, add evidence at every boundary: log what enters and leaves each component and confirm environment and config propagate. Run once to see where it breaks, then analyze.
5. Trace the bad value backward through the call stack to where it originates.

If the affected code is not located yet, run `/kit-scout` first.

### Phase 2: Pattern analysis

1. Find similar code in the same codebase that works.
2. Read the reference implementation completely before comparing.
3. List every difference between working and broken, however small.
4. Identify what the working version depends on: settings, config, environment, other components.

### Phase 3: Hypothesis and test

1. State one hypothesis: "X is the root cause because Y". Be specific.
2. Test it with the smallest possible change or check, one variable at a time.
3. Confirmed: go to phase 4. Refuted: form a new hypothesis. Do not stack changes.
4. When you do not understand something, say so and ask the user. Do not pretend.

### Phase 4: Conclude

1. Write the simplest failing reproduction, automated if possible.
2. State the root cause with `file:line` evidence, why it surfaced now, and the blast radius.
3. Recommend a single fix at the source plus the validation layers that would prevent recurrence: entry validation, business-logic validation, environment guards, diagnostic logging.
4. Hand off to `/kit-fix`. If the user asked you to fix as well, apply one change, verify with fresh output, and stop after 3 failed attempts to question the design with the user.

## Delegation

- For long log collection or a multi-component investigation, delegate to the `debugger` custom agent. Give it the symptom, reproduction, files to read, reports path, and the instruction to return evidence, not a patch.
- Independent evidence gathering (different services, different log sources) may run as parallel read-only subagents. Each returns findings with `file:line` or log-line citations.
- Subagent replies end with `Status: DONE | DONE_WITH_CONCERNS | BLOCKED | NEEDS_CONTEXT`, `Summary:`, `Concerns/Blockers:`.

## Tools

- `gh` for CI runs and logs.
- `psql` or the project's database client for query plans and data checks. Read-only unless the user approves otherwise.
- The project's own test, type-check and lint commands.
- For frontend changes: open the page in a browser or run the project's browser tests, check for console errors, and compare a screenshot against the expected result.

## Red flags

If you catch yourself thinking any of these, return to phase 1:

- "Quick patch now, investigate later."
- "Just change X and see."
- "It's probably X."
- "Should work now" or "seems fixed".
- "Tests pass, so we are done."
- "One more attempt" after two have failed.

Signals from the user that you skipped the process: "is that actually happening?", "stop guessing", "are we stuck?".

## Output

Report in chat; for a substantial investigation also write a report file using the report filename pattern from `## Naming` in the session context:

- Summary: issue, impact, root cause in one or two sentences.
- Evidence chain: observation, hypothesis, test result, trace to `file:line`.
- Affected scope.
- Recommended fix and prevention.
- Unresolved questions.

## Workflow position

Follows `/kit-scout`. Precedes `/kit-fix`, or `/kit-brainstorm` when the cause points to a design problem with several possible solutions.
