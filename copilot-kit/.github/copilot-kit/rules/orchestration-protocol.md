# Orchestration Protocol

Use this file only when delegating to custom agents or coordinating parallel work.

## Delegation Context

Every subagent prompt should include:

- task
- files to read
- files it may modify
- acceptance criteria
- constraints
- project root path
- reports path (from the injected `## Paths` block)

## Context Isolation

- Do not pass the full conversation history.
- Summarize only the decisions needed for the subtask.
- Give exact file paths instead of "look around the repo" unless scouting is the task.
- Keep coordination, merge decisions, and user approvals in the main session.

## Parallel Work

Use parallel subagents only when file ownership is clear and integration points are known. Avoid parallel edits to the same file, generated artifact, database migration sequence, or shared config.

## Status Protocol

Subagents end with:

```text
Status: DONE | DONE_WITH_CONCERNS | BLOCKED | NEEDS_CONTEXT
Summary: one or two sentences
Concerns/Blockers: optional
```

Handle `BLOCKED` and `NEEDS_CONTEXT` by changing context, scope, or approach. Do not retry the same failing prompt repeatedly.
