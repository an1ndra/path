#!/usr/bin/env python3
"""Print the changelog section for a version, for use as a GitHub release body.

    python3 tools/changelog.py 0.1.0     > release-notes.md
    python3 tools/changelog.py v0.1.0    # leading v is accepted

Exits non-zero when the version has no section, so a release fails instead of
publishing an empty body.
"""
import re
import sys
from pathlib import Path

CHANGELOG = Path(__file__).resolve().parent.parent / "CHANGELOG.md"


def section(version: str) -> str:
    text = CHANGELOG.read_text(encoding="utf-8")
    pattern = re.compile(
        rf"^##\s*\[{re.escape(version)}\][^\n]*\n(.*?)(?=^##\s*\[|\Z)",
        re.MULTILINE | re.DOTALL,
    )
    match = pattern.search(text)
    return match.group(1).strip() if match else ""


def main() -> int:
    if len(sys.argv) < 2:
        print("usage: changelog.py <version>", file=sys.stderr)
        return 2

    raw = sys.argv[1].strip()
    version = raw[1:] if raw.startswith("v") else raw

    body = section(version)
    if not body:
        print(
            f"No changelog section found for '{version}'. Add one to CHANGELOG.md "
            f"before tagging.",
            file=sys.stderr,
        )
        return 1

    if body.strip() in {"", "Nothing yet."}:
        print(f"The changelog section for '{version}' is empty.", file=sys.stderr)
        return 1

    print(f"## {raw}\n")
    print(body)
    return 0


if __name__ == "__main__":
    sys.exit(main())
