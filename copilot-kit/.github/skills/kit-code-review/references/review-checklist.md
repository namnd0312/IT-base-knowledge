# Review Checklist

Two passes. Pass 1 findings block. Pass 2 findings inform. Cite `file:line`, one line for the problem, one for the fix. Skip what is fine. Read the whole diff before commenting.

## Pass 1 - Critical (blocking)

### Correctness and regressions
- Behavior does not match the stated requirement or acceptance criteria
- A caller or dependent of changed code now receives a different shape, type or error
- Public contract changed without being called out: signatures, exported types, API responses, schemas, env vars, config keys
- Error path swallowed, or failure reported as success
- Off-by-one, null or empty input, or boundary case that crashes or corrupts data

### Injection and data safety
- String interpolation in database queries; use parameterized queries
- Unsanitized user input written to storage or rendered as HTML
- Raw HTML output from user-controlled data
- Shell commands assembled by string concatenation; use argument arrays
- Path traversal through user input in file operations

### Race conditions and concurrency
- Read-check-write without an atomic operation
- Find-or-create without a unique constraint
- Status transition without an atomic conditional update
- Shared mutable state without synchronization

### Security boundaries and access control
- New endpoint or route without authentication
- Authenticated but not authorized: a user can read or modify another user's data
- Admin-only operation reachable by regular users
- Secrets in logs, error responses, client-side code or committed files
- Model or external-service output written to storage or used in queries without validation
- Token or signature comparison that is not constant-time

### Fake or incomplete work
- Stubs, placeholder returns, hard-coded sample data presented as real behavior
- Tests that assert nothing, are skipped, or mock the unit under test
- Assertions loosened or tests deleted to make a run pass
- TODO left where the requirement needed an implementation

## Pass 2 - Informational (non-blocking)

### Conditional side effects
- A branch sets status but forgets the associated data or notification
- A log line claims an action that was conditionally skipped

### Coupling and constants
- Same bare literal used in several files; should be a named constant
- Error message strings used as filters elsewhere

### Dead code and consistency
- Variables assigned but never read; unused imports
- Comments describing old behavior
- New code that duplicates an existing helper
- Naming, structure or error handling that departs from adjacent code without reason

### Scope discipline
- Changes unrelated to the request (drive-by refactors, reformatting)
- New abstraction, option or config with a single caller and no stated need
- File grown past the project's size guideline where a real boundary exists to split on

### Test gaps
- No negative-path tests (errors, validation failures)
- Asserts on status or type but not on side effects
- No integration test for security enforcement (auth, rate limiting, access control)

### Type coercion at boundaries
- Values crossing language or system boundaries where the type can change (string versus number)
- Hash or digest inputs not normalized before serialization

### Performance
- Nested lookups over collections inside loops; use a map
- List endpoints without pagination; queries without a limit
- N+1 queries: associations loaded inside a loop

## Do not flag

- Style and formatting a linter handles
- "Consider X instead of Y" when Y works
- Redundancy that aids readability
- Requests for comments explaining thresholds
- "This assertion could be tighter" when it already covers the behavior
- Harmless no-ops
- Anything already addressed elsewhere in the same diff

## Severity guide

| Severity | Meaning | Action |
|----------|---------|--------|
| Critical | Wrong behavior, data loss, security hole, broken contract, fake work | Blocks merge; fix and re-verify |
| Important | Likely bug under realistic conditions, missing test for risky path, scope drift | Fix before proceeding unless the user accepts |
| Minor | Maintainability and consistency | Author's call |

## Codebase scan order

For `codebase` mode, with no diff to anchor on:

1. Read the project docs: codebase summary, code standards, architecture.
2. Rank modules by risk: auth, payments, data access, external input handling, then the rest.
3. Apply pass 1 to the high-risk modules in full; sample the rest.
4. Report per module, then a cross-cutting section for repeated patterns.
