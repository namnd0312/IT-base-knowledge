# Plan File Templates

Use these structures for every plan. Keep them short; put detail where an implementer needs it.

## plan.md (under 80 lines)

````markdown
---
title: "<Plan title>"
status: pending            # pending | in-progress | completed | cancelled
created: <YYYY-MM-DD>
mode: fast                 # fast | hard | deep | parallel | two
blockedBy: []              # plan directory names this plan waits on
blocks: []                 # plan directory names waiting on this plan
---

# <Plan title>

## Goal
<One or two sentences: what will exist when this is done.>

## Scope
- In: ...
- Not in scope: ...

## Phases
| # | Phase | Status | Depends on | File |
|---|-------|--------|------------|------|
| 1 | <name> | pending | - | [phase-01-<name>.md](phase-01-<name>.md) |
| 2 | <name> | pending | 1 | [phase-02-<name>.md](phase-02-<name>.md) |

## Key decisions
- <decision> - <reason>

## Acceptance criteria
- [ ] ...

## Risks
- <risk> - <mitigation>

## Unresolved questions
- ...
````

Parallel mode adds two sections to `plan.md`:

- **Execution strategy**: which phases run concurrently and which must wait.
- **File ownership matrix**: every touched file listed under exactly one phase.

## phase-NN-<name>.md

````markdown
---
phase: <N>
title: "<Phase name>"
status: pending       # pending | in-progress | completed
priority: P2          # P1 | P2 | P3
dependencies: []      # phase numbers this phase waits on
---

# Phase <N>: <Name>

## Context
- Links to research or scout reports, related docs, related code.

## Overview
<One or two sentences describing what this phase delivers.>

## Requirements
- Functional: ...
- Non-functional: ...

## Architecture
<Design, data flow, component interactions. Pseudocode only where it removes ambiguity.>

## Related code files
- Create: `path/...`
- Modify: `path/...`
- Delete: `path/...`

## Implementation steps
1. ...
2. ...

## Todo
- [ ] ...

## Success criteria
- [ ] <observable, checkable outcome>

## Tests and validation
- <command or scenario that proves the phase works>

## Risks and rollback
- <risk> - <mitigation or how to back out>
````

## Tests-first phases (`--tdd`)

Replace "Implementation steps" with four ordered blocks:

1. **Tests before**: write tests that lock in current behavior of the code about to change.
2. **Seams**: add shared infrastructure or seams the change needs.
3. **Change**: refactor or add the new code those tests protect.
4. **Regression gate**: the exact compile or type-check command and test command that must pass. Add tests for behavior introduced in this phase.

## Deep mode additions

Each phase file also carries:

- a file inventory table (path, action, reason),
- a test scenario matrix (scenario, existing coverage, gap),
- a checklist of functions or interfaces that need test protection,
- the dependency edges this phase touches.

## Writing rules

- Name real paths, symbols and commands. Verify them against the repository; mark anything unchecked `[UNVERIFIED]`.
- One phase should be finishable and verifiable on its own.
- Prefer fewer phases. Merge phases that cannot be verified separately.
- State what is out of scope so the implementer does not drift.
- Do not paste large code blocks; describe the change and the contract.
- Reports produced while planning go in the plan's `reports/` folder using the report filename pattern from the session context `## Naming`. Use descriptive names, never `report.md` or `notes.md`.
