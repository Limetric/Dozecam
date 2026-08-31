---
name: create-github-release
description: Prepare, preview, create, or update a Dozecam GitHub release and include approved English Google Play “What’s new” copy in its body. Use when the user asks an agent to create, publish, draft, edit, or add Play Store notes to a GitHub release for this repository.
---

# Create GitHub Release

Create a SemVer GitHub release from the current committed branch and include a
copyable Play Store locale block. Keep release preparation read-only until the
user approves the exact version, target, and body.

## 1. Resolve the release

1. Follow the repository's agent instructions when present. Stay on the currently
   checked-out branch.
2. Decide whether the request creates a release or updates an explicitly named
   existing release. Inspect the branch, committed `HEAD`, working-tree status,
   remotes, releases, and tags needed for that mode.
3. Use the version supplied by the user. Accept an optional leading `v`, but use:
   - release name: `MAJOR.MINOR.PATCH`
   - tag: `vMAJOR.MINOR.PATCH`
4. For a new release, treat committed `HEAD` as the target and the latest
   published release as the baseline. If no version was supplied, inspect that
   range, recommend the appropriate SemVer increment, and include it in the
   approval preview. Never publish a guessed version without explicit approval.
   Report dirty working-tree changes as excluded. Determine whether a remote
   branch contains `HEAD`; if none does, disclose in the approval preview that
   the current branch must be pushed.
5. For an update, require an explicitly named existing release. Treat its tag's
   commit as the target and the published release immediately before it as the
   baseline. Read its current body and preserve all non-Play-Store content unless
   the user requested other edits.
6. Verify the baseline and target commits exist and the baseline is an ancestor
   of the target. For creation, verify the proposed tag and release do not
   already exist. For an update, verify the requested release does exist.

Stop on a diverged history, invalid SemVer, tag/release conflict, or empty
release range instead of choosing a different target silently. If the range has
commits but no user-facing change that can support truthful Play Store copy,
report that and stop unless the user supplies accurate copy.

## 2. Draft both kinds of notes

Read `../play-store-changelog/SKILL.md` completely. Apply its change-selection,
accuracy, formatting, and 500-character rules to draft the Play Store locale
block, substituting the resolved target commit for `HEAD` when updating an
existing release. During this combined workflow, use the single approval preview
in section 3 instead of the standalone skill's separate approval and save
phases.

For a new release, draft the main GitHub release notes from the same
`<baseline>..<target>` range:

- Lead with meaningful user-facing changes and fixes.
- Use Markdown headings and concise prose appropriate to the size of the release.
- Include implementation details only when they help users understand behavior.
- End the main notes with a `Full Changelog` comparison link.
- Do not repeat the Play Store bullets elsewhere merely to fill space.

For an update, start from the release's current body. Change its main notes only
when the user requested those edits.

Save the main GitHub notes or existing release body and the locale block in
separate temporary files. Combine them with:

```sh
python3 .agents/skills/create-github-release/scripts/upsert_play_store_notes.py \
  --body <github-notes-file> \
  --play-store-notes <locale-block-file> \
  --output <complete-release-body-file>
```

The script appends or replaces one marked `## Play Store release notes` section
containing the locale block in a fenced `text` block. Always read the complete
output back before requesting approval.

## 3. Get explicit approval

Show all of the following together:

- release name and tag;
- current branch for creation, and the full target commit SHA;
- comparison range;
- whether creating or updating, and whether it will be published or remain a
  draft;
- whether the current branch must be pushed for creation;
- the complete raw Markdown body, including the marked Play Store section.
  Surround it with a four-backtick fence labeled `markdown` so its inner
  triple-backtick `text` fence remains intact.

Ask whether to create or update the release with exactly that content, then
stop. Apply requested edits, regenerate the combined body, recheck the Play
Store limit and factual accuracy, and show the complete preview again. Do not
interpret a prior request to prepare a release, silence, or an unrelated reply
as approval of the final content.

## 4. Create or update the release

After approval:

1. Recheck that the target ref and proposed tag/release state have not changed
   since the preview. For an update, also recheck that the remote release body
   and state still match the preview source. If anything changed, regenerate the
   preview and ask again.
2. Push the current branch only if the approved preview said it was required.
   Do not create or switch branches.
3. Create a published release by default:

   ```sh
   gh release create <tag> \
     --title <release-name> \
     --target <full-head-sha> \
     --notes-file <complete-release-body-file>
   ```

   Add `--draft` only when the user requested and approved a draft release.
4. For an explicitly requested update, use:

   ```sh
   gh release edit <tag> --notes-file <complete-release-body-file>
   ```

   Preserve the title and draft/prerelease state unless the user approved
   changing them.
5. Verify the release with `gh release view`. Confirm the tag, title, target,
   draft/prerelease state, URL, and that the body contains exactly one marked
   Play Store section matching the approved locale block.

Report the release URL and note that publishing starts the Android Release
workflow. Do not create a pull request or upload to Google Play unless the user
separately requests it.
