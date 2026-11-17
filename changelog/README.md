# Changelog

One file per release, named exactly like the git tag:

```
changelog/
├── README.md          this file
├── unreleased.md      notes for the next release (never published on its own)
└── v0.1.0.md          published as the body of the v0.1.0 GitHub release
```

## Adding changes

Append to `changelog/unreleased.md` as you work. Keep the sections in the
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) order: Added, Changed,
Deprecated, Removed, Fixed, Security.

## Cutting a release

1. Copy `changelog/unreleased.md` to `changelog/vX.Y.Z.md` (the tag name, including `v`).
2. Rewrite the first heading to `# vX.Y.Z` and adjust the sections.
3. Reset `unreleased.md` to an empty template.
4. Commit on `Develop`, open the PR into `main`.
5. Tag `vX.Y.Z` on `main` and push.

The release workflow runs `tools/changelog.py "$GITHUB_REF_NAME"`, which reads
`changelog/<tag>.md` and uses it verbatim as the release body. A tag without a file, or
with no real entries (only `Nothing yet.` placeholders), **fails the release** rather than
publishing blank notes.

Preview locally before tagging:

```bash
python3 tools/changelog.py v0.1.0
```
