# Pull Request and Merge Workflows

Variables used below: `BASE` is the target branch (default `main`), `HEAD` is the source branch (default current branch).

## Pull request (`pr`)

PRs are based on remote branches. A local diff includes unpushed and uncommitted work, so it describes the wrong change.

### 1. Sync and analyze

Bring the base branch into the feature branch first (`git fetch origin && git merge origin/$BASE`), resolve conflicts, then:

```bash
git fetch origin
git push -u origin HEAD
BASE=main
HEAD=$(git rev-parse --abbrev-ref HEAD)
git log origin/$BASE...origin/$HEAD --oneline
git diff origin/$BASE...origin/$HEAD --stat
```

Do not use `git diff main...HEAD`, `git diff --cached` or `git status` to describe the PR.

### 2. Write the content

- Title: conventional commit format, under 72 characters, no version numbers.
- Body: summary bullets, then a test plan checklist. Describe every commit in the range, not only the last one.
- Link related issues.
- No secrets, local absolute paths, or AI attribution.

### 3. Create

```bash
gh pr create --base $BASE --head $HEAD --title "type(scope): description" --body "$(cat <<'EOF'
## Summary
- ...

## Test plan
- [ ] ...
EOF
)"
```

Report the PR URL.

| Error | Action |
|-------|--------|
| Branch not on remote | `git push -u origin HEAD`, retry |
| Empty diff | Warn "No changes for PR" and stop |
| Push rejected | `git pull --rebase`, resolve, push |
| PR already exists for the branch | Report its URL; update it instead of creating another |

## Branch merge (`merge`)

Merge from the remote branch so only committed and pushed work is included.

1. Pre-merge checks:
   ```bash
   git fetch origin
   git merge --no-commit --no-ff origin/$HEAD   # conflict probe on the target branch
   git merge --abort
   ```
2. Sync the target:
   ```bash
   git checkout $BASE
   git pull origin $BASE
   ```
3. Merge:
   ```bash
   git merge origin/$HEAD --no-ff -m "merge: $HEAD into $BASE"
   ```
4. Conflicts: resolve when the intent is unambiguous, then `git add` the files and commit. Otherwise stop and show the conflict to the user.
5. Push: `git push origin $BASE`. If the target is protected, do not push directly; open a PR instead.

| Error | Action |
|-------|--------|
| Branch not found | Verify the name and that it was pushed |
| Push rejected | `git pull --rebase`, retry; never force |

## Review and merge PRs (`merge-pr`)

Accepts one or more PR numbers or URLs. Process them in the order given.

### 1. Inspect

```bash
gh auth status
gh repo view --json nameWithOwner,defaultBranchRef
gh pr view <pr> --json number,url,title,baseRefName,headRefName,mergeStateStatus,statusCheckRollup
```

If any reference is invalid, closed, from another repository or not visible, stop before reviewing or merging anything.

### 2. Review

Run `/kit-code-review #<pr>` for each PR. A PR is merge-ready only when all hold:

- no critical or important findings remain,
- no merge conflicts,
- required checks are finished and green.

If a PR is not ready, stop the whole set and report: the PR, remaining findings or blockers, the exact failed checks or merge state, and the recommended next action. Fixing the PR branch is a separate task the user must ask for.

### 3. Confirm and merge

Show the review verdicts and ask the user to confirm the merge. Then, one PR at a time:

1. Re-read `mergeStateStatus` and `statusCheckRollup`.
2. Choose the merge method from the repository's convention (`--merge`, `--squash` or `--rebase`) before running the command, so `gh` does not prompt.
3. Merge:
   ```bash
   gh pr merge <pr> --delete-branch --squash          # checks already green
   gh pr merge <pr> --auto --delete-branch --squash   # required checks still pending
   ```

Never force push. Never push directly to a protected branch. Respect branch protection rules.

### 4. Watch target-branch CI

```bash
gh pr view <pr> --json baseRefName,mergeCommit
gh run list --branch <base> --commit <merge-sha> --json databaseId,status,conclusion,name,url
gh run watch <run-id> --exit-status
```

If no run appears immediately, poll briefly before concluding there is none.

### 5. If CI fails after the merge

1. Read the failing job: `gh run view <run-id> --log-failed`.
2. Transient infrastructure failure: re-run failed jobs once with `gh run rerun <run-id> --failed`.
3. Deterministic failure the repo can fix: run `/kit-fix` with the workflow name, run id, job, command and exact error. Ship the fix through its own PR and review, then watch CI again.
4. Stop when CI is green, when an external blocker remains, or when the same blocker survives 3 fix attempts. Report the state to the user.

### Output

```
reviewed: PR #123 ready, CI green
merged: PR #123 into main (merge SHA abc123)
CI: green for abc123
```

When blocked:

```
stopped: PR #123 not merge-ready
reason: <finding, failed check or merge blocker>
suggestion: <next action>
```

## Branch naming

`<type>/<short-kebab-description>`, for example `feat/login-validation`, `fix/query-timeout`. Use the repository's existing convention when it has one. Create feature branches from an up-to-date base; delete them after merge.
