# syntax=docker/dockerfile:1
# Builds the Ledgerline service. Used by docker-compose.yml.

FROM gradle:8.14.3-jdk21 AS build
WORKDIR /src
# Dependency layer first so source edits don't re-download the world.
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle/libs.versions.toml gradle/libs.versions.toml
COPY core/build.gradle.kts core/
COPY ledger/build.gradle.kts ledger/
COPY calc/build.gradle.kts calc/
COPY ingest/build.gradle.kts ingest/
COPY app/build.gradle.kts app/
COPY tests/build.gradle.kts tests/
RUN gradle :app:dependencies --no-daemon -q > /dev/null || true
COPY . .
RUN gradle :app:installDist --no-daemon -q

FROM eclipse-temurin:21-jre-alpine
WORKDIR /opt/ledgerline
COPY --from=build /src/app/build/install/app/ ./
EXPOSE 8080
HEALTHCHECK --interval=5s --timeout=3s --retries=20 CMD wget -qO- http://localhost:8080/health || exit 1
ENTRYPOINT ["/opt/ledgerline/bin/app"]
