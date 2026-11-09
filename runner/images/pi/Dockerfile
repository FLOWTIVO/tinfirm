# Tinfirm runner image for the PI runtime (ADR-0041).
#
# The command is `tinfirm-pi-acp`, Tinfirm's ACP adapter for pi, speaking ACP on stdio; it starts
# `pi --mode rpc` from the pinned @earendil-works/pi-coding-agent for each session. The base
# image's entrypoint `tinfirm-sandbox-entrypoint` prepares the sandbox and execs it. pi reads the
# provider's key from its usual variables (ANTHROPIC_API_KEY, AWS_BEARER_TOKEN_BEDROCK, ...), which
# the runner host sets per run.
FROM tinfirm-runner-base:local

# The version package-lock.json pins (the build checks that they agree).
ARG PI_VERSION=1.0.4
# The npm registry, or a mirror of it, for a host without internet access.
ARG NPM_REGISTRY=https://registry.npmjs.org/

USER root
COPY tinfirm-pi-acp.mjs /usr/local/lib/tinfirm/tinfirm-pi-acp.mjs
# The packages and their versions are those of package-lock.json (generated with npm 11's
# `npm install --package-lock-only --ignore-scripts` from package.json): `npm ci` installs
# exactly them, checked against the lockfile's integrity hashes, and runs no install script, as
# pi's own install instructions do: of the two in its tree, @google/genai's preinstall is a no-op
# and protobufjs's postinstall only warns about its dependents' version ranges. pi takes its
# sibling packages (@earendil-works/pi-ai, pi-agent-core, pi-tui and the others) with ^1.0.4:
# the lockfile holds the newest in range when it was generated, 1.5.0. pi pins undici 8.10.2
# exactly, which GHSA-2m5v-pch6-74hx and other advisories name (fixed in 8.12.2); package.json's
# overrides moves it to 8.12.3, the version pi 1.5.0 pins.
COPY package.json package-lock.json /opt/tinfirm/pi/
RUN cd /opt/tinfirm/pi \
 && { npm ci --ignore-scripts --omit=dev --no-audit --no-fund --registry "${NPM_REGISTRY}" \
      || { echo "ERROR: npm ci failed. An EINTEGRITY error above means a downloaded package is not the one package-lock.json locks: do not use that registry or mirror (Q16)." >&2; exit 1; }; } \
 && tinfirm-npm-check \
 && npm cache clean --force \
 && ln -s /opt/tinfirm/pi/node_modules/.bin/pi /usr/local/bin/pi \
 && installed="$(HOME=/tmp/pi-version PI_OFFLINE=1 PI_SKIP_VERSION_CHECK=1 pi --version | awk '{print $NF}')" \
 && echo "pi ${installed} with tinfirm-pi-acp" \
 && test "${installed}" = "${PI_VERSION}" \
 && chmod 644 /usr/local/lib/tinfirm/tinfirm-pi-acp.mjs \
 && printf '#!/bin/sh\nexec node /usr/local/lib/tinfirm/tinfirm-pi-acp.mjs "$@"\n' > /usr/local/bin/tinfirm-pi-acp \
 && chmod 755 /usr/local/bin/tinfirm-pi-acp \
 && node --check /usr/local/lib/tinfirm/tinfirm-pi-acp.mjs \
 && rm -rf /tmp/pi-version /home/agent/.pi /home/agent/.npm /root/.npm \
 && chown -R agent:0 /home/agent \
 && chmod -R g=u /home/agent

# No version check and no telemetry. pi refreshes its model catalog unless PI_OFFLINE is set,
# which the runner host does for a gateway (as it keeps Claude Code to the gateway).
ENV PI_SKIP_VERSION_CHECK=1 \
    PI_TELEMETRY=0

LABEL dev.tinfirm.image="runtime" \
      dev.tinfirm.runtime="PI" \
      dev.tinfirm.runtime-version="pi ${PI_VERSION} / tinfirm-pi-acp 1.1.0"

USER 1000:1000
WORKDIR /workspace
ENTRYPOINT ["tinfirm-sandbox-entrypoint"]
CMD ["tinfirm-pi-acp"]
