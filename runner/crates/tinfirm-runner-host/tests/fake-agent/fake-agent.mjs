#!/usr/bin/env node
// Fake ACP agent for the runner host tests. No dependencies; newline-delimited JSON-RPC
// on stdio, like claude-agent-acp and codex-acp.
//
// The behaviour is chosen by a marker in the prompt text: `[scenario:<name>]`. The session's
// cwd is the workspace root; the repositories are its subdirectories with a `.git`, and the
// single-repository scenarios work in the first one (by name).
//   happy              thought, plan, permission-gated edit, git commit, usage + rate
//                      limit meta, a streamed final message, an uncommitted file (WIP)
//   slow               writes slow.txt, then streams a chunk every 100 ms until cancelled
//   gate               streams a chunk every 100 ms until the file `release` appears in the
//                      workspace root (the test creates it), then ends the turn; or until
//                      cancelled
//   loop               the same tool call over and over until cancelled
//   many-tools         distinct tool calls until cancelled
//   leak               prints the credential from its environment (raw and base64): a subscription
//                      token or a provider's own key; a gateway's run has only the model proxy's
//                      dummy key there
//   model-env          reports the model variables of its environment and the length of its key,
//                      never a key itself (a gateway's run: the model proxy's dummy key), and the
//                      value the host set for the session's `effort` option
//   model-proxy        reports the port and host of its model base URL and its key (the model proxy's dummy,
//                      which every gateway's run gets; a real key would be a leak), then sends that
//                      key to a model endpoint (`/v1/messages`) and to one outside the model API
//                      (`/key/generate`) there, and reports each status
//   leak-commit        commits a file containing the credential, prints nothing
//   usage-limit        Claude-style `_claude/rateLimit` rejected, then waits for cancel
//   codex-usage-limit  Codex-style usageLimitExceeded error on the prompt
//   codex-usage        several requests with per-request usage (codex-acp semantics)
//   turn-usage         one usage_update with the session's cost after the turn, and the turn's
//                      usage in the prompt response (`opencode acp` semantics)
//   auth               authentication required error
//   steer              reports `Prompt had [done].` and ends the turn when the prompt holds
//                      `[done]`; else streams a chunk every 50 ms, reports every steered message
//                      (`Steered: <text>`) and ends the turn after one that holds `[done]`; or
//                      until cancelled
//   crash              exits in the middle of the turn
//   huge               oversized tool input, diffs, plan and message (frame limits)
//   hang               one message, then silence: never answers, ignores session/cancel
//   long-tool          one tool call running ~3 s that sends progress updates every 300 ms
//   refusal            the model refuses (stopReason: refusal)
//   fetch              prints `origin`, waits for a new commit on main (git fetch through the
//                      git proxy), reads its file, tries to push, and leaves an uncommitted file
//   multi              lists the repositories, prints each `origin` and whether it has
//                      alternates, then applies the `[commit:<dir>]` and `[wip:<dir>]` markers
//   forge-remote-ref   commits the credential in the first repository, points the forged
//                      `refs/remotes/origin/x` and `origin/main` at it, commits a clean change on
//                      top, then applies the `[commit:<dir>]` markers
//   rebase             fetches `main` and rebases the task branch onto `origin/main` (rewriting
//                      commits that are already on the remote branch), applies the
//                      `[commit:<dir>]` markers, and with `[wait]` then waits like `gate` until
//                      the file `release` appears; prints the branch's subjects
//   request-repo       talks JSON-RPC to the `tinfirm` MCP server from session/new or
//                      session/load: initialize, tools/list, list_repositories, one
//                      request_repository per `[request:<name>]` marker, list_repositories again,
//                      then one create_task per `[task:<title>]` marker, then writes the file
//                      `<name>` in the workspace root and calls upload_attachment for it, per
//                      `[upload:<name>]` marker (`<name>` may also be a path such as `/etc/hostname`,
//                      which is then not written), then one ask_human per `[ask:<question>]`
//                      marker (options `Yes` and `No`), then one finish_run per
//                      `[finish:<outcome>]` marker
//   platform-v2        the protocol v2 platform tools over MCP: initialize, tools/list,
//                      list_repositories, one request_access per `[access:<repository>]` and
//                      `[access-tool:<connection>]` marker, one call per `[tool:<name>]` marker
//                      (arguments `{key: "SHOP-1"}`), one save_artifact per `[link:<url>]`
//                      marker (title `Link`) and per `[save:<name>]` marker (the file is written
//                      to the workspace root first), a file `<path>` under TINFIRM_OUTPUT_DIR
//                      per `[output:<path>]` marker (content `Output <path>.`) and one holding
//                      the credential of its environment per `[output-secret:<path>]` marker,
//                      then one ask per `[ask:<question>]` marker (options `Yes`, `No`, `yes`)
//                      and one finish per `[finish:<outcome>]` marker; then a call of the v1
//                      name `ask_human`, which v2 does not have
//   egress             opens a `CONNECT` to the `[target:<host:port>]` marker through its
//                      HTTPS_PROXY (with the proxy's credential), sends a line there and reports
//                      the proxy's status and the line that comes back
//   skills             lists the skills in `~/.claude/skills` and `~/.agents/skills`, each with
//                      the `name:` line of its SKILL.md and whether its folder is read-only,
//                      then writes a file of its own in `~/.claude`, as a runtime saves its
//                      session there, and says whether it could
//
// Every session offers an `effort` config option (category `thought_level`) with the values
// `default`, `low`, `medium`, `high` and `max`, as OpenCode does for a model without `xhigh`;
// `session/set_config_option` stores the value.
//
// `--no-mcp-http` (or FAKE_AGENT_NO_MCP_HTTP=1) makes the agent say it cannot use MCP over HTTP.
// `--no-load-session` makes it say it has no session/load (`agentCapabilities.loadSession` false);
// `--fail-load-session` makes session/load of a session it has fail with an internal error.
// `--steering` makes it advertise the steering extension (`_meta.steering.supported`) and take
// `_session/steering` into the running turn (`injected`), or answer `promptRequired` without one.
// `--agent-version=<version>` sets the version that `initialize` names (`agentInfo.version`).
import { createInterface } from "node:readline";
import { execFileSync } from "node:child_process";
import { randomUUID } from "node:crypto";
import fs from "node:fs";
import net from "node:net";
import os from "node:os";
import path from "node:path";

