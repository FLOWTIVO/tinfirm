# Tinfirm runner image with every runtime (ADR-0101): Claude Code, Codex, pi and OpenCode, each
# with its ACP adapter, at the versions their own images pin (the tests keep them equal).
#
# It exists for ready VMs (ADR-0098): a ready VM starts its container before any run names a
# runtime, so a pool of one image serves every agent only when that image holds them all. It has
# no command of its own: the runner host names the run's adapter in the bridge's `start`
# (`claude-agent-acp`, `codex-acp`, `tinfirm-pi-acp` or `opencode acp`, as each image's CMD).
#
# This image contains Claude Code. It is built on the runner host (or in the VM image) and is
# NEVER pushed to any registry.
FROM tinfirm-runner-base:local

ARG CLAUDE_AGENT_ACP_VERSION=0.87.0
ARG CLAUDE_CODE_VERSION=2.1.287
ARG CODEX_ACP_VERSION=1.13.1
ARG CODEX_VERSION=0.156.1
ARG PI_VERSION=1.0.4
ARG OPENCODE_VERSION=2.0.29
# The npm registry, or a mirror of it, for a host without internet access.
ARG NPM_REGISTRY=https://registry.npmjs.org/

USER root
COPY tinfirm-pi-acp.mjs /usr/local/lib/tinfirm/tinfirm-pi-acp.mjs
# The packages and their versions of each runtime are those of its image's package-lock.json
# (generated with `npm install --package-lock-only --ignore-scripts` from its package.json):
# `npm ci` installs exactly them, checked against the lockfile's integrity hashes, and runs no
# install script of any of them (I6 of the security review of 2026-10-27). The lockfiles are
# the runtimes' images' own, so the versions are theirs by construction.
# Claude Code: the adapter pins the Agent SDK, whose platform package holds the CLI.
COPY claude/package.json claude/package-lock.json /opt/tinfirm/claude/
RUN cd /opt/tinfirm/claude \
 && npm ci --ignore-scripts --omit=dev --no-audit --no-fund --registry "${NPM_REGISTRY}" \
 && npm cache clean --force \
 && acp_version="$(node -p 'require("./node_modules/@agentclientprotocol/claude-agent-acp/package.json").version')" \
 && test "${acp_version}" = "${CLAUDE_AGENT_ACP_VERSION}" \
 && ln -s /opt/tinfirm/claude/node_modules/.bin/claude-agent-acp /usr/local/bin/claude-agent-acp \
 && arch="$(dpkg --print-architecture | sed -e 's/amd64/x64/')" \
 && claude_bin="/opt/tinfirm/claude/node_modules/@anthropic-ai/claude-agent-sdk-linux-${arch}/claude" \
 && test -x "${claude_bin}" \
 && ln -s "${claude_bin}" /usr/local/bin/claude \
 && installed="$(/usr/local/bin/claude --version | awk '{print $1}')" \
 && echo "claude-agent-acp ${CLAUDE_AGENT_ACP_VERSION} bundles Claude Code ${installed}" \
 && test "${installed}" = "${CLAUDE_CODE_VERSION}"
# Codex and its adapter; the Codex binary comes in its platform package, which bin/codex.js
# picks at run time.
COPY codex/package.json codex/package-lock.json /opt/tinfirm/codex/
RUN cd /opt/tinfirm/codex \
 && npm ci --ignore-scripts --omit=dev --no-audit --no-fund --registry "${NPM_REGISTRY}" \
 && npm cache clean --force \
 && acp_version="$(node -p 'require("./node_modules/@agentclientprotocol/codex-acp/package.json").version')" \
 && test "${acp_version}" = "${CODEX_ACP_VERSION}" \
 && ln -s /opt/tinfirm/codex/node_modules/.bin/codex-acp /usr/local/bin/codex-acp \
 && ln -s /opt/tinfirm/codex/node_modules/.bin/codex /usr/local/bin/codex \
 && installed="$(CODEX_HOME=/tmp/codex-version codex --version | awk '{print $NF}')" \
 && echo "codex-acp ${CODEX_ACP_VERSION} with codex ${installed}" \
 && test "${installed}" = "${CODEX_VERSION}"
