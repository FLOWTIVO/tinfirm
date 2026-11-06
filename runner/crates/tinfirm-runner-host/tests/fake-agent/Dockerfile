# Test image tinfirm-runner-fake:test, built by the test harness. Same user layout as the
# real runner images (agent, uid/gid 1000, HOME /home/agent), with the fake ACP agent as
# entrypoint.
FROM node:24.19.0-trixie-slim
RUN apt-get update \
 && apt-get install -y --no-install-recommends git ca-certificates \
 && rm -rf /var/lib/apt/lists/* \
 && groupmod --new-name agent node \
 && usermod --login agent --home /home/agent --move-home node \
 && mkdir -p /workspace \
 && chown agent:agent /workspace
COPY fake-agent.mjs /opt/fake-agent/fake-agent.mjs
LABEL dev.tinfirm.image="runtime" \
      dev.tinfirm.runtime="TEST" \
      dev.tinfirm.runtime-version="fake-agent 1.0.0"
USER 1000:1000
WORKDIR /workspace
ENTRYPOINT ["node", "/opt/fake-agent/fake-agent.mjs"]