// The access token of a Claude login (ADR-0085): Claude Code's `.credentials.json` in
// `CLAUDE_CONFIG_DIR` (the login's directory under the local executor; `~/.claude` in a
// sandbox, where the host mounts the file). The host never reads it; this agent does, to leak
// it, as a prompt-injected one would.
function claudeLoginToken() {
  const dir = process.env.CLAUDE_CONFIG_DIR ?? path.join(os.homedir(), ".claude");
  try {
    const parsed = JSON.parse(fs.readFileSync(path.join(dir, ".credentials.json"), "utf8"));
    return parsed?.claudeAiOauth?.accessToken ?? "";
  } catch {
    return "";
  }
}

// What a run paid by a credential of the host can leak: a Claude login's token, else a key
// of the environment.
function hostSecret() {
  return (
    process.env.CLAUDE_CODE_OAUTH_TOKEN ||
    claudeLoginToken() ||
    process.env.ANTHROPIC_API_KEY ||
    process.env.ANTHROPIC_AUTH_TOKEN ||
    process.env.OPENAI_API_KEY ||
    ""
  );
}

// Where Claude Code keeps its sessions, so that a session bundle (ADR-0097) carries them.
const sessionsDir = path.join(os.homedir(), ".claude", "projects", "fake-agent");
const mcpHttp = !(process.argv.includes("--no-mcp-http") || process.env.FAKE_AGENT_NO_MCP_HTTP === "1");
const loadSessionSupported = !process.argv.includes("--no-load-session");
const failLoadSession = process.argv.includes("--fail-load-session");
const steering = process.argv.includes("--steering");
const agentVersion =
  process.argv.find((arg) => arg.startsWith("--agent-version="))?.slice("--agent-version=".length) ?? "1.0.0";
let nextId = 1;
const pending = new Map();
let current = null;

const send = (msg) => process.stdout.write(JSON.stringify({ jsonrpc: "2.0", ...msg }) + "\n");
const notify = (sessionId, update) => send({ method: "session/update", params: { sessionId, update } });
const request = (method, params) => {
  const id = nextId++;
  send({ id, method, params });
  return new Promise((resolve) => pending.set(id, resolve));
};
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const chunk = (sessionId, text) =>
  notify(sessionId, { sessionUpdate: "agent_message_chunk", content: { type: "text", text } });

// The session's config options, as session/new, session/load and session/set_config_option
// return them.
function configOptions(session) {
  return [
    {
      id: "effort",
      name: "Effort",
      category: "thought_level",
      type: "select",
      currentValue: session.effort ?? "default",
      options: ["default", "low", "medium", "high", "max"].map((value) => ({ value, name: value })),
    },
  ];
}

function saveSession(id, data) {
  fs.mkdirSync(sessionsDir, { recursive: true });
  fs.writeFileSync(path.join(sessionsDir, `${id}.json`), JSON.stringify(data));
}

function loadSession(id) {
  try {
    return JSON.parse(fs.readFileSync(path.join(sessionsDir, `${id}.json`), "utf8"));
  } catch {
    return null;
  }
}

const rl = createInterface({ input: process.stdin });
rl.on("line", (line) => {
  let msg;
  try {
    msg = JSON.parse(line);
  } catch {
    return;
  }
  handle(msg).catch((e) => console.error("fake-agent error:", e));
});
rl.on("close", () => process.exit(0));
console.error("fake-agent started");

async function handle(msg) {
  if (msg.id !== undefined && msg.method === undefined) {
    const resolve = pending.get(msg.id);
    pending.delete(msg.id);
    resolve?.(msg);
    return;
  }
  switch (msg.method) {
    case "initialize":
      return send({
        id: msg.id,
        result: {
          protocolVersion: 1,
          agentCapabilities: { loadSession: loadSessionSupported, mcpCapabilities: { http: mcpHttp, sse: false } },
          authMethods: [],
          agentInfo: { name: "fake-agent", version: agentVersion },
          ...(steering ? { _meta: { steering: { supported: true } } } : {}),
        },
      });
    case "session/new": {
      const sessionId = `fake-${randomUUID()}`;
      const session = {
        cwd: msg.params.cwd,
        history: [],
        mcpServers: msg.params.mcpServers ?? [],
        mcpFrom: "session/new",
      };
      saveSession(sessionId, session);
      return send({ id: msg.id, result: { sessionId, configOptions: configOptions(session) } });
    }
    case "session/load": {
      const session = loadSession(msg.params.sessionId);
      if (!session) {
        return send({ id: msg.id, error: { code: -32002, message: "Resource not found" } });
      }
      if (failLoadSession) {
        return send({ id: msg.id, error: { code: -32603, message: "Internal error: the session file is corrupt" } });
      }
      // ACP: the agent replays the conversation before answering session/load.
      for (const text of session.history) chunk(msg.params.sessionId, text);
      session.cwd = msg.params.cwd;
      session.mcpServers = msg.params.mcpServers ?? [];
      session.mcpFrom = "session/load";
      saveSession(msg.params.sessionId, session);
      return send({ id: msg.id, result: { configOptions: configOptions(session) } });
    }
    case "session/set_config_option": {
      const session = loadSession(msg.params.sessionId);
      const option = configOptions(session ?? {}).find((o) => o.id === msg.params.configId);
      if (!session || !option || !option.options.some((o) => o.value === msg.params.value)) {
        return send({ id: msg.id, error: { code: -32602, message: "Invalid params" } });
      }
      session.effort = msg.params.value;
      saveSession(msg.params.sessionId, session);
      return send({ id: msg.id, result: { configOptions: configOptions(session) } });
    }
    case "session/prompt":
      return prompt(msg);
    case "_session/steering": {
      if (!steering) return send({ id: msg.id, error: { code: -32601, message: "Method not found" } });
      if (!current || current.sessionId !== msg.params.sessionId) {
        return send({ id: msg.id, result: { outcome: "promptRequired", reason: "noRunningTurn" } });
      }
      current.steers.push(msg.params.prompt.map((b) => b.text ?? "").join(""));
      return send({ id: msg.id, result: { outcome: "injected" } });
    }
    case "session/cancel":
      if (current && current.sessionId === msg.params.sessionId) {
        current.cancelled = true;
        current.onCancel();
      }
      return;
    default:
      if (msg.id !== undefined) send({ id: msg.id, error: { code: -32601, message: "Method not found" } });
  }
}

