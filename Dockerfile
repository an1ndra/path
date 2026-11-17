FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q -DskipTests dependency:go-offline
COPY src/ src/
RUN ./mvnw -B -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/path-*.jar app.jar

# Run as an unprivileged user (Trivy DS-0002) and keep ./data writable for the H2 file
# and the generated master key.
RUN groupadd --system --gid 1001 path \
    && useradd --system --uid 1001 --gid path --home-dir /app --shell /usr/sbin/nologin path \
    && mkdir -p /app/data \
    && chown -R path:path /app
USER path

ENV PORT=8080
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
