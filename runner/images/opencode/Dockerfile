# Tinfirm runner image for the OPENCODE runtime (ADR-0041, OpenCode 2: ADR-0061).
#
# The command is `opencode acp`, OpenCode's own ACP agent on stdio. It starts OpenCode's server
# as a child process (`opencode serve --stdio`, on 127.0.0.1) and talks to it over HTTP. The base
# image's entrypoint `tinfirm-sandbox-entrypoint` prepares the sandbox and execs it. OpenCode
# reads the provider's key from its usual variables (ANTHROPIC_API_KEY, AWS_BEARER_TOKEN_BEDROCK,
# ...) and the rest of its configuration from OPENCODE_CONFIG_CONTENT, which the runner host sets
# per run.
FROM tinfirm-runner-base:local

# OpenCode 2 is the npm package @opencode/cli; opencode-ai is OpenCode 1. package-lock.json
# pins it (the build checks that they agree).
ARG OPENCODE_VERSION=2.0.29
# The npm registry, or a mirror of it, for a host without internet access.
ARG NPM_REGISTRY=https://registry.npmjs.org/

USER root
# The packages and their versions are those of package-lock.json (generated with
# `npm install --package-lock-only --ignore-scripts` from package.json): `npm ci` installs
# exactly them, checked against the lockfile's integrity hashes, and runs no install script
# (I6 of the security review of 2026-10-27). The one package with one, @opencode/cli, links the
# native binary of this platform (an optional dependency, which npm ci installed) in place of
# its placeholder: its postinstall.mjs, from the locked tarball, runs here by name, alone.
# `--version` may print a name and a `v` before the version, as OpenCode's own installer expects.
COPY package.json package-lock.json /opt/tinfirm/opencode/
RUN cd /opt/tinfirm/opencode \
 && npm ci --ignore-scripts --omit=dev --no-audit --no-fund --registry "${NPM_REGISTRY}" \
 && (cd node_modules/@opencode/cli && node ./postinstall.mjs) \
 && npm cache clean --force \
 && ln -s /opt/tinfirm/opencode/node_modules/.bin/opencode /usr/local/bin/opencode \
 && installed="$(HOME=/tmp/opencode-version OPENCODE_DISABLE_AUTOUPDATE=1 opencode --version | tail -n 1)" \
 && installed="${installed##* }" \
 && installed="${installed#v}" \
 && echo "opencode ${installed}" \
 && test "${installed}" = "${OPENCODE_VERSION}" \
 && rm -rf /tmp/opencode-version /home/agent/.npm /root/.npm /home/agent/.config/opencode \
      /home/agent/.local/share/opencode /home/agent/.local/state/opencode \
      /home/agent/.cache/opencode \
 && chown -R agent:0 /home/agent \
 && chmod -R g=u /home/agent

# Auto-update stays off: the version is pinned in this image. OpenCode 2 cannot share a session.
# OpenCode's warnings and errors, of `opencode acp` and of its server, go to stderr, which the
# host shows as log lines of the run; stdout stays ACP's.
ENV OPENCODE_DISABLE_AUTOUPDATE=1 \
    OPENCODE_PRINT_LOGS=1 \
    OPENCODE_LOG_LEVEL=WARN

LABEL dev.tinfirm.image="runtime" \
      dev.tinfirm.runtime="OPENCODE" \
      dev.tinfirm.runtime-version="opencode ${OPENCODE_VERSION}"

USER 1000:1000
WORKDIR /workspace
ENTRYPOINT ["tinfirm-sandbox-entrypoint"]
CMD ["opencode", "acp"]
