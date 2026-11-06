# syntax=docker/dockerfile:1

# Static Linux builds of tinfirm-runner-host (musl), for the release assets
# tinfirm-runner-host-<version>-<target>.tar.gz, and the runner host's image. The stage
# `binary` is the FILE alone: deploy/release/assets.sh exports it and packages it:
#
#   docker build -f deploy/release/runner-musl.Dockerfile --target binary \
#     --build-arg TARGET=aarch64-unknown-linux-musl --output type=local,dest=out .
#
# The last stage, the default, is the image ghcr.io/flowtivo/tinfirm-runner-host (ADR-0101,
# decision 8): the binary on Debian slim with git, for the Helm chart's runner pod, which runs
# its sandboxes as VMs and needs no Docker. It holds no agent runtime, so it may be published;
# TARGET follows the platform of the build (--platform) when it is not given.
#
# Both targets cross-compile on the build platform (no emulation) with cargo-zigbuild: zig
# is the C compiler for ring and the linker against Rust's bundled musl. The result is one
# static executable that runs on any Linux of its architecture, whatever its libc.
#
# The build context is the repository root; runner-musl.Dockerfile.dockerignore next to this
# file whitelists runner/ and protocol/ (tinfirm-protocol includes
# protocol/runner-v1.schema.json). The base image is pinned tag@digest and the tools by
# version and checksum. ci.yml's `runner` job tests with the same image (runner/ci.Dockerfile);
# change both together.
#
# CACHE_SCOPE suffixes the cache ids: `pr` (the default) for local and pull-request builds,
# `main` for the builds of `rolling` and of a release (assets.sh passes $CACHE_SCOPE, which
# runner-artifacts.yml sets), so that no other build writes the registry or target
# directory a published binary is built from.
#
# TINFIRM_RUNNER_BUILD, empty by default, is what the binary was published as: assets.sh
# passes the asset's version (`rolling-<sha7>` for rolling, the version for a release).
# `--version` shows it next to the workspace's version; hello.runnerVersion stays the
# workspace's version alone (ADR-0109).
ARG CACHE_SCOPE=pr

# ---- 1. Toolchain: Rust, the two musl targets, zig, cargo-zigbuild ----------------------
FROM --platform=$BUILDPLATFORM rust:1.99.1-bookworm@sha256:9181b56c2a21ab1d5e73cffe04fc762426d30aa7fac3c91141dd033f49a7fc8b AS toolchain
ARG BUILDARCH
ARG ZIG_VERSION=0.16.0
# From https://ziglang.org/download/index.json, "<arch>-linux".shasum of ZIG_VERSION.
ARG ZIG_SHA256_AMD64=70e49664a74374b48b51e6f3fdfbf437f6395d42509050588bd49abe52ba3d00
ARG ZIG_SHA256_ARM64=ea4b09bfb22ec6f6c6ceac57ab63efb6b46e17ab08d21f69f3a48b38e1534f17
ARG CARGO_ZIGBUILD_VERSION=0.23.4
SHELL ["/bin/bash", "-o", "pipefail", "-c"]
RUN case "$BUILDARCH" in \
      amd64) arch=x86_64; sum="$ZIG_SHA256_AMD64" ;; \
      arm64) arch=aarch64; sum="$ZIG_SHA256_ARM64" ;; \
      *) echo "unsupported build platform: $BUILDARCH" >&2; exit 1 ;; \
    esac \
 && curl -fsSL --retry 3 -o /tmp/zig.tar.xz \
      "https://ziglang.org/download/${ZIG_VERSION}/zig-${arch}-linux-${ZIG_VERSION}.tar.xz" \
 && echo "${sum}  /tmp/zig.tar.xz" | sha256sum -c - \
 && mkdir -p /opt/zig \
 && tar -xJf /tmp/zig.tar.xz -C /opt/zig --strip-components=1 \
 && ln -s /opt/zig/zig /usr/local/bin/zig \
 && rm /tmp/zig.tar.xz \
 && rustup target add x86_64-unknown-linux-musl aarch64-unknown-linux-musl \
 && cargo install --locked "cargo-zigbuild@${CARGO_ZIGBUILD_VERSION}" \
 && rm -rf /usr/local/cargo/registry

