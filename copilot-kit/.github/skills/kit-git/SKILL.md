---
name: kit-git
description: Stage and commit with conventional commits, push, open pull requests, merge branches, and review-then-merge PRs, with a secret scan before every commit. Use when asked to commit, push, create a PR, merge a branch or merge a PR.
---

# Git Operations

## Usage

```
/kit-git cm                              stage and commit
/kit-git cp                              stage, commit and push
/kit-git pr [to-branch] [from-branch]    open a pull request (defaults: main, current branch)
/kit-git merge [to-branch] [from-branch] merge a branch (defaults: main, current branch)
/kit-git merge-pr <PR numbers or URLs>   review, merge, then watch CI
```

No argument: ask the user which operation to run.

For anything beyond a trivial commit, delegate the operation to the `git-manager` custom agent so verbose git output stays out of the main conversation. Pass it the operation, branch names, these rules, and ask for the output format below. Keep prompts and reports terse.

## Safety rules (all operations)

- Never commit secrets, environment files, tokens, private keys, database credentials or personal data.
- Never force push to `main`, `master`, `production`, `prod` or `release/*`.
- Confirm with the user before any destructive operation: hard reset, discarding changes, deleting branches, force push anywhere.
- Push only when the user asked for it (`cp`, `pr`, or an explicit "push").
- Commit messages and PR text carry no AI attribution and no co-author lines for assistants.
- No interactive git commands (`-i` flags, editors).

## Commit workflow (`cm`, `cp`)

### 1. Stage and analyze

```bash
git add -A && git diff --cached --stat && git diff --cached --name-only
```

Nothing staged: say so and stop.

### 2. Secret scan

```bash
git diff --cached | grep -iE "(AKIA|api[_-]?key|token|password|secret|credential|private[_-]?key|mongodb://|postgres://|mysql://|redis://|-----BEGIN)"
```

Also flag staged files named `.env`, `.env.*` (except `.env.example`), `*.key`, `*.pem`, `*.p12`, `credentials.json`, `secrets.json`.

Read each match. A real secret blocks the commit: show the matching lines, unstage the file (`git reset HEAD <file>`), suggest an ignore rule or environment variables, and stop. A false positive (a variable name, a test fixture, documentation) may proceed; say why.

### 3. Split decision

Split into several commits when:

- types are mixed (feature and fix, code and docs),
- several scopes are touched (auth and payments),
- config or dependency changes are mixed with code,
- more than 10 unrelated files are staged.

One commit when the change has one type and scope and is small (about 3 files, 50 lines).

When splitting, group in this order: config, dependencies, tests, code, docs. For each group: `git reset`, `git add <files>`, commit.

### 4. Commit

```bash
git commit -m "type(scope): description"
```

Message rules:

- Types: `feat`, `fix`, `docs`, `style`, `refactor`, `test`, `chore`, `perf`, `ci`, plus the conventional type for compile-system changes.
- Under 72 characters, imperative present tense ("add", not "added"), no trailing period.
- Scope optional but recommended.
- Say what changed, not how.
- Reference a related GitHub issue in the body when one exists (`gh issue list --search "<keywords>"`).
- No plan, phase or review identifiers in the message.

Good: `feat(auth): add login validation`, `fix(api): resolve query timeout`.
Bad: `Updated files`, `Fix bug`, `feat(auth): added login using bcrypt with salt`.

### 5. Push (`cp` only, or when asked)

```bash
git push
```

No upstream: `git push -u origin HEAD`. Rejected: `git pull --rebase`, resolve, push again; never force.

Before committing work produced by `/kit-cook` or `/kit-fix`, the tests and `/kit-code-review` should already have passed. If they have not run, say so and ask the user whether to commit anyway.

## Pull requests and merges

Read `references/pr-and-merge-workflows.md` from this skill's directory for `pr`, `merge` and `merge-pr`. Key rules:

- PR content is built from the **remote** diff (`origin/<base>...origin/<head>`), never from local uncommitted state.
- Bring the base branch into the feature branch before opening a PR.
- `merge-pr` merges only after a review with no critical or important findings and green required checks.

## Output format

```
staged: N files (+X/-Y lines)
security: passed
commit: <hash> type(scope): description
pushed: yes/no
```

## Error handling

| Error | Action |
|-------|--------|
| Secrets detected | Block the commit, show files and lines |
| No changes | Exit cleanly |
| Push rejected | `git pull --rebase`, resolve, retry |
| Merge conflicts | Resolve if unambiguous; otherwise stop and show the conflict to the user |
| `gh` not authenticated | Stop and report the exact error |

## Recovery

- Undo the last unpushed commit, keep changes staged: `git reset --soft HEAD~1`
- Abort a merge: `git merge --abort`
- Discarding changes (`git checkout -- <file>`, `git reset --hard`) needs user confirmation first.

## Workflow position

Follows `/kit-code-review` and `/kit-test`. Called from the finalize step of `/kit-cook` and `/kit-fix` after the user agrees to commit.
