---
name: kit-test
description: Run and analyze unit, integration, end-to-end and UI tests, check coverage and compile status, and produce a structured QA report. Use after implementing or fixing code, before commit or review, or when asked to verify coverage. Never hides or weakens failing tests.
---

# Test

Validate code with real test runs and report the results precisely.

## Core rule

**Never ignore a failing test.** Fix causes, not symptoms. No fake data, mocks added only to pass, commented-out tests, loosened assertions or skipped suites to get a green run.

## Usage

```
/kit-test [scope or context]      run code tests for the scope (default: recent changes)
/kit-test ui <url>                run browser-based UI checks against a URL
```

With no arguments and no obvious recent change to test, ask the user: run code tests, or run UI tests on a URL.

## Code testing workflow

Track steps in the todo list.

1. **Scope.** Identify what changed (`git status`, `git diff --stat`, the active plan's current phase) and which tests cover it. Start with the narrowest useful run; broaden when shared behavior or public contracts changed.
2. **Detect tooling.** Read the project manifest and docs for the real commands. Do not invent them.

   | Stack | Typical commands |
   |-------|------------------|
   | JS/TS | `npm test`, `npm run test:coverage`, `npx vitest run`, `npx jest`, `npx tsc --noEmit` |
   | Python | `pytest`, `pytest --cov` |
   | Go | `go vet ./...`, `go test ./...`, `go test -cover ./...` |
   | Rust | `cargo check`, `cargo test` |
   | Flutter | `flutter analyze`, `flutter test` |

3. **Static checks first.** Run type-check, analyze or lint so syntax and type errors surface before the test run.
4. **Run the tests.** Focused tests for the touched behavior, then the wider suite.
5. **Analyze failures.** For each: the error, relevant stack, likely cause, suggested fix. A failure whose cause is unclear goes to `/kit-debug`; do not guess.
6. **Coverage**, when the project has it configured. Name the specific uncovered files and functions, not only the percentage. Default thresholds when the project sets none: 80% lines and functions, 70% branches.
7. **Compile and packaging check** when the change could affect it: run the project's production compile command and note warnings.
8. **Report** using `references/test-report-format.md` from this skill's directory.

Fixing is not this skill's job unless the user asked for it. Report failures and recommend `/kit-fix`. If asked to fix, fix the cause and re-run until the suite passes for real.

## Writing missing tests

When coverage of the changed behavior is missing:

- Cover the happy path, edge cases and error paths.
- Keep tests isolated and deterministic: no order dependence, no shared mutable state, no real network unless the project's integration tests already do so.
- Clean up test data.
- Follow the project's existing test layout, helpers and naming.

## UI testing workflow

1. Prefer the project's own browser tests (Playwright, Cypress, Vitest browser mode) when they exist.
2. Otherwise drive a browser with whatever browser automation is available in the session, then verify:
   - the page loads without console errors or failed network requests,
   - key user flows and forms work,
   - layout at mobile, tablet and desktop widths,
   - basic accessibility: labels, focus order, contrast, keyboard operation.
3. Save screenshots under the reports path and reference them in the report.
4. Pages behind login need credentials or a test account from the user. Ask; never guess or bypass.
5. If no browser automation is available, say so and list the manual checks instead of claiming UI verification.

## Delegation

- For a large suite or when invoked from `/kit-cook` or `/kit-fix`, delegate the run to the `tester` custom agent with: scope, changed files, acceptance criteria, commands to run, reports path.
- Failures needing investigation go to the `debugger` custom agent.
- Subagent replies end with `Status: DONE | DONE_WITH_CONCERNS | BLOCKED | NEEDS_CONTEXT`, `Summary:`, `Concerns/Blockers:`. Read the actual output behind a DONE before repeating its claim.

## Quality standards

- All critical paths covered.
- Happy path and error scenarios both validated.
- Tests are deterministic and reproducible.
- A result is only reported from output produced in this session.

## Report output

Write the report to the reports path in `## Paths`, named with the report pattern in `## Naming` from the session context (the active plan's `reports/` folder when a plan is active). Give the user a short summary in chat: totals, failures, coverage, blocking issues, unresolved questions.

## Workflow position

Follows `/kit-cook` or `/kit-fix`. Precedes `/kit-code-review`. Rules: `.github/copilot-kit/rules/development-rules.md`.
