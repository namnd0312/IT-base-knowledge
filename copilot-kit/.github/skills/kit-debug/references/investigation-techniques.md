# Investigation Techniques

## Backward root-cause tracing

Use when the error is deep in a call stack or bad data has an unknown origin.

1. Start at the line that fails. What value is wrong?
2. Who passed that value? Go one caller up.
3. Repeat until you reach the place where the value was first wrong: that is the origin.
4. Fix at the origin. Then consider adding validation at the layers the bad value passed through.

When you cannot trace by reading, instrument: log the value and a stack trace just before the failing operation, run once, read the output. In tests, write to stderr so the runner does not swallow it. Remove the instrumentation afterwards unless it has lasting diagnostic value.

Rule: never fix only where the error appears.

## Test pollution bisection

Use when a test passes alone but fails in the suite, or leaves stray files or state behind.

1. Confirm: run the failing test alone, then the full suite.
2. List test files in execution order.
3. Run the first half plus the victim test. If it fails, the polluter is in that half; otherwise in the other half.
4. Halve again until one file remains.
5. In that file, find the shared state it leaves behind: globals, singletons, environment variables, files, database rows, fake timers, module caches.
6. Fix by cleaning up in that test's teardown, not by reordering tests.

## Defense in depth

After the root cause is known, make the bug structurally hard to reintroduce. Consider each layer, apply the relevant ones:

| Layer | Purpose |
|-------|---------|
| Entry validation | Reject invalid input at the API or function boundary |
| Business logic validation | Assert the data makes sense for this operation |
| Environment guards | Refuse dangerous operations in the wrong context (for example destructive actions outside a temp directory during tests) |
| Diagnostic instrumentation | Capture the context needed to diagnose a recurrence |

## System investigation (incidents, multi-component failures)

1. **Initial assessment**: symptoms, scope, severity, when it started, what changed recently (deploys, config, dependencies, traffic).
2. **Data collection**: logs from each layer, CI output, metrics, database state. Collect before theorizing.
3. **Analysis**: build a timeline; correlate events across sources by timestamp and request or trace id; find the first anomaly, not the loudest one.
4. **Root cause identification**: trace from the first anomaly to its trigger; confirm with a reproduction or with evidence that rules out alternatives.
5. **Solution development**: immediate mitigation, permanent fix, and monitoring or guard that would catch it earlier next time.

## Log and CI analysis

GitHub Actions:

```bash
gh run list --limit 10
gh run view <run-id>
gh run view <run-id> --log-failed
gh run view <run-id> --job <job-id> --log
```

- Find the first failing step; later failures are often consequences.
- Compare with the last successful run: commit range, dependency lockfile, runner image, environment variables, cache state.
- Reproduce locally with the same command and versions before changing code.
- Distinguish a real failure from a flaky one by checking history for the same step, not by re-running until green.

Application logs:

- Filter by time window around the first report, then by error level.
- Follow one request or trace id end to end.
- Note the last successful log line before the failure: the defect is usually between that line and the error.
- Never paste secrets or personal data from logs into reports.

## Performance diagnostics

1. Measure before changing anything: establish a baseline number and the command that produces it.
2. Locate the bottleneck with a profiler, timing logs at boundaries, or the database's query plan (`EXPLAIN ANALYZE` in PostgreSQL).
3. Common causes: N+1 queries, missing index, unbounded result sets, sequential calls that could be batched, repeated computation in a loop, lock contention, memory growth from retained references.
4. Change one thing, re-measure with the same command, keep or revert.
5. Report baseline, change, and new measurement.

## Verification before any claim

| Claim | Evidence required |
|-------|-------------------|
| Tests pass | Fresh test output showing zero failures |
| Compiles | Fresh command output with exit code 0 |
| Bug fixed | The original reproduction now passes |
| Regression test works | It fails with the fix reverted and passes with it |
| Subagent finished the task | You inspected the diff and re-ran the check yourself |

Words like "should", "probably" and "seems to" mean the check has not been run.

## Diagnostic report template

```markdown
# Diagnostic Report: <issue>

## Summary
- Issue: <one line>
- Impact: <who or what is affected>
- Root cause: <one or two sentences>
- State: diagnosed | mitigated | unresolved

## Technical analysis
- Timeline of events
- Evidence chain: observation -> hypothesis -> test -> result
- Root cause location: <file:line>
- Why it surfaced now

## Recommendations
1. Immediate fix
2. Prevention: tests, guards, monitoring

## Evidence
- Commands run and key output
- Relevant log excerpts (redacted)

## Unresolved questions
- ...
```

Save reports under the reports path from the session context `## Paths`, named with the report pattern in `## Naming`.
