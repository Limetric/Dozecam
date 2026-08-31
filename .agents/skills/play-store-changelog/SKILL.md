---
name: play-store-changelog
description: Draft an English Google Play “What’s new” changelog for Dozecam by comparing the current committed HEAD with the latest published release or a release tag supplied by the user, collect user edits and explicit approval, then save the approved Play Store text to a temporary .txt file. Use when the user asks for Play Store release notes, an Android changelog, “What’s new” copy, or invokes this skill by name.
---

# Play Store Changelog

Produce short, user-facing release notes from the actual release diff. Treat drafting, review, and saving as separate phases; do not create the temporary file until the user approves the text.

## 1. Resolve the comparison

1. Use the release tag supplied by the user as the baseline. When none is supplied, resolve the latest published GitHub release with `gh release view --json tagName,publishedAt` and use its `tagName`.
2. Fetch tags when needed, then verify the baseline resolves to a commit and is an ancestor of `HEAD`. Stop and explain the problem if it is missing or the history has diverged; do not guess another release.
3. Use the committed `HEAD` as the target and record its short SHA. If the working tree is dirty, tell the user that uncommitted changes are excluded unless they explicitly request otherwise.
4. Compare `<baseline>..HEAD`. If the range contains no commits, report that there are no committed changes to describe and do not create a file.

## 2. Find important user-facing changes

Inspect the commit list and diff summary first:

```sh
git log --reverse --pretty='%h %s' <baseline>..HEAD
git diff --stat <baseline>..HEAD
```

Inspect ambiguous commits or relevant diffs until their user-visible effect is clear. Follow the repository's agent instructions when present; use CodeGraph for structural questions such as definitions, call paths, or impact when it is available.

Keep only changes Dozecam users can notice, such as:

- New or improved app capabilities and workflows.
- Fixed incorrect behavior, crashes, hangs, data loss risks, or frustrating interactions.
- Meaningful performance, reliability, accessibility, or visual improvements.

Exclude internal refactors, tests, CI/build work, documentation, dependency updates, developer tooling, and implementation-only hardening unless they produce a concrete user-visible benefit. Consolidate related commits and avoid overstating a fix beyond the evidence in the diff.

## 3. Draft Play Store text

Write 1–5 bullets, ordered by user importance. Use concise, natural `en-US` language and describe outcomes rather than files, frameworks, commit hashes, or implementation details. Do not add a heading, version number, Markdown emphasis, links, promises, or filler.

Format the draft exactly as a Google Play locale block:

```text
<en-US>
• User-facing change
• Another important improvement
</en-US>
```

Keep the text inside the locale tags at or below 500 Unicode characters, including bullets and line breaks. Prefer comfortably below the limit.

## 4. Get approval

Show the complete draft in a fenced `text` block. State the resolved comparison (`<baseline>..HEAD`) and ask: “Is this okay, or what should I change?” Then stop and wait for the user's answer.

Apply requested edits, recheck accuracy and the 500-character limit, show the full revised draft, and ask again. Repeat until the user explicitly approves. Do not interpret silence or an unrelated reply as approval.

## 5. Save the approved changelog

After explicit approval, write the approved locale block, and nothing else, to:

```text
/tmp/dozecam-play-store-changelog-<short-head-sha>.txt
```

End the file with one newline. Read it back to verify it exactly matches the approved draft. Report the comparison range and absolute temporary-file path. Do not commit, push, create a release, or upload to Google Play unless the user separately asks.
