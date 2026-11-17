FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

# Fetch Maven here instead of through ./mvnw. The wrapper's download produced a file that
# failed its own SHA-256 check inside this image, so every image build broke. Doing it
# explicitly keeps .mvn/wrapper/maven-wrapper.properties as the single source of truth and
# fails loudly if the artifact ever stops matching the pin.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl unzip ca-certificates \
 && rm -rf /var/lib/apt/lists/*

COPY .mvn/wrapper/maven-wrapper.properties /tmp/maven-wrapper.properties
RUN set -eux; \
    URL="$(sed -n 's/^distributionUrl=//p' /tmp/maven-wrapper.properties | tr -d '[:space:]')"; \
    SUM="$(sed -n 's/^distributionSha256Sum=//p' /tmp/maven-wrapper.properties | tr -d '[:space:]')"; \
    test -n "$URL"; \
    test -n "$SUM"; \
    curl -fsSL --retry 3 -o /tmp/maven.zip "$URL"; \
    echo "$SUM  /tmp/maven.zip" | sha256sum -c -; \
    unzip -q /tmp/maven.zip -d /opt; \
    ln -sfn "$(echo /opt/apache-maven-*/bin/mvn)" /usr/local/bin/mvn; \
    rm -f /tmp/maven.zip /tmp/maven-wrapper.properties; \
    mvn -v

COPY pom.xml ./
RUN mvn -B -q -DskipTests dependency:go-offline

COPY src/ src/
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/path-*.jar app.jar

# Run as an unprivileged user (Trivy DS-0002) and keep ./data writable for the H2 file
# and the generated master key.
RUN groupadd --system --gid 1001 path \
    && useradd --system --uid 1001 --gid path --home-dir /app --shell /usr/sbin/nologin path \
    && chown -R path:path /app
USER path

ENV PORT=8080
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
