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
# The Claude Code version bundled by the SDK that CLAUDE_AGENT_ACP_VERSION pins. The build
# checks the binary against its SHA-256 in claude-code.sha256 for this version, then its
# `--version`.
ARG CLAUDE_CODE_VERSION=2.1.287
# The npm registry, or a mirror of it, for a host without internet access.
ARG NPM_REGISTRY=https://registry.npmjs.org/

USER root
# The packages and their versions are those of package-lock.json (generated with
# `npm install --package-lock-only --ignore-scripts` from package.json): `npm ci` installs
# exactly them, checked against the lockfile's integrity hashes, and runs no install script of
# any of them (I6 of the security review of 2026-10-27). None of them has one. package.json's
# `overrides` holds zod past 4.6.5 (CVE-2023-54404), which the adapter pins exactly.
# The Claude Code binary is then checked a second time, against the SHA-256 that Anthropic's
# signed release manifest gives for it (claude-code.sha256, threat model Q16), before it runs.
COPY package.json package-lock.json claude-code.sha256 /opt/tinfirm/claude/
RUN cd /opt/tinfirm/claude \
 && { npm ci --ignore-scripts --omit=dev --no-audit --no-fund --registry "${NPM_REGISTRY}" \
      || { echo "ERROR: npm ci failed. An EINTEGRITY error above means a downloaded package is not the one package-lock.json locks: do not use that registry or mirror (Q16)." >&2; exit 1; }; } \
 && tinfirm-npm-check \
 && npm cache clean --force \
 && acp_version="$(node -p 'require("./node_modules/@agentclientprotocol/claude-agent-acp/package.json").version')" \
 && test "${acp_version}" = "${CLAUDE_AGENT_ACP_VERSION}" \
 && ln -s /opt/tinfirm/claude/node_modules/.bin/claude-agent-acp /usr/local/bin/claude-agent-acp \
 && arch="$(dpkg --print-architecture | sed -e 's/amd64/x64/')" \
 && claude_bin="/opt/tinfirm/claude/node_modules/@anthropic-ai/claude-agent-sdk-linux-${arch}/claude" \
 && test -x "${claude_bin}" \
 && expected="$(awk -v v="${CLAUDE_CODE_VERSION}" -v p="linux-${arch}" '!/^#/ && $2 == v && $3 == p { print $1 }' /opt/tinfirm/claude/claude-code.sha256)" \
 && if [ -z "${expected}" ]; then echo "ERROR: claude-code.sha256 pins no SHA-256 for Claude Code ${CLAUDE_CODE_VERSION} on linux-${arch}: add it from Anthropic's signed manifest (runner/README.md, Updating Claude Code)." >&2; exit 1; fi \
 && actual="$(sha256sum "${claude_bin}" | cut -d ' ' -f 1)" \
 && if [ "${actual}" != "${expected}" ]; then echo "ERROR: the Claude Code binary ${claude_bin} is not the pinned one: SHA-256 ${actual}, claude-code.sha256 pins ${expected} for ${CLAUDE_CODE_VERSION} on linux-${arch}. Do not use this download (Q16)." >&2; exit 1; fi \
 && echo "Claude Code ${CLAUDE_CODE_VERSION} linux-${arch}: SHA-256 ${actual}, as pinned" \
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
