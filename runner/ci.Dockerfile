# syntax=docker/dockerfile:1

# The checks of the runner workspace for CI (ci.yml, job `runner`): rustfmt, clippy, the
# tests and the crates' licences, each a stage built on the CI runner's Docker daemon. It
# builds no image to keep:
#
#   docker build -f runner/ci.Dockerfile --target fmt .
#   docker build -f runner/ci.Dockerfile --target clippy .
#   docker build -f runner/ci.Dockerfile --target test .
#   docker build -f runner/ci.Dockerfile --target licences .
#
# Why in Docker: ring's build script and the linker need a C toolchain, which this image has,
# so a CI runner needs none; nothing here writes into the runner's checkout or into the $HOME
# that a self-hosted runner shares between jobs. Locally, `make runner-test` runs the same
# checks without this image.
#
# The build context is the repository root; ci.Dockerfile.dockerignore next to this file
# whitelists runner/, ee/runner/, protocol/ (tinfirm-protocol includes the schemas) and
# the server's public suffix list (tests/public_suffix.rs). The Rust image is the release
# build's (deploy/release/runner-musl.Dockerfile), pinned tag@digest, and Node the server
# image's (Dockerfile); change them together.

# ---- 1. Toolchain: Rust with clippy and rustfmt, Node ----------------------------------
FROM rust:1.99.1-bookworm@sha256:e6f2cff35d0f309acf8433b4f05878ed77092602c5065cc81801177da0bb3a9c AS toolchain
# The end-to-end tests start the fake ACP agent with Node, and run the pi adapter's tests.
COPY --from=node:24-trixie-slim@sha256:54f84b4cf8432b21aaffd5d3ce8d41e12cea0d10d33c09d2f3c9879d49d6b446 /usr/local/bin/node /usr/local/bin/node
RUN rustup component add clippy rustfmt \
 && node --version
# Off, as rust-cache sets it for CI: incremental data would only grow the kept target
# directory, and every build starts from a fresh checkout's timestamps anyway.
ENV CARGO_INCREMENTAL=0 \
    CARGO_TERM_COLOR=always

# ---- 2. The workspace ------------------------------------------------------------------
FROM toolchain AS source
WORKDIR /src
COPY protocol/ protocol/
COPY server/src/main/resources/psl/public_suffix_list.dat server/src/main/resources/psl/
COPY runner/ runner/
COPY ee/runner/ ee/runner/
WORKDIR /src/runner

# ---- 3. The checks ---------------------------------------------------------------------
FROM source AS fmt
RUN cargo fmt --all --check

# The cache mounts keep the crate registry and the target directory between builds on the
# same daemon. `private`: a build that runs while another one holds them gets fresh ones
# instead of waiting for it (a self-hosted machine can run several jobs at a time).
FROM source AS clippy
RUN --mount=type=cache,id=tinfirm-runner-ci-registry,target=/usr/local/cargo/registry,sharing=private \
    --mount=type=cache,id=tinfirm-runner-ci-target,target=/src/runner/target,sharing=private \
    cargo clippy --workspace --all-targets --locked -- -D warnings

# Unit, protocol schema, CLI and end-to-end tests, and the local end-to-end tests again with
# every run over the sandbox bridge (TINFIRM_TEST_SANDBOX_BRIDGE, ADR-0095) and with a VM per
# run through the fake driver (TINFIRM_TEST_VM_EXECUTOR, ADR-0096). docker_runs.rs,
# which runs the key scenarios in real containers, is skipped: a build has no Docker daemon.
# `make runner-test` runs it on a host with Docker (runner/README.md, "Linux hosts").
# Two limits a build may meet, which the tests notice:
# - a RUN may start with a soft limit of 1024 open files (GitHub's runners allow 65536), and
#   egress_proxy's open_tunnels_are_bounded holds 256 tunnels of four descriptors each in one
#   process: the soft limit goes up to the hard one first;
# - a slow disk can take a second for an fsync, and a run's own files sync (its repository
#   mirror's state, for one) while some tests time the run, such as local_runs' cancel, whose
#   push of the work in progress misses its time with fsync slowed to 1 s: the tests'
#   temporary directories are in a tmpfs at /tmp.
FROM source AS test
ENV TINFIRM_SKIP_DOCKER_TESTS=1
SHELL ["/bin/bash", "-o", "pipefail", "-c"]
RUN --mount=type=cache,id=tinfirm-runner-ci-registry,target=/usr/local/cargo/registry,sharing=private \
    --mount=type=cache,id=tinfirm-runner-ci-target,target=/src/runner/target,sharing=private \
    --mount=type=tmpfs,target=/tmp \
    ulimit -n "$(ulimit -Hn)" 2>/dev/null || ulimit -n 65536 2>/dev/null; \
    echo "open files: $(ulimit -Sn) (hard $(ulimit -Hn))"; \
    cargo test --workspace --locked \
    && TINFIRM_TEST_SANDBOX_BRIDGE=1 cargo test --locked -p tinfirm-runner-host --test local_runs \
    && TINFIRM_TEST_VM_EXECUTOR=1 cargo test --locked -p tinfirm-runner-host --test local_runs

# ---- 4. The crates' licences (ADR-0082) ------------------------------------------------
# cargo-about, pinned by version, reads the licences of the crates the binary is built with
# (runner/about.toml): it fails on a crate whose licence the policy does not accept, and
# writes the third-party notices every runner host archive holds (runner/about.hbs).
# `licences` also fails when the committed runner/THIRD-PARTY-LICENSES.txt is not what
# Cargo.lock gives; `make runner-licences` writes it again from `licences-file`:
#
#   docker build -f runner/ci.Dockerfile --target licences-file --output type=local,dest=runner .
FROM toolchain AS about
ARG CARGO_ABOUT_VERSION=0.9.2
RUN cargo install --locked --features cli "cargo-about@${CARGO_ABOUT_VERSION}" \
 && rm -rf /usr/local/cargo/registry

FROM about AS licences-generate
WORKDIR /src
COPY protocol/ protocol/
COPY runner/ runner/
COPY ee/runner/ ee/runner/
WORKDIR /src/runner
RUN --mount=type=cache,id=tinfirm-runner-ci-registry,target=/usr/local/cargo/registry,sharing=private \
    mkdir -p /out \
 && cargo about generate --locked --fail -o /out/THIRD-PARTY-LICENSES.txt about.hbs

FROM licences-generate AS licences
RUN diff -u THIRD-PARTY-LICENSES.txt /out/THIRD-PARTY-LICENSES.txt \
 || { echo "runner/THIRD-PARTY-LICENSES.txt is not what Cargo.lock gives: run 'make runner-licences' and commit it" >&2; exit 1; }

FROM scratch AS licences-file
COPY --from=licences-generate /out/THIRD-PARTY-LICENSES.txt /