function git(cwd, ...args) {
  return execFileSync("git", args, { cwd, encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] });
}

// The repositories under the workspace root, sorted by directory name.
function repoDirs(root) {
  try {
    return fs
      .readdirSync(root, { withFileTypes: true })
      .filter((d) => d.isDirectory() && fs.existsSync(path.join(root, d.name, ".git")))
      .map((d) => d.name)
      .sort();
  } catch {
    return [];
  }
}

const markers = (text, name) =>
  [...text.matchAll(/\[([a-z-]+):([^\]]+)\]/g)].filter((m) => m[1] === name).map((m) => m[2]);

// `[commit:<dir>]`: commit a file in that repository; `[wip:<dir>]`: leave one uncommitted.
function applyRepoMarkers(text, root) {
  for (const dir of markers(text, "commit")) {
    const repo = path.join(root, dir);
    fs.writeFileSync(path.join(repo, `${dir}.txt`), `change in ${dir}\n`);
    git(repo, "add", `${dir}.txt`);
    git(repo, "commit", "-q", "-m", `Change ${dir}`);
  }
  for (const dir of markers(text, "wip")) {
    fs.writeFileSync(path.join(root, dir, "wip.txt"), `work in progress in ${dir}\n`);
  }
}

// One JSON-RPC message to an MCP server over Streamable HTTP; `id` undefined = notification.
async function mcpCall(server, method, params, id) {
  const headers = { "content-type": "application/json", accept: "application/json, text/event-stream" };
  for (const h of server.headers ?? []) headers[h.name] = h.value;
  const body = { jsonrpc: "2.0", method, ...(params === undefined ? {} : { params }), ...(id === undefined ? {} : { id }) };
  const response = await fetch(server.url, { method: "POST", headers, body: JSON.stringify(body) });
  const text = await response.text();
  return { status: response.status, body: text ? JSON.parse(text) : null };
}

async function toolCall(sessionId, id, title, kind, rawInput, extra = {}) {
  notify(sessionId, { sessionUpdate: "tool_call", toolCallId: id, title, kind, status: "pending", rawInput, ...extra });
}

