# Tinfirm runner image for the CODEX runtime.
#
# The command is the ACP adapter `codex-acp`, speaking ACP on stdio; it starts
# `codex app-server` from the pinned @openai/codex CLI (CODEX_PATH). The base image's
# entrypoint `tinfirm-sandbox-entrypoint` prepares the sandbox and execs it.
FROM tinfirm-runner-base:local

# The versions package-lock.json pins (the build checks that they agree).
ARG CODEX_ACP_VERSION=1.13.1
# codex-acp 1.13.1 depends on @openai/codex ^0.156.1; this is the newest release in range.
ARG CODEX_VERSION=0.156.1
# The npm registry, or a mirror of it, for a host without internet access.
ARG NPM_REGISTRY=https://registry.npmjs.org/

USER root
# The packages and their versions are those of package-lock.json (generated with
# `npm install --package-lock-only --ignore-scripts` from package.json): `npm ci` installs
# exactly them, checked against the lockfile's integrity hashes, and runs no install script of
# any of them (I6 of the security review of 2026-10-27). None of them has one; the Codex
# binary comes in its platform package, which bin/codex.js picks at run time.
COPY package.json package-lock.json /opt/tinfirm/codex/
RUN cd /opt/tinfirm/codex \
 && npm ci --ignore-scripts --omit=dev --no-audit --no-fund --registry "${NPM_REGISTRY}" \
 && npm cache clean --force \
 && acp_version="$(node -p 'require("./node_modules/@agentclientprotocol/codex-acp/package.json").version')" \
 && test "${acp_version}" = "${CODEX_ACP_VERSION}" \
 && ln -s /opt/tinfirm/codex/node_modules/.bin/codex-acp /usr/local/bin/codex-acp \
 && ln -s /opt/tinfirm/codex/node_modules/.bin/codex /usr/local/bin/codex \
 && installed="$(CODEX_HOME=/tmp/codex-version codex --version | awk '{print $NF}')" \
 && echo "codex-acp ${CODEX_ACP_VERSION} with codex ${installed}" \
 && test "${installed}" = "${CODEX_VERSION}" \
 && rm -rf /tmp/codex-version /home/agent/.codex /home/agent/.npm /root/.npm \
 && chown -R agent:0 /home/agent \
 && chmod -R g=u /home/agent

# CODEX_PATH: use the pinned global CLI, not whatever codex-acp resolved for itself.
# INITIAL_AGENT_MODE: no approvals and no inner sandbox; the container is the sandbox
# (a v0 shortcut: runner/README.md, "v0 security shortcuts"). NO_BROWSER: never offer
# browser login in the sandbox.
ENV CODEX_PATH=/usr/local/bin/codex \
    CODEX_HOME=/home/agent/.codex \
    INITIAL_AGENT_MODE=agent-full-access \
    NO_BROWSER=1

LABEL dev.tinfirm.image="runtime" \
      dev.tinfirm.runtime="CODEX" \
      dev.tinfirm.runtime-version="codex ${CODEX_VERSION} / codex-acp ${CODEX_ACP_VERSION}"

USER 1000:1000
WORKDIR /workspace
ENTRYPOINT ["tinfirm-sandbox-entrypoint"]
CMD ["codex-acp"]
