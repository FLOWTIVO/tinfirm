# Tinfirm runner image for the CLAUDE_CODE runtime.
#
# The command is the ACP adapter `claude-agent-acp`, speaking ACP on stdio; the base image's
# entrypoint `tinfirm-sandbox-entrypoint` prepares the sandbox and execs it. The adapter
# runs on the Claude Agent SDK, whose platform package (for example
# @anthropic-ai/claude-agent-sdk-linux-arm64) contains the Claude Code native binary, so
# no separate Claude Code install is needed. claude-agent-acp pins the SDK version exactly
# and the SDK pins its platform packages exactly, so the adapter version fixes the Claude
# Code version.
#
# This image contains Claude Code. It is built on the runner host and is NEVER pushed to
# any registry.
FROM tinfirm-runner-base:local

# The adapter's version: package-lock.json pins it (and the build checks that they agree).
ARG CLAUDE_AGENT_ACP_VERSION=0.87.0
# Informational: the Claude Code version bundled by the SDK that CLAUDE_AGENT_ACP_VERSION pins.
ARG CLAUDE_CODE_VERSION=2.1.287
# The npm registry, or a mirror of it, for a host without internet access.
ARG NPM_REGISTRY=https://registry.npmjs.org/

USER root
# The packages and their versions are those of package-lock.json (generated with
# `npm install --package-lock-only --ignore-scripts` from package.json): `npm ci` installs
# exactly them, checked against the lockfile's integrity hashes, and runs no install script of
# any of them (I6 of the security review of 2026-10-27). None of them has one. package.json's
# `overrides` holds zod past 4.6.5 (CVE-2023-54404), which the adapter pins exactly.
COPY package.json package-lock.json /opt/tinfirm/claude/
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
 && test "${installed}" = "${CLAUDE_CODE_VERSION}" \
 && rm -rf /home/agent/.claude /home/agent/.claude.json /home/agent/.npm /root/.npm \
 && chown -R agent:0 /home/agent \
 && chmod -R g=u /home/agent

# Auto-update stays off: the adapter and the runtime are pinned together in this image.
ENV DISABLE_AUTOUPDATER=1 \
    CLAUDE_CODE_EXECUTABLE=/usr/local/bin/claude

LABEL dev.tinfirm.image="runtime" \
      dev.tinfirm.runtime="CLAUDE_CODE" \
      dev.tinfirm.runtime-version="claude-code ${CLAUDE_CODE_VERSION} / claude-agent-acp ${CLAUDE_AGENT_ACP_VERSION}"

USER 1000:1000
WORKDIR /workspace
ENTRYPOINT ["tinfirm-sandbox-entrypoint"]
CMD ["claude-agent-acp"]
