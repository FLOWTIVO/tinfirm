# syntax=docker/dockerfile:1

# Tinfirm server image: the Angular UI built into the Spring Boot jar, on a JRE 25.
#
#   docker build -t ghcr.io/flowtivo/tinfirm-server:dev .      (or: make build-image)
#
# The build context is the repository root; .dockerignore whitelists api/, web/, server/,
# libs/, ee/ and the root LICENSE.
#
# Base images are pinned tag@digest: a tag alone is mutable, so the same commit built a week
# later would be a different image. Bump the tag and the digest together (Dependabot does it;
# by hand: `docker buildx imagetools inspect <tag>`).
#
# BuildKit cache mounts keep the npm and Maven caches between builds on the same daemon
# (a local one, or a self-hosted CI runner's), so a source-only change skips most downloads.
# Every cache id ends in CACHE_SCOPE: `pr` (the default) for pull-request and local builds,
# `main` for the builds of main and of a release (.github/workflows/ci.yml, release.yml).
# A pull request's build, and any dependency it pulls, then never writes a cache that a
# build of main or a release reads.
ARG CACHE_SCOPE=pr

# ---- 1. Web UI ------------------------------------------------------------------------
FROM --platform=$BUILDPLATFORM node:24-trixie-slim@sha256:54f84b4cf8432b21aaffd5d3ce8d41e12cea0d10d33c09d2f3c9879d49d6b446 AS web
ARG CACHE_SCOPE
ENV CI=true \
    NG_CLI_ANALYTICS=false \
    NPM_CONFIG_AUDIT=false \
    NPM_CONFIG_FUND=false \
    NPM_CONFIG_UPDATE_NOTIFIER=false
WORKDIR /src/web
# Dependencies first, so a source-only change does not reinstall node_modules.
COPY web/package.json web/package-lock.json ./
RUN --mount=type=cache,id=tinfirm-npm-${CACHE_SCOPE},target=/root/.npm \
    npm ci
# `npm run build` runs `prebuild` -> `generate:api`, which reads ../api/openapi.yaml.
COPY api/ /src/api/
COPY web/ ./
# The build also writes the notices of the packages it bundles, 3rdpartylicenses.txt, beside
# browser/: the runtime stage puts them in /licenses/. spartan/ui's notice is added to them in
# their format: its helm components are copied into src/app/ui rather than installed, and its
# @spartan-ng/brain package carries no licence text for the build to find.
RUN npm run build \
 && { test -f dist/tinfirm-web/browser/index.html \
      || { echo "web build did not produce dist/tinfirm-web/browser/index.html" >&2; exit 1; }; } \
 && { test -s dist/tinfirm-web/3rdpartylicenses.txt \
      || { echo "web build did not produce dist/tinfirm-web/3rdpartylicenses.txt" >&2; exit 1; }; } \
 && { printf 'Package: %s\nLicense: "MIT"\n\n' '@spartan-ng/brain, and the spartan/ui helm components in src/app/ui' \
      && cat src/app/ui/LICENSE \
      && printf '\n%s\n' '--------------------------------------------------------------------------------'; } \
    >> dist/tinfirm-web/3rdpartylicenses.txt
# Each hashed JavaScript and CSS file gzipped once, beside it (chunk-5DTZP5O2.js.gz): the server sends the copy
# to a browser that accepts gzip (shared.web.SpaConfiguration), so it compresses nothing per request. -n leaves
# the name and time out of the copy, so the same build gives the same bytes.
RUN find dist/tinfirm-web/browser -maxdepth 1 -type f \( -name '*.js' -o -name '*.css' \) -exec gzip -9 -k -n {} +

