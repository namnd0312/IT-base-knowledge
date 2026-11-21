---
name: kit-scout
description: Quickly map the parts of a codebase relevant to a task using targeted searches and, for large repos, parallel read-only subagents. Use to find files, understand where functionality lives, gather context before planning, debugging, fixing or reviewing, or when asked to locate or search for code.
---

# Scout

Fast, token-efficient discovery of the files and patterns a task depends on. Read-only: scouting never edits code.

## Usage

```
/kit-scout <what to find or the task needing context>
```

## Workflow

### 1. Analyze the target

- Extract search targets from the request: feature names, symbols, routes, file types, error strings.
- Run a few broad file-name and content searches to estimate scale: how many files match and in which directories.
- Read `README.md` and the docs' codebase summary if present; they often answer the question outright.

### 2. Decide the scale

| Situation | Approach |
|-----------|----------|
| Small repo, or matches concentrated in 1-2 directories | Search and read directly. No subagents. |
| Matches spread over 3-5 areas | 2-3 parallel read-only subagents |
| Large repo or monorepo, many areas | Up to 6 parallel read-only subagents |

Do not spawn subagents for something a handful of searches can answer.

### 3. Divide

Split the codebase into non-overlapping segments, one per subagent: by directory (`src/api`, `src/ui`, `tests`, `config`), by layer, or by package. Every relevant area is covered once. When using 3 or more subagents, add one todo list entry per segment.

### 4. Delegate in parallel

Start all subagents together using a read-only exploration agent (the built-in explore agent, or the `researcher` custom agent). Each gets a self-contained prompt:

```
Scout <directories> for files related to: <target>.
Read-only. Use file-name and content search; read only the parts of files you need.
Return:
## Found files
- `path/file.ext` - what it does and why it matters to the target
## Patterns
- conventions, entry points, contracts observed
## Gaps
- what you expected but did not find
Stop after about 3 minutes of work and report what you have.
End with: Status: DONE | DONE_WITH_CONCERNS | BLOCKED | NEEDS_CONTEXT, Summary:, Concerns/Blockers:
```

Give exact directories. Do not pass conversation history. Each subagent has a limited context window, so keep scopes narrow.

### 5. Collect

- Do not wait indefinitely: skip a subagent that does not return, and note the uncovered segment in the report. Do not restart it.
- Merge findings, remove duplicates, and verify anything surprising by opening the file yourself.
- When reading large files, check line counts first and read in chunks of about 500 lines.

Always skip dependency folders, generated output, caches, version-control metadata, and secret or environment files.

## What to capture for a task

When scouting ahead of planning, implementing or fixing, the report must answer:

1. Project type, language and framework (from the manifest files).
2. Files and modules relevant to the task.
3. Existing patterns and conventions for similar features.
4. Related docs and in-flight plans in the plans directory.
5. Public APIs, schemas and contracts the task could affect.
6. Tests covering the area.

For an edge-case scout ahead of review, add: callers and dependents of changed code, data flows, error paths, boundary conditions.

## Report format

```markdown
# Scout Report: <target>

## Relevant files
- `path/to/file.ts` - brief description

## Patterns and conventions
- ...

## Contracts and touchpoints
- ...

## Unresolved questions
- gaps, segments not covered
```

Reply in chat with the report. Write it to a file only when another step needs it (for example planning): use the reports path from `## Paths` and the report filename pattern from `## Naming` in the session context.

## Workflow position

Precedes `/kit-plan`, `/kit-cook`, `/kit-debug`, `/kit-fix`, `/kit-brainstorm` and `/kit-code-review`. Delegation rules: `.github/copilot-kit/rules/orchestration-protocol.md`.