# pi, with Tinfirm's adapter; the lockfile holds undici at 8.12.3 (package.json's overrides).
COPY pi/package.json pi/package-lock.json /opt/tinfirm/pi/
RUN cd /opt/tinfirm/pi \
 && npm ci --ignore-scripts --omit=dev --no-audit --no-fund --registry "${NPM_REGISTRY}" \
 && npm cache clean --force \
 && ln -s /opt/tinfirm/pi/node_modules/.bin/pi /usr/local/bin/pi \
 && installed="$(HOME=/tmp/pi-version PI_OFFLINE=1 PI_SKIP_VERSION_CHECK=1 pi --version | awk '{print $NF}')" \
 && echo "pi ${installed} with tinfirm-pi-acp" \
 && test "${installed}" = "${PI_VERSION}" \
 && chmod 644 /usr/local/lib/tinfirm/tinfirm-pi-acp.mjs \
 && printf '#!/bin/sh\nexec node /usr/local/lib/tinfirm/tinfirm-pi-acp.mjs "$@"\n' > /usr/local/bin/tinfirm-pi-acp \
 && chmod 755 /usr/local/bin/tinfirm-pi-acp \
 && node --check /usr/local/lib/tinfirm/tinfirm-pi-acp.mjs
# OpenCode 2 (ADR-0061). The one package with an install script, @opencode/cli, links the native
# binary of this platform (an optional dependency, which npm ci installed) in place of its
# placeholder: its postinstall.mjs, from the locked tarball, runs here by name, alone.
COPY opencode/package.json opencode/package-lock.json /opt/tinfirm/opencode/
RUN cd /opt/tinfirm/opencode \
 && npm ci --ignore-scripts --omit=dev --no-audit --no-fund --registry "${NPM_REGISTRY}" \
 && (cd node_modules/@opencode/cli && node ./postinstall.mjs) \
 && npm cache clean --force \
 && ln -s /opt/tinfirm/opencode/node_modules/.bin/opencode /usr/local/bin/opencode \
 && installed="$(HOME=/tmp/opencode-version OPENCODE_DISABLE_AUTOUPDATE=1 opencode --version | tail -n 1)" \
 && installed="${installed##* }" \
 && installed="${installed#v}" \
 && echo "opencode ${installed}" \
 && test "${installed}" = "${OPENCODE_VERSION}"
# What the version checks left in the homes, then the agent's home as each image leaves it.
RUN rm -rf /tmp/codex-version /tmp/pi-version /tmp/opencode-version /root/.npm \
      /home/agent/.claude /home/agent/.claude.json /home/agent/.codex /home/agent/.pi \
      /home/agent/.npm /home/agent/.config/opencode /home/agent/.local/share/opencode \
      /home/agent/.local/state/opencode /home/agent/.cache/opencode \
 && chown -R agent:0 /home/agent \
 && chmod -R g=u /home/agent

# Each runtime's own settings, as its image sets them: no updates, no telemetry.
ENV DISABLE_AUTOUPDATER=1 \
    CLAUDE_CODE_EXECUTABLE=/usr/local/bin/claude \
    CODEX_PATH=/usr/local/bin/codex \
    CODEX_HOME=/home/agent/.codex \
    INITIAL_AGENT_MODE=agent-full-access \
    NO_BROWSER=1 \
    PI_SKIP_VERSION_CHECK=1 \
    PI_TELEMETRY=0 \
    OPENCODE_DISABLE_AUTOUPDATE=1 \
    OPENCODE_PRINT_LOGS=1 \
    OPENCODE_LOG_LEVEL=WARN

LABEL dev.tinfirm.image="runtime" \
      dev.tinfirm.runtime="MULTI" \
      dev.tinfirm.runtime-version="claude-code ${CLAUDE_CODE_VERSION} / claude-agent-acp ${CLAUDE_AGENT_ACP_VERSION}; codex ${CODEX_VERSION} / codex-acp ${CODEX_ACP_VERSION}; pi ${PI_VERSION} / tinfirm-pi-acp 1.1.0; opencode ${OPENCODE_VERSION}"

USER 1000:1000
WORKDIR /workspace
ENTRYPOINT ["tinfirm-sandbox-entrypoint"]
# No command: the host names the run's adapter.
CMD []
