# Publishing to Maven Central

The `release` profile is wired and verified: it builds a plain library jar plus sources
and javadoc, signs all four artifacts (jar, sources, javadoc, pom) and uploads them to
the Sonatype Central Portal.

The published coordinates are **`io.github.an1ndra:path`**, verified through GitHub. The
Java package stays `com.anindra.path`; only the Maven coordinates use the namespace.

Three things cannot be automated from this repository, because they need your account:

1. **A verified namespace**, `io.github.an1ndra`, proved by a public GitHub repository of
   that exact name.
2. **A GPG key** whose public half is on a keyserver.
3. **A Portal token**, stored in `~/.m2/settings.xml` (local) or as repository secrets
   (CI).

Until those exist, `mvn deploy` will fail at authentication or signature validation —
and a Central release is **immutable**, so do not retry with a version that was already
published.

## One-time setup

### 1. Namespace

`io.github.an1ndra` is a **GitHub namespace**: Sonatype proves you own it by finding a
public repository with that exact name, so no domain is needed.

1. Create a **public** GitHub repository named exactly `io.github.an1ndra`
   (dots included — that is the required convention).
2. `central.sonatype.com` → **Namespaces** → **Add Namespace** → `io.github.an1ndra`.
3. Press **Verify**. It reads the repository through your GitHub account and turns
   green immediately.

Until that badge is green, every upload fails with **403**.

### 2. Signing key

```bash
gpg --full-generate-key            # RSA 4096 signing key, 0 = never expires
gpg --keyserver keys.openpgp.org --send-keys <FINGERPRINT>
gpg --list-secret-keys --keyid-format LONG
```

`keys.openpgp.org` then emails a confirmation link to the key's UID address. The key
stays invisible to consumers (404 on lookup) until that link is clicked, and Central
cannot validate uploads signed by an unpublished key.

Export the private key for CI:

```bash
gpg --armor --export-secret-keys <FINGERPRINT> > path-signing-key.asc
```

### 3. Portal credentials

`central.sonatype.com → Account → Generate User Token`, then in `~/.m2/settings.xml`:

```xml
<settings>
  <servers>
    <server>
      <id>central</id>
      <username>TOKEN_USERNAME</username>
      <password>TOKEN_PASSWORD</password>
    </server>
  </servers>
</settings>
```

## Publish

```bash
# 1. Confirm what will be uploaded (nothing leaves the machine).
./mvnw -P release -DskipTests verify
ls path-gateway/target/path-*.jar path-gateway/target/*.asc path-spring-ai/target/path-spring-ai-*.jar

# 2. Upload to the Portal. autoPublish=false, so you review before release.
MAVEN_GPG_PASSPHRASE='...' ./mvnw -P release -DskipTests deploy

# 3. In central.sonatype.com, open the deployment and press Publish.
```

Expected upload set:

```
path-0.1.0.jar            the library jar (not the Spring Boot fat jar)
path-0.1.0-sources.jar
path-0.1.0-javadoc.jar
path-0.1.0.pom
*.asc                     one signature per file
```

The runnable fat jar is **not** published; the GitHub release and container image carry
it. `-P release` skips `spring-boot:repackage` for that reason — which also means
`path-gateway/target/path-0.2.2.jar` is the **plain library** after a release build and `java -jar` on
it fails with "no main manifest attribute". Run a normal `./mvnw package` to get the
runnable jar back.

## From CI

Add three repository secrets and one variable:

| Kind | Name | Value |
|---|---|---|
| secret | `MAVEN_GPG_PRIVATE_KEY` | the armored private key |
| secret | `MAVEN_GPG_PASSPHRASE` | its passphrase |
| secret | `CENTRAL_USERNAME` / `CENTRAL_PASSWORD` | the Portal token |
| variable | `PUBLISH_TO_CENTRAL` | `true` to enable the step |

The build is a reactor: `io.github.an1ndra:path-parent` (pom), `path` (the gateway) and
`path-spring-ai` (the adapter). A release publishes all three, because a consumer resolving
the adapter needs its parent POM.

The release workflow then publishes on each `v*` tag. With the variable unset the step is
skipped, so a fork never tries to publish.

## Container image order

The image is gated before it is named. The workflow:

1. builds and pushes **only** `ghcr.io/an1ndra/path:sha-<commit>`;
2. runs Trivy against that tag;
3. promotes the exact digest with
   `docker buildx imagetools create -t ghcr.io/an1ndra/path:<tag> -t ghcr.io/an1ndra/path:latest ghcr.io/an1ndra/path:sha-<commit>`;
4. writes the promoted digest to the workflow summary.

A failing scan therefore burns no version number and leaves no version tag a deployment
could pull; the `sha-` tag stays for traceability. See issue #80 for the two releases that
were lost to the old order.

## Consumers

```xml
<dependency>
  <groupId>io.github.an1ndra</groupId>
  <artifactId>path</artifactId>
  <version>0.1.0</version>
</dependency>
```

```kotlin
implementation("io.github.an1ndra:path:0.1.0")
```

Resolvable path once published:

```
https://repo.maven.apache.org/maven2/io/github/an1ndra/path/0.1.0/path-0.1.0.pom
```

Only after the first successful release. Until then the coordinates do not resolve.
