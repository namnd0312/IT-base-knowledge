# Test Report Format

Concise over grammatical. Keep the report under 200 lines.

## Template

```markdown
# Test Report - <date> - <scope>

## Results
- Total: X | Passed: X | Failed: X | Skipped: X
- Duration: Xs
- Commands run: `<command>` ...

## Coverage
| Metric    | Value | Threshold | Result    |
|-----------|-------|-----------|-----------|
| Lines     | X%    | 80%       | PASS/FAIL |
| Branches  | X%    | 70%       | PASS/FAIL |
| Functions | X%    | 80%       | PASS/FAIL |

Uncovered: `<file>` - `<function or branch>`

## Failed tests
### `<path/to/test file>` - <test name>
- Error: <message>
- Stack trace: <relevant frames, truncated>
- Cause: <brief analysis, or "unknown - needs /kit-debug">
- Fix: <suggested resolution>

## UI results (if run)
- Pages tested: X
- Screenshots: <paths>
- Console errors: none | <list>
- Responsive: checked at <widths> | skipped
- Accessibility: <findings>

## Compile and static checks
- Type-check / lint: PASS/FAIL
- Production compile: PASS/FAIL | not run
- Warnings: none | <list>

## Critical issues
1. <blocking issue and its impact>

## Recommendations
1. <action, ordered by impact>

## Unresolved questions
- <open question, or "none">
```

## Guidelines

- List every failed test with its error message. Do not summarize failures away.
- Skipped tests are reported with the reason they are skipped.
- Coverage: name uncovered files and functions, not only percentages.
- Use the project's own thresholds when configured; the table shows defaults.
- Recommendations ordered: critical, high, medium, low.
- State what was not run and why. Never imply a check passed when it did not run.
- File name and location come from `## Naming` and `## Paths` in the session context.
