# Diagnosis and Prevention

## Part 1: Diagnosis protocol

Never guess a root cause. Form hypotheses explicitly and test them against evidence.

### Capture the broken state first

1. Exact error messages, copied not paraphrased.
2. The failing command and its full output.
3. Stack traces.
4. Log lines with timestamps.
5. `git status` and `git log --oneline -10`.

You will re-run the same commands after the fix and compare.

### Observe

- What is the exact message? Where does it occur (file, line, function)?
- When did it start? Use `git log` and, if needed, `git bisect`.
- Is it reproducible every time?
- What should happen, and what does happen?

### Hypothesize

For each hypothesis write:

1. The claim, stated specifically.
2. Evidence that would confirm it.
3. Evidence that would refute it.
4. The fastest way to test it.

Usual categories: regression from a recent change; data or state mismatch (bad input, stale cache, race); environment difference (dependency version, config, platform); missing validation (null, type, boundary); wrong assumption about a contract, data shape or ordering.

### Test

Check each hypothesis against the code and runtime evidence. Independent hypotheses can be checked in parallel by read-only exploration subagents, at most 3 at once. Mark each CONFIRMED, REFUTED (note why) or INCONCLUSIVE (refine or gather more).

### Trace

```
Symptom (where the error appears)
  <- immediate cause (what triggered it)
    <- contributing factor (what set up the bad state)
      <- ROOT CAUSE (the origin that must change)
```

Fix at the origin, never where the error surfaces.

### When hypotheses keep failing

After two refuted hypotheses:

- Inversion: what would you write to cause this bug on purpose?
- Scale: does it fail with 1 item, 100, 10000?
- Environment: timing, concurrency, platform, configuration.

After three failed fix attempts: stop, question whether the design is sound, and talk to the user.

### Diagnosis report

```markdown
## Diagnosis
**Issue:** <one line>
**Broken state captured:** yes/no

### Root cause
<explanation traced to origin, with file:line>

### Evidence chain
1. <observation> -> hypothesis <X>
2. <test result> -> confirmed/refuted <X>
3. <trace> -> root cause at <file:line>

### Affected scope
Files, functions, dependents.

### Recommended fix
<what to change and why it addresses the cause>

### Prevention needed
<tests and guards to add>
```

### Quick mode

Read the error, locate the file, identify the cause, skip parallel hypothesis testing. Still capture the before state.

## Part 2: Specialized inputs

| Input | First moves |
|-------|-------------|
| CI failure | `gh run list`, `gh run view <id> --log-failed`; find the first failing step; reproduce locally with the same command before changing anything |
| Test failure | Run the single failing test in isolation; check whether it passes alone but fails in the suite (shared state or ordering) |
| Type errors | Run the type checker, fix from the first error down; fix the type at its source, do not cast it away or use `any` |
| Application logs | Find the first error in time order, correlate request or trace ids across sources, then map to code |
| UI bug | Reproduce in a browser, capture console errors and a screenshot, compare against the intended design; involve `ui-ux-designer` for visual judgment |

## Part 3: Prevention gate

A fix without prevention is incomplete.

### Regression test (always, except trivial type or lint fixes)

The test must fail without the fix and pass with it. If the project has no test framework, add a runtime assertion and say so in the report.

### Layered guards (apply what is relevant, consider all)

| Layer | Apply when | Example |
|-------|-----------|---------|
| Entry validation | Bug involved external or user input | Reject invalid input at the boundary |
| Business logic validation | Bug involved data processing | Assert data is valid for the operation |
| Environment guard | Bug involved environment-sensitive operations | Refuse a dangerous operation in the wrong context |
| Diagnostic logging | Bug was hard to find | Log the context that would have revealed it |

### Type safety and error handling

- Null or undefined caused it: add strict null handling.
- Wrong type passed: add a type guard or runtime validation.
- Missing property: make the field required in the type.
- Unhandled rejection or silent failure: add explicit handling and logging.
- External dependency with no fallback path: add a timeout and a fallback.

### Checklist before declaring the fix done

- [ ] Broken state was captured before the fix
- [ ] Fix changes the root cause, not the symptom
- [ ] Same commands re-run fresh; before and after compared
- [ ] Regression test added (fails without, passes with)
- [ ] Guard layers considered, applied where relevant
- [ ] Blast radius tests pass; contracts unchanged
- [ ] Type-check, lint, compile and tests are clean

### Report lines

```
Prevention:
- Regression test: <file:line> covers <scenario>
- Guard: <file:line> <what it rejects>
Before: <exact failure>
After:  <exact passing output>
```
