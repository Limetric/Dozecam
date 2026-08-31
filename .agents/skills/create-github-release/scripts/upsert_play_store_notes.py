#!/usr/bin/env python3
"""Append or replace the marked Play Store notes section in a release body."""

from __future__ import annotations

import argparse
import re
from pathlib import Path

START_MARKER = "<!-- play-store-release-notes:start -->"
END_MARKER = "<!-- play-store-release-notes:end -->"
HEADING = "## Play Store release notes"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--body", required=True, type=Path)
    parser.add_argument("--play-store-notes", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    return parser.parse_args()


def validate_locale_block(text: str) -> str:
    normalized = text.rstrip("\n")
    match = re.fullmatch(r"<en-US>\n(?P<body>.+)\n</en-US>", normalized, re.DOTALL)
    if not match:
        raise ValueError(
            "Play Store notes must contain only an <en-US> locale block "
            "with content on separate lines"
        )

    content = match.group("body")
    bullets = content.splitlines()
    if not 1 <= len(bullets) <= 5 or any(
        not line.startswith("• ") or not line.removeprefix("• ").strip()
        for line in bullets
    ):
        raise ValueError("Play Store notes must contain 1–5 non-empty '• ' bullets")
    if len(content) > 500:
        raise ValueError(
            f"Play Store content is {len(content)} Unicode characters; maximum is 500"
        )
    return normalized


def release_section(locale_block: str) -> str:
    return "\n".join(
        (
            START_MARKER,
            HEADING,
            "",
            "```text",
            locale_block,
            "```",
            END_MARKER,
        )
    )


def replace_existing(body: str, section: str) -> str:
    start_count = body.count(START_MARKER)
    end_count = body.count(END_MARKER)
    if start_count or end_count:
        if start_count != 1 or end_count != 1:
            raise ValueError("Release body has malformed or duplicate Play Store markers")
        start = body.index(START_MARKER)
        end = body.index(END_MARKER, start) + len(END_MARKER)
        return body[:start].rstrip() + "\n\n" + section + body[end:]

    heading_match = re.search(r"(?m)^## Play Store release notes[ \t]*$", body)
    if heading_match:
        next_heading = re.search(r"(?m)^## ", body[heading_match.end() :])
        end = (
            heading_match.end() + next_heading.start()
            if next_heading
            else len(body)
        )
        suffix = body[end:].lstrip("\n")
        separator = "\n\n" if suffix else ""
        return body[: heading_match.start()].rstrip() + "\n\n" + section + separator + suffix

    stripped = body.rstrip()
    return f"{stripped}\n\n{section}" if stripped else section


def main() -> None:
    args = parse_args()
    body = args.body.read_text(encoding="utf-8")
    locale_block = validate_locale_block(
        args.play_store_notes.read_text(encoding="utf-8")
    )
    combined = replace_existing(body, release_section(locale_block)).rstrip() + "\n"
    args.output.write_text(combined, encoding="utf-8")
    print(args.output.resolve())


if __name__ == "__main__":
    main()
