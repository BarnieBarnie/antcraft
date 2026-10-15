---
name: new-issue
description: Draft and file a GitHub issue (feature, bug or improvement) for the AntCraft repo. Use when the user wants to open, file, create or log an issue, bug report, feature request or improvement idea, or turn a list of ideas into issues.
argument-hint: "[short description of the feature, bug or improvement]"
---

# File a GitHub issue for AntCraft

Turn the user's idea into a well-scoped issue on `BarnieBarnie/antcraft`, grounded in the actual code, and file it with `gh` only after the user approves the draft.

## 1. Check `gh` works

Run `gh auth status`. The `gh` token is stored in the system keyring, which the Claude Code sandbox cannot reach (inside the sandbox it reports "The token in default is invalid"), so run every `gh` command with the sandbox disabled. If it is genuinely not logged in, ask the user to run `! gh auth login` and stop until they have.

## 2. Understand the request

Classify it as one of:

- **bug**: something behaves wrongly. Need: what happens, what should happen, how to reproduce.
- **feature**: new behaviour or content.
- **improvement**: existing behaviour made better (performance, UX, refactor, tests, docs).

If several ideas come in at once, split them into one issue each and handle them as a batch.

Ask only for what you cannot work out yourself. For a bug with no reproduction steps, ask for them. Do not ask about things the code answers.

## 3. Ground it in the code

Before drafting, look at the relevant code so the issue names real classes, files and milestones:

- Find where the behaviour lives (`src/main/java`), and which tests cover it (`src/test/java`, `src/gametest/java`).
- Check the README roadmap (M0 to M6) to see which milestone it belongs to.
- Check for duplicates: `gh issue list --state all --search "<keywords>"`. If one exists, show it and ask whether to comment on it instead.

Keep this short. The issue is not the place for a full design; point at the code that will change.

## 4. Draft

Title: imperative and specific, under 70 characters, no type prefix (the label carries the type). For example "Hauler drops items when colony storage is full", not "Bug: hauler issue".

Body templates (GitHub Markdown, normal prose):

**bug**
```markdown
## What happens
## Expected
## Steps to reproduce
1.
## Where
`path/to/File.java` — why it is likely involved
## Acceptance criteria
- [ ] A test reproduces the bug (unit or game test) and fails before the fix
- [ ] ...
```

**feature / improvement**
```markdown
## Goal
Why this is wanted, from a player's or developer's point of view.
## Proposal
What changes. Mention affected classes and config keys.
## Acceptance criteria
- [ ] ...
- [ ] Covered by tests (unit tests for pure logic, game tests for bot behaviour in a world)
## Notes
Milestone, open questions, related issues.
```

If the issue cannot start until another issue is done, add a line `Blocked by #N` to Notes. The `work-issues` skill reads that line to order its work; use "Relates to #N" for links that are not blocking.

Drop sections that would be empty. Acceptance criteria must be testable, since the project is test-driven (see CLAUDE.md).

Labels: one of `bug`, `enhancement` (features and improvements), plus optional area labels the repo already has. Check with `gh label list`; do not invent new labels unless the user asks.

## 5. Confirm, then file

Show the user the title, labels and body. Filing is outward-facing, so wait for an explicit go-ahead. For a batch, show all drafts and get one approval for the set.

Then write each body to a file in the scratchpad directory and run:

```sh
gh issue create --repo BarnieBarnie/antcraft --title "<title>" --label <label> --body-file <file>
```

Report the issue URLs. Do not assign people, milestones or projects unless the user asked.
