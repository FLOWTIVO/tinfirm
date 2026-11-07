# Tinfirm runner base image: Debian 13 (trixie) slim with Node 26, git and build tools, the
# toolchains of Java (JDK, Maven, Gradle) and Python (pip, uv), and Docker Engine for agents
# that run their own Docker in a Kata sandbox (`run --sandbox-docker`, ADR-0025).
# Built locally by `tinfirm-runner-host images build`; tag tinfirm-runner-base:local.
#
# The agent runs as `agent` (uid/gid 1000, HOME /home/agent). Its home and /workspace are
# writable by group 0 as well (`chmod g=u`), so the image also runs under a random UID with
# GID 0. The entrypoint `tinfirm-sandbox-entrypoint` starts dockerd when
# TINFIRM_SANDBOX_DOCKER=1, writes the TINFIRM_SANDBOX_* mirror settings into the agent's home,
# and then execs the command (the ACP adapter of the runtime images).
#
# Build arguments for a host without internet access: runner/README.md, "Image build arguments".
# Pinned by digest (the multi-platform index of the tag, I6 of the security review of
# 2026-10-27): a moved tag cannot change what the sandboxes run. Change the tag and the digest
# together: `docker buildx imagetools inspect node:<tag>` prints the index's digest.
FROM node:26.13.0-trixie-slim@sha256:e4db4c914bf8ad241bd24804fc25d272d89bad97e68cdc8a1414862790a02c39

ARG DEBIAN_FRONTEND=noninteractive

# --- Package sources ----------------------------------------------------------------------
# APT_MIRROR replaces http://deb.debian.org and must serve /debian and /debian-security below
# it. Use http://: ca-certificates is not installed yet, and apt checks the signatures anyway.
ARG APT_MIRROR=
# Docker's apt repository for Debian, or a mirror of it (https).
ARG DOCKER_APT_URL=https://download.docker.com/linux/debian
ARG PIP_INDEX_URL=https://pypi.org/simple
# A Maven repository with the layout of Maven Central; Maven itself is downloaded from it.
ARG MAVEN_REPOSITORY_URL=https://repo.maven.apache.org/maven2
ARG GRADLE_DOWNLOAD_URL=https://services.gradle.org/distributions

# --- Pinned versions ----------------------------------------------------------------------
# Debian's OpenJDK: one archive and one mirror for everything apt installs. To add JDK 25:
# JDK_PACKAGES="openjdk-21-jdk-headless openjdk-25-jdk-headless" (it must be in the apt
# sources), and JDK_DEFAULT=25 to make it JAVA_HOME and the `java` on PATH.
ARG JDK_PACKAGES=openjdk-21-jdk-headless
ARG JDK_DEFAULT=21
ARG DOCKER_CE_VERSION=5:29.8.2-1~debian.13~trixie
ARG CONTAINERD_VERSION=2.3.6-1~debian.13~trixie
ARG DOCKER_BUILDX_VERSION=0.37.1-1~debian.13~trixie
ARG DOCKER_COMPOSE_VERSION=5.6.0-1~debian.13~trixie
ARG MAVEN_VERSION=3.9.16
ARG MAVEN_SHA512=831a8591fe20c8243b1dbe7d71e3244f31d1665b0804b2e825e38cbbe5ce0cafb8338851f90780735568773e0a6cd07bbec107cda0b896b008b861075358b6f6
ARG GRADLE_VERSION=9.8.0
ARG GRADLE_SHA256=bafd5ce9cfaea0fbccfdc8439a1ac42fbd4cd9c89dc9a988228d8a2639a58e6c
ARG UV_VERSION=0.12.22
# SHA-256 of uv's manylinux wheels on PyPI: x86_64, then the two aarch64 ones.
ARG UV_SHA256="680769ef701922dd93426b73476073411941f51ac46991a6e5c5a6efbf68a713 972cb556d34372f4c19f5c8bba246c92c5a8bfda3f30b2a5b8e12fddd81ba441 04af20f84c122039c16a006b497ea25d4850543b3ac2bd879d96d36d9890bebc"

# Docker's release key, fingerprint 9DC8 5822 9FC7 DD38 854A E2D8 8D81 803C 0EBF CD88.
COPY docker.asc /etc/apt/keyrings/docker.asc