# ---- 2. Server jar --------------------------------------------------------------------
FROM --platform=$BUILDPLATFORM eclipse-temurin:25-jdk-resolute@sha256:422bf3f42dc7f967f36eb4196f6d7b683c415cb541583571833d865c807c2ea2 AS server
ARG CACHE_SCOPE
WORKDIR /src/server
# unzip: the Maven wrapper takes the distribution's .tar.gz where unzip is missing, and its digest
# is not the pinned zip's (.mvn/wrapper/maven-wrapper.properties), so the download fails the check.
# The package is not pinned (DL3008): Ubuntu's archive serves only the release's current
# versions, which the base image's digest fixes the release of, so each build takes its fixes.
# hadolint ignore=DL3008
RUN apt-get update \
 && apt-get install -y --no-install-recommends unzip \
 && rm -rf /var/lib/apt/lists/*
# --chmod: the wrapper must run even if it was committed without its executable bit.
COPY --chmod=755 server/mvnw ./
COPY server/.mvn/ .mvn/
COPY server/pom.xml ./
# The OpenAPI generator reads ../api/openapi.yaml.
COPY api/ /src/api/
COPY server/src/ src/
# The shared library (libs/jira; CONVENTIONS §2), whose sources the server's build compiles in.
COPY libs/jira/src/main/ /src/libs/jira/src/main/
# ee/, the commercial part (ADR-0077), whose sources the server's build compiles in: in every image.
COPY ee/src/main/ /src/ee/src/main/
# The server serves the SPA from classpath:/static/.
COPY --from=web /src/web/dist/tinfirm-web/browser/ src/main/resources/static/
# sharing=locked: two builds on one daemon must not write the Maven repository at the same
# time. The wrapper's Maven distribution is cached there too.
#
# Then take exactly one executable jar and unpack it into Spring Boot's layers, so the
# runtime image puts the dependencies (which rarely change) in their own layer below the
# application classes. The build checks the dependencies' licences (license-maven-plugin,
# ADR-0082) and lists them, for the runtime stage's /licenses/.
RUN --mount=type=cache,id=tinfirm-m2-${CACHE_SCOPE},target=/root/.m2,sharing=locked \
    set -eu; \
    ./mvnw -B -ntp -DskipTests package; \
    set -- target/tinfirm-server-*.jar; \
    if [ "$#" -ne 1 ] || [ ! -f "$1" ]; then \
      echo "expected exactly one target/tinfirm-server-*.jar, found: $*" >&2; exit 1; \
    fi; \
    mkdir -p /build; \
    cp "$1" /build/application.jar; \
    cp target/generated-sources/license/THIRD-PARTY.txt /build/THIRD-PARTY.txt; \
    java -Djarmode=tools -jar /build/application.jar extract --layers --destination /build/extracted; \
    mkdir -p /build/extracted/dependencies /build/extracted/spring-boot-loader \
             /build/extracted/snapshot-dependencies /build/extracted/application

# ---- 3. Runtime -----------------------------------------------------------------------
FROM eclipse-temurin:25-jre-resolute@sha256:b6e00330337143a2c1a20f1c1c693d4b6ce9a3c30ade8b9d03fc68cc9e3871e7 AS runtime
ARG VERSION=0.1.0-SNAPSHOT
ARG REVISION=unknown
LABEL org.opencontainers.image.title="tinfirm-server" \
      org.opencontainers.image.description="Tinfirm server: API, worker and web UI in one Spring Boot image" \
      org.opencontainers.image.vendor="FLOWTIVO" \
      org.opencontainers.image.licenses="MIT AND LicenseRef-Tinfirm-Enterprise" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${REVISION}" \
      org.opencontainers.image.base.name="docker.io/library/eclipse-temurin:25-jre-resolute"
# Fixed numeric UID/GID, so Kubernetes runAsNonRoot/runAsUser can pin and verify it.
RUN groupadd --system --gid 10001 tinfirm \
 && useradd --system --uid 10001 --gid 10001 --no-create-home \
      --home-dir /nonexistent --shell /usr/sbin/nologin tinfirm
WORKDIR /app
# Least-changing layer first. The files stay owned by root: the app user can read its
# code but not change it, with or without a read-only root filesystem.
COPY --from=server /build/extracted/dependencies/ ./
COPY --from=server /build/extracted/spring-boot-loader/ ./
COPY --from=server /build/extracted/snapshot-dependencies/ ./
# The licences (ADR-0082): the repository's (MIT, and ee/'s for the ee/ part compiled in),
# the list of the server's dependencies and their licences (each jar keeps its own texts),
# and the notices of the packages the web bundle holds.
COPY LICENSE /licenses/LICENSE
COPY ee/LICENSE /licenses/ee/LICENSE
COPY --from=server /build/THIRD-PARTY.txt /licenses/THIRD-PARTY-server.txt
COPY --from=web /src/web/dist/tinfirm-web/3rdpartylicenses.txt /licenses/THIRD-PARTY-web.txt
COPY --from=server /build/extracted/application/ ./
# Heap sized from the container memory limit; exit on OOM so the orchestrator restarts
# the pod instead of leaving a half-dead JVM. Replace the whole variable to change it
# (Helm: javaToolOptions). The JVM prints one "Picked up JAVA_TOOL_OPTIONS" line on start.
# The only writable path the server needs is /tmp (Tomcat work dir, java.io.tmpdir), so
# the image runs with a read-only root filesystem.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"
# The build's git revision, reported by GET /api/system/info (`commit`). Runner hosts on
# auto-deploy follow it (docs/operations/auto-deploy.md). Last, so that a new revision does
# not invalidate the cached layers above.
ENV TINFIRM_REVISION="${REVISION}"
# The session bundles' `filesystem` store (ADR-0097), the server user's alone, so that a volume
# mounted here (Compose's session-bundles) starts with that owner. Unused with another store.
RUN mkdir -p /var/lib/tinfirm/session-bundles \
 && chown 10001:10001 /var/lib/tinfirm/session-bundles \
 && chmod 700 /var/lib/tinfirm/session-bundles
USER 10001:10001
# 8080 application (API, OIDC, SPA); 8081 management (health, prometheus), never exposed
# through an ingress.
EXPOSE 8080 8081
ENTRYPOINT ["java", "-jar", "/app/application.jar"]
