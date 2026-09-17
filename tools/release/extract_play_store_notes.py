#!/usr/bin/env python3
"""Extract approved Play Store locale blocks from a GitHub release body.

The release body carries the copy approved through the create-github-release
skill, inside a marked section:

    <!-- play-store-release-notes:start -->
    ## Play Store release notes

    ```text
    <en-US>
    • A user-facing change
    </en-US>
    ```
    <!-- play-store-release-notes:end -->

Each locale block becomes a `whatsnew-<locale>` file that
r0adkll/upload-google-play reads as that locale's "What's new" text. The file
holds exactly the characters Play receives, so its length is the length Play
validates.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

START_MARKER = "<!-- play-store-release-notes:start -->"
END_MARKER = "<!-- play-store-release-notes:end -->"
LOCALE_BLOCK = re.compile(
    r"<(?P<locale>[a-z]{2,3}(?:-[A-Za-z]{2,4})?)>\n(?P<text>.+?)\n</(?P=locale)>",
    re.DOTALL,
)
# Google Play rejects release notes longer than this.
MAX_CHARACTERS = 500


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--body-file",
        type=Path,
        help="Release body file; defaults to stdin.",
    )
    parser.add_argument("--output-dir", required=True, type=Path)
    return parser.parse_args()


def read_body(body_file: Path | None) -> str:
    raw = body_file.read_text(encoding="utf-8") if body_file else sys.stdin.read()
    return raw.replace("\r\n", "\n").replace("\r", "\n")


def extract_section(body: str) -> str | None:
    start = body.find(START_MARKER)
    end = body.find(END_MARKER, start + 1) if start != -1 else -1
    if start == -1 or end == -1:
        return None
    return body[start + len(START_MARKER) : end]


def extract_locales(section: str) -> dict[str, str]:
    locales: dict[str, str] = {}
    for match in LOCALE_BLOCK.finditer(section):
        locale = match.group("locale")
        text = match.group("text").strip("\n")
        if locale in locales:
            raise ValueError(f"Release body has duplicate <{locale}> blocks")
        if not text.strip():
            raise ValueError(f"<{locale}> block is empty")
        if len(text) > MAX_CHARACTERS:
            raise ValueError(
                f"<{locale}> block is {len(text)} Unicode characters; "
                f"Google Play allows at most {MAX_CHARACTERS}"
            )
        locales[locale] = text
    return locales


def main() -> int:
    args = parse_args()
    section = extract_section(read_body(args.body_file))
    if section is None:
        # Releases cut outside the skill have no approved copy. Publish the
        # bundle anyway rather than blocking on notes Play treats as optional.
        print(
            "::warning::Release body has no Play Store release notes section; "
            "publishing without 'What's new' text."
        )
        args.output_dir.mkdir(parents=True, exist_ok=True)
        return 0

    locales = extract_locales(section)
    if not locales:
        raise ValueError("Play Store release notes section has no locale blocks")

    args.output_dir.mkdir(parents=True, exist_ok=True)
    for locale, text in sorted(locales.items()):
        destination = args.output_dir / f"whatsnew-{locale}"
        # No trailing newline: the uploader reads each file verbatim, so a
        # newline here would count toward Play's per-locale character limit.
        destination.write_text(text, encoding="utf-8")
        print(f"{destination} ({len(text)} characters)")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except ValueError as error:
        print(f"::error::{error}")
        sys.exit(1)
