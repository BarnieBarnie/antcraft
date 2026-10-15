---
name: work-issues
description: Act as the manager that works through open AntCraft GitHub issues with parallel issue-worker subagents, each in its own git worktree, then reviews, merges and pushes their branches in dependency order and starts newly unblocked issues. Use when the user asks to work on, implement, or burn down the open issues, or names several issues to have done.
argument-hint: "[issue numbers, or empty for all open issues] [--parallel N]"
---

# Work through open issues

You are the manager. You do not implement issues yourself. You plan, dispatch `issue-worker` subagents, review and merge their work, and keep the queue moving. The user stays in this session, so keep them informed with short status lines.

`gh` and `./gradlew` both need the sandbox disabled (keyring token and `~/.gradle`).

## 1. Preflight

- The main checkout must be on `main` and clean (`git status --short`; untracked files are fine if they don't overlap the repo's sources). If tracked files are modified, stop and ask the user whether to commit, stash or wait. Never stash or discard their work yourself.
- `git pull --ff-only` so `main` is current.
- `gh auth status`.

## 2. Gather

`gh issue list --repo BarnieBarnie/antcraft --state open --json number,title,labels,body --limit 100`

If the user named issues, use only those. Otherwise use all open issues.

## 3. Plan

Work out an order:

- **Hard dependencies**: a body that says "Blocked by #N", "Depends on #N", "Requires #N", or that clearly needs another open issue's work first (for example, "the iron tier depends on smelting" means it waits for the smelting issue). Only open issues in the set count; a closed blocker is already met.
- **File overlap**: issues likely to change the same files (judge from the files the bodies name) run one after another, not in parallel. Parallel branches on the same file usually end in merge conflicts.
- Within those limits, bugs go first, then lower issue numbers.
- Parallel limit: 3 workers by default (`--parallel N` overrides). Each runs a full Gradle build with game tests, which is heavy.

Show the user the plan as waves: what starts now, what waits and why. Also ask whether to push `main` to `origin` after each merge. Pushing closes the issue through `Fixes #N` and runs CI. Wait for their go-ahead before dispatching.

## 4. Dispatch

For each issue that is ready (all blockers merged, no file overlap with a running worker), spawn an agent:

- `subagent_type: "issue-worker"`, `isolation: "worktree"`, launched in the background. Send all ready workers in one message so they run in parallel.
- Prompt: the issue number, title and full body; the branch name `issue-<N>-<short-slug>`; and any context from issues merged earlier in this run that it builds on (for example, "#2 added smelting steps to `TaskPlanner`, merged as abc1234").

Worktrees are created from the current `main`, so a dependent issue picks up its blocker's code once the blocker is merged. Never start a dependent issue before its blocker is merged.

## 5. Review and merge

When a worker reports back:

1. **Status is not `done`**: do not merge. Note the reason, tell the user, and remove the issue from the queue along with anything blocked on it. If the reason is something the user can answer, ask them, then continue the same worker with `SendMessage`.
2. **Review**: in the worker's worktree, look at `git log main..<branch>` and `git diff main...<branch>`. Check that the change matches the issue, has tests for the acceptance criteria, follows `CLAUDE.md` (TDD, no weakened tests), and stays in scope. If something is wrong, send it back to the worker with `SendMessage` and specific feedback.
3. **Bring it up to date**: if `main` has moved since the worker started, rebase the branch onto `main` inside its worktree. On a conflict, resolve it there if it is trivial; otherwise abort the rebase and send it back to the worker. After any rebase, run `./gradlew build` in the worktree again.
4. **Merge**: one merge at a time, so `main` stays green. In the main checkout: `git merge --ff-only <branch>` to keep history linear. Push if the user agreed.
5. **Clean up**: `git worktree remove <path>` and `git branch -d <branch>`.
6. **Advance**: tell the user in one line (issue, commit, pushed or not), then dispatch every issue that just became ready.

If two workers finish around the same time, merge them one after the other, rebasing the second onto the first.

## 6. Finish

When the queue is empty, or only blocked or failed issues are left, give the user a summary: merged issues with commits, issues left open and why, and any follow-ups the workers suggested. Offer to file those follow-ups with the `new-issue` skill.