# ---- 2. Build -------------------------------------------------------------------------
FROM toolchain AS build
ARG TARGET
ARG CACHE_SCOPE
ARG TARGETARCH
ARG TINFIRM_RUNNER_BUILD=
WORKDIR /src
COPY protocol/ protocol/
COPY runner/ runner/
COPY ee/runner/ ee/runner/
WORKDIR /src/runner
# The cache mounts keep the crate registry and the target directory between builds on the
# same daemon. Both targets share the target directory: each has its own target/<triple>/,
# and they share the build scripts.
RUN --mount=type=cache,id=tinfirm-cargo-registry-${CACHE_SCOPE},target=/usr/local/cargo/registry,sharing=locked \
    --mount=type=cache,id=tinfirm-runner-musl-target-${CACHE_SCOPE},target=/src/runner/target,sharing=locked \
    set -eu; \
    if [ -z "${TARGET:-}" ]; then \
      case "$TARGETARCH" in \
        amd64) TARGET=x86_64-unknown-linux-musl ;; \
        arm64) TARGET=aarch64-unknown-linux-musl ;; \
        *) echo "no TARGET, and no musl target for the platform '$TARGETARCH'" >&2; exit 1 ;; \
      esac; \
    fi; \
    case "$TARGET" in \
      x86_64-unknown-linux-musl) machine=x86-64 ;; \
      aarch64-unknown-linux-musl) machine=aarch64 ;; \
      *) echo "TARGET must be x86_64-unknown-linux-musl or aarch64-unknown-linux-musl, not '$TARGET'" >&2; exit 1 ;; \
    esac; \
    cargo zigbuild --release --locked --target "$TARGET" -p tinfirm-runner-host; \
    install -D -m 755 "target/$TARGET/release/tinfirm-runner-host" /out/tinfirm-runner-host; \
    info="$(file -b /out/tinfirm-runner-host)"; \
    echo "tinfirm-runner-host for $TARGET: $info"; \
    case "$info" in \
      *"$machine"*"statically linked"* | *"$machine"*"static-pie linked"*) ;; \
      *) echo "expected a static $machine executable" >&2; exit 1 ;; \
    esac

# ---- 3. The binary alone, for --output type=local ---------------------------------------
FROM scratch AS binary
COPY --from=build /out/tinfirm-runner-host /tinfirm-runner-host

# ---- 4. The image: the binary, git and the CA certificates -------------------------------
# git for the clones and mirrors, ca-certificates for the server's and the providers' TLS
# (the binary reads the system's roots), procps for the `kill` that `drain` sends SIGHUP
# with. The data, the run records (ADR-0101, decision 7) and the mirrors go to
# /var/lib/tinfirm-runner, the volume the chart mounts there.
FROM debian:trixie-slim@sha256:f6b08b09a05725f9431502d3b5714f5c4576e82c1a84fa1debe0c24a7e0c3ec4 AS image
ARG VERSION=0.1.0-SNAPSHOT
ARG REVISION=unknown
ARG DEBIAN_FRONTEND=noninteractive
LABEL org.opencontainers.image.title="tinfirm-runner-host" \
      org.opencontainers.image.description="Tinfirm runner host, for the Helm chart's runner pod: its sandboxes are VMs (run --executor vm)" \
      org.opencontainers.image.vendor="FLOWTIVO" \
      org.opencontainers.image.licenses="MIT AND LicenseRef-Tinfirm-Enterprise" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${REVISION}" \
      org.opencontainers.image.base.name="docker.io/library/debian:trixie-slim"
# The packages are not pinned (DL3008): Debian's archive serves only the release's current
# versions, which the base image's digest fixes the release of, so each build takes its fixes.
# hadolint ignore=DL3008
RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates git procps \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd --system --gid 10001 tinfirm \
 && useradd --system --uid 10001 --gid 10001 --home-dir /var/lib/tinfirm-runner \
      --shell /usr/sbin/nologin tinfirm \
 && install -d -o 10001 -g 10001 -m 700 /var/lib/tinfirm-runner
# The licences (ADR-0082): the repository's (MIT, and ee/'s for the ee/ part compiled in) and
# the crates' (runner/THIRD-PARTY-LICENSES.txt, which ci.yml's licence check keeps current).
COPY LICENSE /licenses/LICENSE
COPY ee/LICENSE /licenses/ee/LICENSE
COPY runner/THIRD-PARTY-LICENSES.txt /licenses/THIRD-PARTY-runner.txt
COPY --from=build /out/tinfirm-runner-host /usr/local/bin/tinfirm-runner-host
ENV HOME=/var/lib/tinfirm-runner \
    TINFIRM_RUNNER_CONFIG_DIR=/var/lib/tinfirm-runner/config \
    TINFIRM_RUNNER_DATA_DIR=/var/lib/tinfirm-runner/data
USER 10001:10001
WORKDIR /var/lib/tinfirm-runner
ENTRYPOINT ["/usr/local/bin/tinfirm-runner-host"]
CMD ["run"]
