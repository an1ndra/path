# Publishing to Maven Central

The `release` profile is wired and verified: it builds a plain library jar plus sources
and javadoc, signs all four artifacts (jar, sources, javadoc, pom) and uploads them to
the Sonatype Central Portal.

Three things cannot be automated from this repository, because they need your account:

1. **A verified namespace.** `com.anindra` requires a DNS TXT record on `anindra.com`.
   If you do not control that domain, switch the groupId to `io.github.an1ndra`, which
   Sonatype verifies with a GitHub repository instead. The Java package can stay
   `com.anindra.path`; only `<groupId>` changes.
2. **A GPG key** whose public half is on a keyserver.
3. **A Portal token**, stored in `~/.m2/settings.xml` (local) or as repository secrets
   (CI).

Until those exist, `mvn deploy` will fail at authentication or signature validation —
and a Central release is **immutable**, so do not retry with a version that was already
published.

## One-time setup

### 1. Namespace

```bash
# Option A — you own anindra.com
#   central.sonatype.com → Namespaces → Add "com.anindra"
#   add the TXT record it shows to your DNS, then verify.

# Option B — no domain: use a GitHub namespace
#   add to pom.xml:  <groupId>io.github.an1ndra</groupId>
#   create a public GitHub repository named exactly "io.github.an1ndra"
#   verify it in the Portal.
```

### 2. Signing key

```bash
gpg --full-generate-key            # RSA 4096, does not expire for a demo, or ed25519
gpg --keyserver keys.openpgp.org --send-keys <FINGERPRINT>
gpg --list-secret-keys --keyid-format LONG
```

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
ls target/path-*.jar target/*.asc

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
it. `-P release` skips `spring-boot:repackage` for that reason.

## From CI

Add three repository secrets and one variable:

| Kind | Name | Value |
|---|---|---|
| secret | `MAVEN_GPG_PRIVATE_KEY` | the armored private key |
| secret | `MAVEN_GPG_PASSPHRASE` | its passphrase |
| secret | `CENTRAL_USERNAME` / `CENTRAL_PASSWORD` | the Portal token |
| variable | `PUBLISH_TO_CENTRAL` | `true` to enable the step |

The release workflow then publishes on each `v*` tag. With the variable unset the step is
skipped, so a fork never tries to publish.

## Consumers

```xml
<dependency>
  <groupId>com.anindra</groupId>
  <artifactId>path</artifactId>
  <version>0.1.0</version>
</dependency>
```

```kotlin
implementation("com.anindra:path:0.1.0")
```

Only after the first successful release. Until then the coordinates do not resolve.
