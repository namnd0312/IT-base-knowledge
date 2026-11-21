---
name: kit-docs
description: Create, update or summarize project documentation in the docs directory by analyzing the codebase. Use for `init` (first-time docs), `update` (refresh docs after changes) or `summarize` (refresh the codebase summary), or when asked to document the project. Never edits source code.
---

# Docs

Analyze the codebase and keep project documentation accurate. This skill writes documentation only; it does not implement or change code.

## Usage

```
/kit-docs init                     analyze the codebase and create the initial docs
/kit-docs update [extra requests]  analyze changes and update existing docs
/kit-docs summarize [topics]       refresh the codebase summary only
```

No argument or an unclear one: ask the user which operation to run. Do not default to `init`.

## Documentation set

Docs live in the docs directory named in `## Paths` of the session context (normally `docs/`), which is the source of truth:

```
docs/
  project-overview-pdr.md     overview and product requirements
  codebase-summary.md         structure, modules, entry points
  code-standards.md           conventions, structure, error handling
  system-architecture.md      components, data flow, integrations
  project-roadmap.md          phases, milestones, progress
  deployment-guide.md         optional
  design-guidelines.md        optional
```

`README.md` at the repo root stays under 300 lines.

## init

1. **Scout.** Measure the codebase: files and lines per directory, targeting directories that actually exist. Skip dependency folders, generated output, caches, version-control metadata, tests, and secret or environment files. Run `/kit-scout` across the remaining areas and merge the findings into one context summary.
2. **Write.** Delegate to the `docs-manager` custom agent with the merged summary, the docs directory, and the file list above. It creates the docs and updates `README.md`. Do not pause for user input between steps 1 and 2.
3. **Size check.** Run `wc -l` on the docs. For any file over 800 lines, report it and ask the user: split it now, or accept as is.
4. **Accuracy check** (below).

## update

1. **Scout** as in `init`, focused on what changed since the docs were last touched (`git log` on the docs directory gives the date; `git diff --stat` from that point shows the changes).
2. **Read existing docs.** With 1-3 doc files, let `docs-manager` read them directly. With 4 or more, delegate reading to 2-5 parallel read-only subagents, larger files getting their own, each returning: purpose, key sections, areas that look stale. You must start these readers yourself; do not expect `docs-manager` to start them.
3. **Write.** Delegate to `docs-manager` with the scout summary, the doc readings, and any extra requests from the user. It updates only what the changes require.
4. **Size check** and **accuracy check**.

## summarize

Update `codebase-summary.md` only, and reply with a short summary.

- Focus on the topics the user named; default is everything.
- Do not rescan the whole codebase unless the user asks. Use existing docs, the manifest files and recent changes; scout only the areas that changed.

## Accuracy check

Documentation must describe the code as it is. After writing:

1. Every file path, command, function name, config key and environment variable mentioned in the docs exists in the repo. Search for each; fix or remove what does not.
2. Internal links resolve.
3. Dates, version numbers and status claims match reality.
4. Report anything you could not verify as an unresolved question. Never fill a gap with a plausible guess.

## When docs should change (called from other workflows)

Update docs only when a change affects user-visible behavior, setup, commands, architecture, security posture, public contracts, or decisions future maintainers need. Do not add changelog noise for purely internal edits unless the repo already requires it. Read the existing document before editing it. Details: `.github/copilot-kit/rules/documentation-management.md`.

## Writing rules

- Concise, factual, scannable. Describe what exists, not aspirations.
- Diagrams in `system-architecture.md` use plain Mermaid or ASCII; keep labels short and arrows unambiguous.
- No secrets, tokens, credentials or personal data in docs.
- Do not put plan or review identifiers in docs; explain the behavior or decision directly.

## Delegation

Prompts to `docs-manager` include: task, files to read, files it may modify (docs directory and `README.md` only), acceptance criteria, constraints, work context path, reports path. Its reply ends with `Status: DONE | DONE_WITH_CONCERNS | BLOCKED | NEEDS_CONTEXT`, `Summary:`, `Concerns/Blockers:`.

## Output

Report which files were created or changed with line counts, what was verified, and unresolved questions.

## Workflow position

Runs standalone, or at the finalize step of `/kit-cook` and `/kit-fix`. Uses `/kit-scout` for discovery.