async function prompt(msg) {
  const sessionId = msg.params.sessionId;
  const text = msg.params.prompt.map((b) => b.text ?? "").join("");
  const scenario = (text.match(/\[scenario:([a-z0-9-]+)\]/) || [])[1] || "happy";
  const session = loadSession(sessionId) ?? { cwd: process.cwd(), history: [] };
  const root = session.cwd;
  // Single-repository scenarios work in the first repository.
  const cwd = repoDirs(root).length > 0 ? path.join(root, repoDirs(root)[0]) : root;
  let onCancel;
  const cancelled = new Promise((resolve) => (onCancel = resolve));
  current = { sessionId, cancelled: false, onCancel, steers: [] };
  const done = (result) => {
    current = null;
    send({ id: msg.id, result });
  };
  const fail = (error) => {
    current = null;
    send({ id: msg.id, error });
  };
  const waitCancel = async (ms) => {
    const winner = await Promise.race([cancelled.then(() => "cancel"), sleep(ms).then(() => "timeout")]);
    return winner === "cancel";
  };

  chunk(sessionId, `Received: ${text.split("\n")[0].slice(0, 200)}`);
  session.history.push(`Earlier answer to: ${text.split("\n")[0].slice(0, 80)}`);
  saveSession(sessionId, session);

  switch (scenario) {
    case "attachments": {
      // Lists the files in TINFIRM_ATTACHMENTS_DIR with their content, and checks they are read-only.
      const dir = process.env.TINFIRM_ATTACHMENTS_DIR;
      if (!dir) {
        chunk(sessionId, "no attachments dir");
        return done({ stopReason: "end_turn" });
      }
      const names = fs.readdirSync(dir).sort();
      const listing = names.map((name) => {
        const file = path.join(dir, name);
        const mode = (fs.statSync(file).mode & 0o777).toString(8);
        return `${name}=${fs.readFileSync(file, "utf8")} (${mode})`;
      });
      chunk(sessionId, `attachments: ${listing.join("; ")}`);
      return done({ stopReason: "end_turn" });
    }
    case "skills": {
      const home = os.homedir();
      const listing = [];
      for (const relative of [".claude/skills", ".agents/skills"]) {
        const dir = path.join(home, relative);
        if (!fs.existsSync(dir)) continue;
        for (const name of fs.readdirSync(dir).sort()) {
          const folder = path.join(dir, name);
          let nameLine;
          try {
            const text = fs.readFileSync(path.join(folder, "SKILL.md"), "utf8");
            nameLine = text.split("\n").find((line) => line.startsWith("name:")) ?? "no name";
          } catch (e) {
            nameLine = `no SKILL.md (${e.code})`;
          }
          let access = "writable";
          try {
            fs.writeFileSync(path.join(folder, ".probe"), "x");
          } catch (e) {
            access = e.code === "EROFS" ? "read-only" : `not writable (${e.code})`;
          }
          listing.push(`${relative}/${name}: ${nameLine}, ${access}`);
        }
      }
      let own = "home writable";
      try {
        fs.mkdirSync(path.join(home, ".claude"), { recursive: true });
        fs.writeFileSync(path.join(home, ".claude", "fake-session.json"), "{}");
      } catch (e) {
        own = `home not writable (${e.code})`;
      }
      chunk(sessionId, `skills: [${listing.join("; ")}]; ${own}`);
      return done({ stopReason: "end_turn" });
    }
    case "happy": {
      notify(sessionId, { sessionUpdate: "agent_thought_chunk", content: { type: "text", text: "Planning the change." } });
      notify(sessionId, {
        sessionUpdate: "plan",
        entries: [
          { content: "Write hello.txt", status: "in_progress", priority: "high" },
          { content: "Commit it", status: "pending", priority: "medium" },
        ],
      });
      const file = path.join(cwd, "hello.txt");
      const content = "hello from the fake agent\n";
      await toolCall(sessionId, "t1", "Write hello.txt", "edit", { path: "hello.txt", content }, { locations: [{ path: file }] });
      const answer = await request("session/request_permission", {
        sessionId,
        toolCall: { toolCallId: "t1", title: "Write hello.txt" },
        options: [
          { optionId: "reject", name: "Reject", kind: "reject_once" },
          { optionId: "always", name: "Always allow", kind: "allow_always" },
          { optionId: "allow", name: "Allow", kind: "allow_once" },
        ],
      });
      if (answer.result?.outcome?.optionId !== "allow") {
        notify(sessionId, { sessionUpdate: "tool_call_update", toolCallId: "t1", status: "failed" });
        return done({ stopReason: "end_turn" });
      }
      fs.writeFileSync(file, content);
      notify(sessionId, {
        sessionUpdate: "tool_call_update",
        toolCallId: "t1",
        status: "completed",
        content: [{ type: "diff", path: file, oldText: null, newText: content }],
      });
      await toolCall(sessionId, "t2", "git commit", "execute", { command: "git add hello.txt && git commit -m 'Add hello.txt'" });
      git(cwd, "add", "hello.txt");
      const out = git(cwd, "commit", "-q", "-m", "Add hello.txt");
      notify(sessionId, {
        sessionUpdate: "tool_call_update",
        toolCallId: "t2",
        status: "completed",
        content: [{ type: "content", content: { type: "text", text: `committed ${out}`.trim() } }],
      });
      fs.writeFileSync(path.join(cwd, "notes.txt"), "uncommitted notes\n");
      notify(sessionId, {
        sessionUpdate: "usage_update",
        used: 1234,
        size: 200000,
        cost: { amount: 0.01, currency: "USD" },
        _meta: {
          "_claude/rateLimit": {
            status: "allowed",
            utilization: 0.25,
            resetsAt: Math.floor(Date.now() / 1000) + 3600,
            rateLimitType: "five_hour",
          },
        },
      });
      const parts = ["Done. ", "I created ", "hello.txt ", "and committed ", "it. ", "Notes are ", "left ", "uncommitted."];
      for (const part of parts) {
        chunk(sessionId, part);
        await sleep(40);
      }
      return done({
        stopReason: "end_turn",
        usage: { inputTokens: 120, outputTokens: 45, cachedReadTokens: 30, cachedWriteTokens: 15, totalTokens: 210 },
      });
    }
    case "slow": {
      fs.writeFileSync(path.join(cwd, "slow.txt"), "work in progress\n");
      for (let i = 0; ; i++) {
        chunk(sessionId, `tick ${i} `);
        if (await waitCancel(100)) return done({ stopReason: "cancelled" });
      }
    }
    case "steer": {
      if (text.includes("[done]")) {
        chunk(sessionId, "Prompt had [done].");
        return done({ stopReason: "end_turn" });
      }
      for (let i = 0; ; i++) {
        while (current.steers.length > 0) {
          const steered = current.steers.shift();
          chunk(sessionId, `Steered: ${steered}`);
          if (steered.includes("[done]")) return done({ stopReason: "end_turn" });
        }
        chunk(sessionId, `steer ${i} `);
        if (await waitCancel(50)) return done({ stopReason: "cancelled" });
      }
    }
    case "gate": {
      for (let i = 0; ; i++) {
        if (fs.existsSync(path.join(root, "release"))) {
          chunk(sessionId, "Released.");
          return done({ stopReason: "end_turn" });
        }
        chunk(sessionId, `gate ${i} `);
        if (await waitCancel(100)) return done({ stopReason: "cancelled" });
      }
    }
    case "loop": {
      for (let i = 0; ; i++) {
        await toolCall(sessionId, `loop-${i}`, "Read file.txt", "read", { path: "file.txt" });
        notify(sessionId, { sessionUpdate: "tool_call_update", toolCallId: `loop-${i}`, status: "completed" });
        if (await waitCancel(30)) return done({ stopReason: "cancelled" });
      }
    }
    case "many-tools": {
      for (let i = 0; ; i++) {
        await toolCall(sessionId, `tool-${i}`, `Step ${i}`, "execute", { command: `echo ${i}` });
        if (await waitCancel(30)) return done({ stopReason: "cancelled" });
      }
    }
    case "leak": {
      // A gateway's run has only the model proxy's dummy key, which is no leak; a provider's own
      // key (ANTHROPIC_API_KEY, OPENAI_API_KEY) is in the environment.
      const secret = hostSecret();
      await toolCall(sessionId, "env", "env", "execute", { command: "env" });
      notify(sessionId, {
        sessionUpdate: "tool_call_update",
        toolCallId: "env",
        status: "completed",
        content: [{ type: "content", content: { type: "text", text: `HOME=/home/agent\nCLAUDE_CODE_OAUTH_TOKEN=${secret}\n` } }],
      });
      chunk(sessionId, `The token in base64 is ${Buffer.from(`x-access-token:${secret}`).toString("base64")}.`);
      if (await waitCancel(10000)) return done({ stopReason: "cancelled" });
      return done({ stopReason: "end_turn" });
    }
    case "bridge-env": {
      // What the agent sees over the sandbox bridge (ADR-0095): the run's credential and
      // settings, none of the bridge's own, and a parent (the bridge) that holds no credential.
      const env = process.env;
      // The run's credential: a Claude login's token (ADR-0085), else a key of the environment.
      const secret = hostSecret();
      let parentHasToken = "unknown";
      let parent = "unknown";
      try {
        const fs = await import("node:fs");
        const environ = fs.readFileSync(`/proc/${process.ppid}/environ`, "latin1");
        parentHasToken = secret && environ.includes(secret) ? "yes" : "no";
        const cmdline = fs.readFileSync(`/proc/${process.ppid}/cmdline`, "latin1").split("\0");
        parent = cmdline[1] === "bridge" ? "bridge" : "other";
      } catch {}
      const report = [
        `token=${secret ? "set" : "-"}`,
        `run=${env.TINFIRM_RUN_ID ? "set" : "-"}`,
        `bridge-vars=${Object.keys(env).filter((k) => k.startsWith("TINFIRM_BRIDGE_")).length}`,
        `parent=${parent}`,
        `parent-has-token=${parentHasToken}`,
        `home=${env.HOME === process.cwd() ? "cwd" : "own"}`,
        // The adapter the host named, for the multi-runtime image (the tests' wrappers set it).
        `adapter=${env.FAKE_ADAPTER ?? "-"}`,
      ];
      chunk(sessionId, report.join(" "));
      return done({ stopReason: "end_turn" });
    }
    case "model-env": {
      const env = process.env;
      const set = (name) => `${name}=${env[name] ? "set" : "-"}`;
      const value = (name) => `${name}=${env[name] ?? "-"}`;
      // The length tells a gateway's run, which has the model proxy's dummy key, from one with a
      // real key.
      const key =
        env.ANTHROPIC_AUTH_TOKEN ??
        env.ANTHROPIC_API_KEY ??
        env.OPENAI_API_KEY ??
        env.AWS_SECRET_ACCESS_KEY ??
        env.OPENROUTER_API_KEY ??
        "";
      const report = [
        value("ANTHROPIC_BASE_URL"),
        value("OPENAI_BASE_URL"),
        set("ANTHROPIC_AUTH_TOKEN"),
        set("OPENAI_API_KEY"),
        set("ANTHROPIC_API_KEY"),
        set("CLAUDE_CODE_OAUTH_TOKEN"),
        set("CLAUDE_CONFIG_DIR"),
        value("DEFAULT_AUTH_REQUEST"),
        value("CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC"),
        value("CODEX_CONFIG"),
        // pi and OpenCode (ADR-0041), and provider keys (ADR-0042).
        value("TINFIRM_MODEL_PROVIDER"),
        value("TINFIRM_MODEL"),
        value("TINFIRM_MODEL_BASE_URL"),
        value("OPENCODE_CONFIG_CONTENT"),
        value("AWS_REGION"),
        value("AWS_ACCESS_KEY_ID"),
        set("AWS_SECRET_ACCESS_KEY"),
        set("AWS_BEARER_TOKEN_BEDROCK"),
        set("OPENROUTER_API_KEY"),
        value("CLAUDE_CODE_USE_BEDROCK"),
        value("CLAUDE_CODE_EFFORT_LEVEL"),
        value("TINFIRM_THINKING_LEVEL"),
        `effort-option=${session.effort ?? "-"}`,
        `key-length=${key.length}`,
      ];
      chunk(sessionId, report.join(" "));
      return done({ stopReason: "end_turn" });
    }
    case "model-proxy": {
      const env = process.env;
      const base = env.ANTHROPIC_BASE_URL ?? env.TINFIRM_MODEL_BASE_URL ?? env.OPENAI_BASE_URL ?? "";
      const key = env.ANTHROPIC_AUTH_TOKEN ?? env.OPENAI_API_KEY ?? "";
      const ask = async (method, endpoint) => {
        try {
          const response = await fetch(`${base}${endpoint}`, {
            method,
            headers: { authorization: `Bearer ${key}`, "x-api-key": key, "content-type": "application/json" },
            body: "{}",
          });
          await response.text();
          return String(response.status);
        } catch {
          return "unreachable";
        }
      };
      let port = "-";
      let host = "-";
      try {
        port = new URL(base).port || "-";
        host = new URL(base).hostname || "-";
      } catch {}
      const report = [
        `model-port=${port}`,
        `model-host=${host}`,
        `model-key=${key}`,
        `messages=${await ask("POST", "/v1/messages")}`,
        `keys=${await ask("POST", "/key/generate")}`,
      ];
      chunk(sessionId, report.join(" "));
      return done({ stopReason: "end_turn" });
    }
    case "leak-commit": {
      const secret = hostSecret();
      fs.writeFileSync(path.join(cwd, "config.env"), `TOKEN=${secret}\n`);
      git(cwd, "add", "config.env");
      git(cwd, "commit", "-q", "-m", "Add config");
      chunk(sessionId, "Committed the config file.");
      return done({ stopReason: "end_turn" });
    }
    case "usage-limit": {
      const resetsAt = Math.floor(Date.now() / 1000) + 7200;
      notify(sessionId, {
        sessionUpdate: "usage_update",
        used: 5000,
        size: 200000,
        _meta: { "_claude/rateLimit": { status: "rejected", resetsAt, rateLimitType: "five_hour", utilization: 1 } },
      });
      chunk(sessionId, "You've hit your limit · resets 5pm");
      if (await waitCancel(10000)) return done({ stopReason: "cancelled" });
      return fail({ code: -32603, message: "Internal error", data: { message: "You've hit your limit" } });
    }
    case "codex-usage-limit": {
      chunk(sessionId, "Working on it.");
      await sleep(50);
      return fail({
        code: -32603,
        message: "Internal error",
        data: {
          message: "You've hit your usage limit. Upgrade to Pro or try again in 2 hours 30 minutes.",
          codexErrorInfo: "usageLimitExceeded",
        },
      });
    }
    case "codex-usage": {
      // Three model requests; usage_update.used is each request's total (codex-acp).
      const totals = [1000, 2000, 4000];
      for (let i = 0; i < totals.length; i++) {
        chunk(sessionId, `request ${i} `);
        await sleep(20);
        notify(sessionId, { sessionUpdate: "usage_update", used: totals[i], size: 272000 });
        await sleep(20);
      }
      // PromptResponse.usage holds only the last request (codex-acp #447).
      return done({
        stopReason: "end_turn",
        usage: { inputTokens: 1000, cachedReadTokens: 2600, outputTokens: 400, totalTokens: 4000 },
      });
    }
    case "turn-usage": {
      chunk(sessionId, "two model requests ");
      await sleep(20);
      notify(sessionId, {
        sessionUpdate: "usage_update",
        used: 5000,
        size: 200000,
        cost: { amount: 0.25, currency: "USD" },
      });
      return done({
        stopReason: "end_turn",
        usage: { inputTokens: 300, outputTokens: 200, cachedReadTokens: 4400, cachedWriteTokens: 100, totalTokens: 5000 },
      });
    }
    case "huge": {
      const input = {};
      for (let i = 0; i < 500; i++) input[`k${i}`] = "v".repeat(5000);
      await toolCall(sessionId, "h1", "Huge edit", "edit", input);
      notify(sessionId, {
        sessionUpdate: "tool_call_update",
        toolCallId: "h1",
        status: "completed",
        content: Array.from({ length: 100 }, (_, i) => ({
          type: "diff",
          path: `/workspace/f${i}.txt`,
          oldText: "o".repeat(20000),
          newText: "n".repeat(20000),
        })),
      });
      notify(sessionId, {
        sessionUpdate: "plan",
        entries: Array.from({ length: 5000 }, (_, i) => ({ content: `${"step ".repeat(60)}${i}`, status: "pending" })),
      });
      chunk(sessionId, "x".repeat(300000));
      return done({ stopReason: "end_turn" });
    }
    case "hang": {
      fs.writeFileSync(path.join(cwd, "partial.txt"), "started before hanging\n");
      // Stuck: no more messages, no prompt response, cancel is ignored.
      current = null;
      return;
    }
    case "long-tool": {
      await toolCall(sessionId, "long", "npm test", "execute", { command: "npm test" });
      for (let i = 0; i < 10; i++) {
        await sleep(300);
        notify(sessionId, {
          sessionUpdate: "tool_call_update",
          toolCallId: "long",
          status: "in_progress",
          _meta: { claudeCode: { toolResponse: { elapsedTimeSeconds: (i + 1) * 0.3 } } },
        });
      }
      notify(sessionId, { sessionUpdate: "tool_call_update", toolCallId: "long", status: "completed" });
      chunk(sessionId, "Tests passed.");
      return done({ stopReason: "end_turn" });
    }
    case "refusal":
      chunk(sessionId, "I can't help with that.");
      return done({ stopReason: "refusal" });
    case "fetch": {
      const report = [];
      const failure = (e) => (e.stderr || e.message || "").toString().trim();
      try {
        report.push(`origin: ${git(cwd, "remote", "get-url", "origin").trim()}`);
        const before = git(cwd, "rev-parse", "origin/main").trim();
        chunk(sessionId, "waiting for upstream");
        let after = before;
        for (let i = 0; i < 100 && after === before; i++) {
          git(cwd, "fetch", "origin", "main");
          after = git(cwd, "rev-parse", "origin/main").trim();
          if (after === before) await sleep(200);
        }
        report.push(`fetched: ${git(cwd, "show", "origin/main:upstream.txt").trim()}`);
        report.push(`log: ${git(cwd, "log", "-1", "--format=%s", "origin/main").trim()}`);
      } catch (e) {
        report.push(`fetch failed: ${failure(e)}`);
      }
      try {
        git(cwd, "push", "origin", "HEAD:refs/heads/agent/pushed-from-sandbox");
        report.push("push succeeded");
      } catch (e) {
        report.push(`push failed: ${failure(e)}`);
      }
      fs.writeFileSync(path.join(cwd, "fetch.txt"), "written after the fetch\n");
      chunk(sessionId, `\n${report.join("\n")}`);
      return done({ stopReason: "end_turn" });
    }
    case "multi": {
      const dirs = repoDirs(root);
      const report = [`dirs: ${dirs.join(",")}`, `root is cwd: ${fs.realpathSync(root) === fs.realpathSync(process.cwd())}`];
      for (const dir of dirs) {
        const repo = path.join(root, dir);
        report.push(`origin ${dir}: ${git(repo, "remote", "get-url", "origin").trim()}`);
        report.push(`alternates ${dir}: ${fs.existsSync(path.join(repo, ".git/objects/info/alternates"))}`);
        report.push(`branch ${dir}: ${git(repo, "rev-parse", "--abbrev-ref", "HEAD").trim()}`);
      }
      applyRepoMarkers(text, root);
      chunk(sessionId, `\n${report.join("\n")}`);
      return done({ stopReason: "end_turn" });
    }
    case "forge-remote-ref": {
      const secret = hostSecret();
      fs.writeFileSync(path.join(cwd, "config.env"), `TOKEN=${secret}\n`);
      git(cwd, "add", "config.env");
      git(cwd, "commit", "-q", "-m", "Add config");
      // Make the leaking commit look like it is already on the remote.
      git(cwd, "update-ref", "refs/remotes/origin/x", "HEAD");
      git(cwd, "update-ref", "refs/remotes/origin/main", "HEAD");
      fs.writeFileSync(path.join(cwd, "clean.txt"), "clean\n");
      git(cwd, "add", "clean.txt");
      git(cwd, "commit", "-q", "-m", "Clean change");
      applyRepoMarkers(text, root);
      chunk(sessionId, "Committed the changes.");
      return done({ stopReason: "end_turn" });
    }
    case "rebase": {
      const report = [];
      const subjects = () => git(cwd, "log", "--format=%s", "HEAD").trim().split("\n").join(" < ");
      try {
        git(cwd, "fetch", "origin", "main");
        report.push(`before: ${subjects()}`);
        git(cwd, "rebase", "-q", "origin/main");
        report.push(`rebased: ${subjects()}`);
      } catch (e) {
        report.push(`rebase failed: ${(e.stderr || e.message || "").toString().trim()}`);
      }
      applyRepoMarkers(text, root);
      if (text.includes("[wait]")) {
        chunk(sessionId, "waiting for release");
        while (!fs.existsSync(path.join(root, "release"))) {
          if (await waitCancel(100)) return done({ stopReason: "cancelled" });
        }
      }
      chunk(sessionId, `\n${report.join("\n")}`);
      return done({ stopReason: "end_turn" });
    }
    case "egress": {
      const report = [];
      try {
        const proxy = new URL(process.env.HTTPS_PROXY ?? "");
        const target = markers(text, "target")[0];
        const auth = Buffer.from(`${decodeURIComponent(proxy.username)}:${decodeURIComponent(proxy.password)}`).toString("base64");
        report.push(`proxy: ${proxy.hostname}:${proxy.port}`);
        report.push(
          await new Promise((resolve, reject) => {
            const socket = net.connect(Number(proxy.port), proxy.hostname);
            let buf = "";
            let open = false;
            socket.on("error", reject);
            socket.on("close", () => resolve(open ? `closed after: ${buf.trim()}` : `closed: ${buf.trim()}`));
            socket.on("data", (data) => {
              buf += data.toString();
              if (!open) {
                const end = buf.indexOf("\r\n\r\n");
                if (end < 0) return;
                const status = buf.slice(0, buf.indexOf("\r\n"));
                buf = buf.slice(end + 4);
                if (!status.includes(" 200")) {
                  socket.destroy();
                  return resolve(`status: ${status}`);
                }
                open = true;
                socket.write("hello through the tunnel\n");
              }
              if (buf.includes("\n")) {
                socket.destroy();
                resolve(`echo: ${buf.trim()}`);
              }
            });
            socket.write(`CONNECT ${target} HTTP/1.1\r\nHost: ${target}\r\nProxy-Authorization: Basic ${auth}\r\n\r\n`);
          }),
        );
      } catch (e) {
        report.push(`egress failed: ${e.message}`);
      }
      chunk(sessionId, `\n${report.join("\n")}`);
      return done({ stopReason: "end_turn" });
    }
    case "request-repo": {
      const servers = session.mcpServers ?? [];
      const report = [`mcp: ${servers.length} server(s) from ${session.mcpFrom}`];
      const server = servers.find((s) => s.type === "http" && s.name === "tinfirm");
      if (!server) {
        report.push("no tinfirm MCP server; I need the repository named in my summary");
        chunk(sessionId, `\n${report.join("\n")}`);
        return done({ stopReason: "end_turn" });
      }
      report.push(`mcp url: ${server.url}`);
      try {
        let id = 1;
        const init = await mcpCall(server, "initialize", {
          protocolVersion: "2025-06-18",
          capabilities: {},
          clientInfo: { name: "fake-agent", version: "1.0.0" },
        }, id++);
        report.push(`initialize: ${init.status} ${init.body.result.serverInfo.name} ${init.body.result.protocolVersion}`);
        const initialized = await mcpCall(server, "notifications/initialized", undefined, undefined);
        report.push(`initialized: ${initialized.status}`);
        const tools = await mcpCall(server, "tools/list", {}, id++);
        report.push(`tools: ${tools.body.result.tools.map((t) => t.name).join(",")}`);
        const listed = async () => {
          const list = await mcpCall(server, "tools/call", { name: "list_repositories", arguments: {} }, id++);
          const value = list.body.result.structuredContent;
          return `repositories: ${value.repositories.map((r) => r.path).join(",")}; requestable: ${value.requestable
            .map((r) => `${r.name}=${r.pendingRequest}`)
            .join(",")}`;
        };
        report.push(await listed());
        for (const name of markers(text, "request")) {
          const call = await mcpCall(server, "tools/call", {
            name: "request_repository",
            arguments: { name, reason: `The task needs ${name}.` },
          }, id++);
          const result = call.body.result;
          report.push(`request ${name}: ${result.isError ? "error" : "ok"}: ${result.content[0].text}`);
        }
        report.push(await listed());
        for (const title of markers(text, "task")) {
          const call = await mcpCall(server, "tools/call", {
            name: "create_task",
            arguments: { title, description: `Found while working on the task: ${title}.` },
          }, id++);
          const result = call.body.result;
          report.push(`task ${title}: ${result.isError ? "error" : "ok"}: ${result.content[0].text}`);
        }
        for (const name of markers(text, "upload")) {
          if (!name.startsWith("/")) fs.writeFileSync(path.join(root, name), `The report ${name}.\n`);
          const call = await mcpCall(server, "tools/call", {
            name: "upload_attachment",
            arguments: { path: name, note: `About ${name}.` },
          }, id++);
          const result = call.body.result;
          report.push(`upload ${name}: ${result.isError ? "error" : "ok"}: ${result.content[0].text}`);
        }
        for (const question of markers(text, "ask")) {
          const call = await mcpCall(server, "tools/call", {
            name: "ask_human",
            arguments: { question, options: ["Yes", "No", "yes"] },
          }, id++);
          const result = call.body.result;
          report.push(`ask ${question}: ${result.isError ? "error" : "ok"}: ${result.content[0].text}`);
        }
        for (const outcome of markers(text, "finish")) {
          const call = await mcpCall(server, "tools/call", {
            name: "finish_run",
            arguments: {
              outcome,
              summary: `Finished as ${outcome}.`,
              nextSteps: ["Review the change", " "],
              openQuestions: [`Is ${outcome} right?`],
            },
          }, id++);
          const result = call.body.result;
          report.push(`finish ${outcome}: ${result.isError ? "error" : "ok"}: ${result.content[0].text}`);
        }
        const get = await fetch(server.url, { method: "GET", headers: { accept: "text/event-stream" } });
        report.push(`get: ${get.status}`);
      } catch (e) {
        report.push(`mcp failed: ${e.message}`);
      }
      chunk(sessionId, `\n${report.join("\n")}`);
      return done({ stopReason: "end_turn" });
    }
    case "platform-v2": {
      const servers = session.mcpServers ?? [];
      const report = [`mcp: ${servers.length} server(s) from ${session.mcpFrom}`];
      const outputDir = process.env.TINFIRM_OUTPUT_DIR;
      report.push(`output dir: ${outputDir ? "set" : "unset"}`);
      for (const file of markers(text, "output")) {
        const target = path.join(outputDir, file);
        fs.mkdirSync(path.dirname(target), { recursive: true });
        fs.writeFileSync(target, `Output ${file}.\n`);
      }
      for (const file of markers(text, "output-secret")) {
        const secret = hostSecret();
        fs.writeFileSync(path.join(outputDir, file), `TOKEN=${secret}\n`);
      }
      const server = servers.find((s) => s.type === "http" && s.name === "tinfirm");
      if (!server) {
        report.push("no tinfirm MCP server");
        chunk(sessionId, `\n${report.join("\n")}`);
        return done({ stopReason: "end_turn" });
      }
      try {
        let id = 1;
        const init = await mcpCall(server, "initialize", { protocolVersion: "2025-06-18", capabilities: {}, clientInfo: { name: "fake-agent", version: "1.0.0" } }, id++);
        report.push(`initialize: ${init.status} ${init.body.result.serverInfo.name}`);
        report.push(`instructions: ${init.body.result.instructions.slice(0, 60)}`);
        await mcpCall(server, "notifications/initialized", undefined, undefined);
        const tools = await mcpCall(server, "tools/list", {}, id++);
        report.push(`tools: ${tools.body.result.tools.map((t) => t.name).join(",")}`);
        const call = async (name, args) => {
          const answer = await mcpCall(server, "tools/call", { name, arguments: args }, id++);
          if (answer.body.error) return `rpc error ${answer.body.error.code}: ${answer.body.error.message}`;
          const result = answer.body.result;
          const texts = result.content.map((c) => c.text).join(" | ");
          const structured = result.structuredContent ? ` structured=${JSON.stringify(result.structuredContent)}` : "";
          return `${result.isError ? "error" : "ok"}: ${texts}${structured}`;
        };
        const list = await mcpCall(server, "tools/call", { name: "list_repositories", arguments: {} }, id++);
        const value = list.body.result.structuredContent;
        report.push(`repositories: ${value.repositories.map((r) => r.path).join(",")}; requestable: ${value.requestable.map((r) => `${r.name}=${r.pendingRequest}`).join(",")}`);
        for (const name of markers(text, "access")) {
          report.push(`access ${name}: ${await call("request_access", { repository: name, reason: `The request needs ${name}.` })}`);
        }
        for (const name of markers(text, "access-tool")) {
          report.push(`access-tool ${name}: ${await call("request_access", { tool: name, reason: `The request needs the ${name} tools.` })}`);
        }
        for (const name of markers(text, "tool")) {
          report.push(`tool ${name}: ${await call(name, { key: "SHOP-1" })}`);
        }
        for (const url of markers(text, "link")) {
          report.push(`link ${url}: ${await call("save_artifact", { url, title: "Link" })}`);
        }
        for (const name of markers(text, "save")) {
          fs.writeFileSync(path.join(root, name), `The document ${name}.\n`);
          report.push(`save ${name}: ${await call("save_artifact", { path: name, note: `About ${name}.` })}`);
        }
        for (const question of markers(text, "ask")) {
          report.push(`ask ${question}: ${await call("ask", { question, options: ["Yes", "No", "yes"] })}`);
        }
        for (const outcome of markers(text, "finish")) {
          report.push(`finish ${outcome}: ${await call("finish", { outcome, summary: `Finished as ${outcome}.`, nextSteps: ["Review"], openQuestions: [] })}`);
        }
        report.push(`ask_human: ${await call("ask_human", { question: "old name?" })}`);
      } catch (e) {
        report.push(`mcp failed: ${e.message}`);
      }
      chunk(sessionId, `\n${report.join("\n")}`);
      return done({ stopReason: "end_turn" });
    }
    case "auth":
      return fail({ code: -32000, message: "Authentication required" });
    case "codex-refresh-race":
    case "codex-refresh-stale": {
      // Codex, refused by ChatGPT because the refresh token it held was used already. In the
      // race another run of this host renewed auth.json first; stale, nothing on the host did.
      if (scenario === "codex-refresh-race") {
        fs.writeFileSync(
          path.join(process.env.CODEX_HOME, "auth.json"),
          JSON.stringify({ tokens: { refresh_token: "renewed-by-another-run", access_token: "renewed" } }) + "\n",
        );
      }
      return fail({
        code: -32603,
        message: "Internal error",
        data: {
          message:
            "Your access token could not be refreshed because your refresh token was already used. Please log out and sign in again.",
          codexErrorInfo: "unauthorized",
        },
      });
    }
    case "crash":
      chunk(sessionId, "about to crash");
      await sleep(50);
      console.error("fake-agent: simulated crash");
      process.exit(3);
      break;
    default:
      return fail({ code: -32602, message: `unknown scenario ${scenario}` });
  }
}
