#!/usr/bin/env python3
"""Print the release notes for a tag, for use as a GitHub release body.

    python3 tools/changelog.py v0.1.0     > release-notes.md
    python3 tools/changelog.py 0.1.0      # the v prefix is optional

Reads `changelog/<tag>.md`. Exits non-zero when the file is missing or empty, so a tag
without notes fails the release instead of publishing a blank body.
"""
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CHANGELOG_DIR = ROOT / "changelog"


def find(version: str) -> Path | None:
    candidates = [f"{version}.md"]
    if version.startswith("v"):
        candidates.append(f"{version[1:]}.md")
    else:
        candidates.append(f"v{version}.md")
    for name in candidates:
        path = CHANGELOG_DIR / name
        if path.exists():
            return path
    return None


def main() -> int:
    if len(sys.argv) < 2:
        print("usage: changelog.py <version>", file=sys.stderr)
        return 2

    version = sys.argv[1].strip()
    path = find(version)
    if path is None:
        print(
            f"No changelog file for '{version}'. Add changelog/{version}.md before tagging "
            f"(see changelog/README.md).",
            file=sys.stderr,
        )
        return 1

    body = path.read_text(encoding="utf-8").strip()
    if not body or body.endswith("Nothing yet.") or "Nothing yet." in body:
        print(f"changelog/{path.name} has no real entries yet.", file=sys.stderr)
        return 1

    print(body)
    return 0


if __name__ == "__main__":
    sys.exit(main())
