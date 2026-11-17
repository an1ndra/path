# Security policy

## Reporting a vulnerability

Please report vulnerabilities privately through
[GitHub security advisories](https://github.com/an1ndra/path/security/advisories/new)
rather than as a public issue.

Include the version or commit, a reproduction, and the impact you believe it has. You can
expect an acknowledgement within a few days. Please do not open a public issue, and do not
test against infrastructure you do not own.

## Scope

In scope:

- the gateway's HTTP surface (`/v1/**`, `/admin/**`)
- authentication and authorisation (client keys, admin tokens, scopes)
- credential handling (AES-GCM storage, redaction, logging)
- the container image and its dependencies

Out of scope:

- vulnerabilities in upstream model providers
- findings that require a compromised host or an already-leaked admin token
- denial of service from traffic you are authorised to send at unlimited volume

## How this repository is checked

Every pull request runs:

| Check | Tool |
|---|---|
| Secret scanning (full history) | gitleaks |
| New dependency review (fails on high severity) | `actions/dependency-review-action` |
| Static analysis for Java | CodeQL |
| Secrets and misconfiguration in the tree | Trivy |
| Unit, integration and end-to-end tests | Maven + the smoke suite |

Releases additionally scan the built container image with Trivy (fails on
HIGH/CRITICAL) and publish a CycloneDX SBOM plus SHA-256 checksums with the artifacts.

## Verifying a release

Each release ships `path-<version>.jar`, `bom.json` and `checksums.txt`:

```bash
sha256sum -c checksums.txt          # both files must report OK
cosign verify ...                   # not used yet: artifacts are not signed
```

The container digest is printed in the workflow summary; pin it by digest:

```bash
docker pull ghcr.io/an1ndra/path@sha256:<digest>
```

The Maven wrapper also verifies its own distribution: `distributionSha256Sum` in
`.mvn/wrapper/maven-wrapper.properties` is checked on every download.

## Supported versions

The latest tagged release receives fixes. `Develop` is the development branch and is not
supported for production use.
