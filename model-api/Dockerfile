# syntax=docker/dockerfile:1
# The model port (ADR-0069): ghcr.io/flowtivo/tinfirm-model-api.
# Build from the repository root: docker build -f model-api/Dockerfile -t tinfirm-model-api:local .
# The base images are the server's (Dockerfile at the root), digest-pinned the same way; bump them together.
# CACHE_SCOPE suffixes the Maven cache id, as in the server's Dockerfile: `pr` (the default)
# for pull-request and local builds, `main` for the builds of main and of a release.
ARG CACHE_SCOPE=pr

FROM --platform=$BUILDPLATFORM eclipse-temurin:25-jdk-resolute@sha256:422bf3f42dc7f967f36eb4196f6d7b683c415cb541583571833d865c807c2ea2 AS build
ARG CACHE_SCOPE
WORKDIR /src/model-api
# unzip: the Maven wrapper takes the distribution's .tar.gz where unzip is missing, and its digest
# is not the pinned zip's (.mvn/wrapper/maven-wrapper.properties), so the download fails the check.
# The package is not pinned (DL3008): Ubuntu's archive serves only the release's current
# versions, which the base image's digest fixes the release of, so each build takes its fixes.
# hadolint ignore=DL3008
RUN apt-get update \
 && apt-get install -y --no-install-recommends unzip \
 && rm -rf /var/lib/apt/lists/*
COPY --chmod=755 model-api/mvnw ./
COPY model-api/.mvn/ .mvn/
COPY model-api/pom.xml ./
COPY model-api/src/ src/
# The shared libraries (libs/litellm, libs/net; CONVENTIONS §2), whose sources this build compiles in.
COPY libs/litellm/src/main/ /src/libs/litellm/src/main/
COPY libs/net/src/main/ /src/libs/net/src/main/
RUN --mount=type=cache,id=tinfirm-m2-${CACHE_SCOPE},target=/root/.m2,sharing=locked \
    set -eu; \
    ./mvnw -B -ntp -DskipTests package; \
    test -f target/tinfirm-model-api.jar; \
    mkdir -p /build; \
    cp target/tinfirm-model-api.jar /build/application.jar; \
    cp target/generated-sources/license/THIRD-PARTY.txt /build/THIRD-PARTY.txt; \
    java -Djarmode=tools -jar /build/application.jar extract --layers --destination /build/extracted; \
    mkdir -p /build/extracted/dependencies /build/extracted/spring-boot-loader \
             /build/extracted/snapshot-dependencies /build/extracted/application

FROM eclipse-temurin:25-jre-resolute@sha256:b6e00330337143a2c1a20f1c1c693d4b6ce9a3c30ade8b9d03fc68cc9e3871e7 AS runtime
ARG VERSION=0.1.0-SNAPSHOT
ARG REVISION=unknown
LABEL org.opencontainers.image.title="tinfirm-model-api" \
      org.opencontainers.image.description="Tinfirm model port: the bundled LiteLLM's model API and nothing else, for the company's services" \
      org.opencontainers.image.vendor="FLOWTIVO" \
      org.opencontainers.image.licenses="MIT" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${REVISION}" \
      org.opencontainers.image.base.name="docker.io/library/eclipse-temurin:25-jre-resolute"
RUN groupadd --system --gid 10001 tinfirm \
 && useradd --system --uid 10001 --gid 10001 --no-create-home \
      --home-dir /nonexistent --shell /usr/sbin/nologin tinfirm
WORKDIR /app
COPY --from=build /build/extracted/dependencies/ ./
COPY --from=build /build/extracted/spring-boot-loader/ ./
COPY --from=build /build/extracted/snapshot-dependencies/ ./
# The licences (ADR-0082): the repository's (MIT; no ee/ code is compiled in), and the list of
# the dependencies and their licences, which the build checks (each jar keeps its own texts).
COPY LICENSE /licenses/LICENSE
COPY --from=build /build/THIRD-PARTY.txt /licenses/THIRD-PARTY-model-api.txt
COPY --from=build /build/extracted/application/ ./
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"
USER 10001:10001
# 4001: the model routes. 4002: the health probes.
EXPOSE 4001 4002
ENTRYPOINT ["java", "-jar", "/app/application.jar"]