# Debian packages, then Docker Engine from Docker's repository. man1 exists because the JDK's
# alternatives link man pages, which the slim image leaves out.
RUN set -eu; \
    if [ -n "${APT_MIRROR}" ]; then \
      sed -i "s|http://deb.debian.org|${APT_MIRROR%/}|g" /etc/apt/sources.list.d/debian.sources; \
    fi; \
    mkdir -p /usr/share/man/man1; \
    apt-get update; \
    apt-get install -y --no-install-recommends \
      bash \
      build-essential \
      ca-certificates \
      curl \
      e2fsprogs \
      file \
      git \
      iptables \
      jq \
      less \
      openssh-client \
      pkg-config \
      procps \
      python3 \
      python3-pip \
      python3-venv \
      ripgrep \
      unzip \
      util-linux \
      xz-utils \
      zip \
      ${JDK_PACKAGES}; \
    . /etc/os-release; \
    printf 'Types: deb\nURIs: %s\nSuites: %s\nComponents: stable\nSigned-By: /etc/apt/keyrings/docker.asc\n' \
      "${DOCKER_APT_URL%/}" "${VERSION_CODENAME}" >/etc/apt/sources.list.d/docker.sources; \
    apt-get update; \
    apt-get install -y --no-install-recommends \
      "docker-ce=${DOCKER_CE_VERSION}" \
      "docker-ce-cli=${DOCKER_CE_VERSION}" \
      "containerd.io=${CONTAINERD_VERSION}" \
      "docker-buildx-plugin=${DOCKER_BUILDX_VERSION}" \
      "docker-compose-plugin=${DOCKER_COMPOSE_VERSION}"; \
    rm -rf /var/lib/apt/lists/*; \
    setpriv --version; \
    dockerd --version; \
    docker compose version; \
    docker buildx version

RUN set -eu; \
    ln -s "java-${JDK_DEFAULT}-openjdk-$(dpkg --print-architecture)" /usr/lib/jvm/default-jdk; \
    /usr/lib/jvm/default-jdk/bin/javac -version; \
    curl -fsSL -o /tmp/maven.tar.gz \
      "${MAVEN_REPOSITORY_URL%/}/org/apache/maven/apache-maven/${MAVEN_VERSION}/apache-maven-${MAVEN_VERSION}-bin.tar.gz"; \
    printf '%s  /tmp/maven.tar.gz\n' "${MAVEN_SHA512}" >/tmp/maven.sha512; \
    sha512sum -c /tmp/maven.sha512; \
    mkdir /opt/maven; \
    tar -xzf /tmp/maven.tar.gz -C /opt/maven --strip-components=1 --no-same-owner; \
    curl -fsSL -o /tmp/gradle.zip "${GRADLE_DOWNLOAD_URL%/}/gradle-${GRADLE_VERSION}-bin.zip"; \
    printf '%s  /tmp/gradle.zip\n' "${GRADLE_SHA256}" >/tmp/gradle.sha256; \
    sha256sum -c /tmp/gradle.sha256; \
    unzip -q /tmp/gradle.zip -d /opt; \
    mv "/opt/gradle-${GRADLE_VERSION}" /opt/gradle; \
    python3 -m venv /opt/uv; \
    printf 'uv==%s' "${UV_VERSION}" >/tmp/uv-requirements.txt; \
    for sum in ${UV_SHA256}; do printf ' --hash=sha256:%s' "${sum}" >>/tmp/uv-requirements.txt; done; \
    printf '\n' >>/tmp/uv-requirements.txt; \
    /opt/uv/bin/pip install --no-cache-dir --disable-pip-version-check --only-binary :all: \
      --require-hashes --index-url "${PIP_INDEX_URL}" -r /tmp/uv-requirements.txt; \
    ln -s /opt/uv/bin/uv /opt/uv/bin/uvx /usr/local/bin/; \
    rm -f /tmp/maven.tar.gz /tmp/maven.sha512 /tmp/gradle.zip /tmp/gradle.sha256 /tmp/uv-requirements.txt; \
    JAVA_HOME=/usr/lib/jvm/default-jdk /opt/maven/bin/mvn --version; \
    JAVA_HOME=/usr/lib/jvm/default-jdk GRADLE_USER_HOME=/tmp/gradle-home /opt/gradle/bin/gradle --version; \
    rm -rf /tmp/gradle-home; \
    uv --version

# The node image ships user `node` with uid/gid 1000; rename it to `agent`. Group 0 may write
# what the agent writes, and /etc/passwd, where the entrypoint adds a random UID. /attachments
# and /output are mounts without the sandbox bridge; with it (ADR-0095) the bridge, which runs
# as the agent, fills them.
RUN groupmod --new-name agent node \
 && usermod --login agent --home /home/agent --move-home --shell /bin/bash node \
 && mkdir -p /workspace /attachments /output \
 && chown agent:0 /workspace /attachments /output \
 && chown -R agent:0 /home/agent \
 && chmod -R g=u /workspace /attachments /output /home/agent \
 && chmod g=u /etc/passwd

# tinfirm-npm-check: the runtime images run it after `npm ci`, which skips an optional package
# (a runtime's native binary) whose download fails its integrity check (threat model Q16).
COPY tinfirm-sandbox-entrypoint tinfirm-sandbox-setup tinfirm-npm-check /usr/local/bin/
RUN chmod 0755 /usr/local/bin/tinfirm-sandbox-entrypoint /usr/local/bin/tinfirm-sandbox-setup \
      /usr/local/bin/tinfirm-npm-check

ENV HOME=/home/agent \
    JAVA_HOME=/usr/lib/jvm/default-jdk \
    MAVEN_HOME=/opt/maven \
    GRADLE_HOME=/opt/gradle \
    PATH=/usr/lib/jvm/default-jdk/bin:/opt/maven/bin:/opt/gradle/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
    NPM_CONFIG_UPDATE_NOTIFIER=false \
    NPM_CONFIG_FUND=false \
    DISABLE_AUTOUPDATER=1 \
    GIT_TERMINAL_PROMPT=0

LABEL dev.tinfirm.image="base"

USER 1000:1000
WORKDIR /workspace
ENTRYPOINT ["tinfirm-sandbox-entrypoint"]
CMD ["bash"]
