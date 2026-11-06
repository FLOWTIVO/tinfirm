// Tests of tinfirm-pi-acp.mjs: `node --test runner/images/pi/test/`. The adapter runs as a child
// process and speaks ACP over stdio with these tests, against fake-pi.mjs in place of pi.

import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { chmodSync, existsSync, mkdtempSync, readFileSync, realpathSync, rmSync, symlinkSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { after, describe, it } from "node:test";
import { fileURLToPath } from "node:url";

import {
  isAuthError,
  mcpConfig,
  piArgs,
  promptToPi,
  toolKind,
  toolTitle,
  turnUsage,
} from "../tinfirm-pi-acp.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const adapter = join(here, "..", "tinfirm-pi-acp.mjs");
const fakePi = join(here, "fake-pi.mjs");
chmodSync(fakePi, 0o755);

const MCP = [{ type: "http", name: "tinfirm", url: "http://172.17.0.1:4000/secret/mcp", headers: [] }];
const dirs = [];
const children = [];
after(() => {
  // A failed assertion leaves its adapter running; it must not keep the test process alive.
  children.forEach((child) => child.kill("SIGKILL"));
  dirs.forEach((dir) => rmSync(dir, { recursive: true, force: true }));
});

function tempDir() {
  const dir = mkdtempSync(join(tmpdir(), "tinfirm-pi-acp-"));
  dirs.push(dir);
  return dir;
}

/** Starts the adapter; `request` sends a request and resolves with its response. */
function start(agentDir, env = {}) {
  const child = spawn(process.execPath, [adapter], {
    env: { ...process.env, PI_CODING_AGENT_DIR: agentDir, TINFIRM_PI_COMMAND: fakePi, ...env },
    stdio: ["pipe", "pipe", "pipe"],
  });
  children.push(child);
  const updates = [];
  const pending = new Map();
  const stderr = [];
  let buffer = "";
  let nextId = 1;
  child.stdout.setEncoding("utf8");
  child.stdout.on("data", (chunk) => {
    buffer += chunk;
    let index;
    while ((index = buffer.indexOf("\n")) !== -1) {
      const message = JSON.parse(buffer.slice(0, index));
      buffer = buffer.slice(index + 1);
      if (message.method === "session/update") updates.push(message.params.update);
      else if (pending.has(message.id)) {
        pending.get(message.id)(message);
        pending.delete(message.id);
      }
    }
  });
  child.stderr.setEncoding("utf8");
  child.stderr.on("data", (chunk) => stderr.push(chunk));
  const send = (message) => child.stdin.write(`${JSON.stringify({ jsonrpc: "2.0", ...message })}\n`);
  return {
    updates,
    stderr,
    request(method, params) {
      const id = nextId++;
      return new Promise((resolve) => {
        pending.set(id, resolve);
        send({ id, method, params });
      });
    },
    notify(method, params) {
      send({ method, params });
    },
    stop() {
      child.stdin.end();
      return new Promise((resolve) => child.on("exit", resolve));
    },
  };
}

async function newSession(acp, cwd) {
  const init = await acp.request("initialize", { protocolVersion: 1, clientCapabilities: {} });
  assert.equal(init.result.agentCapabilities.mcpCapabilities.http, true);
  assert.equal(init.result.agentCapabilities.loadSession, true);
  const created = await acp.request("session/new", { cwd, mcpServers: MCP });
  assert.ok(created.result?.sessionId, JSON.stringify(created));
  return created.result.sessionId;
}

const prompt = (acp, sessionId, text = "Do the work") =>
  acp.request("session/prompt", { sessionId, prompt: [{ type: "text", text }] });

/** Resolves once `ready()` holds, checking every 10 ms for up to 5 s. */
async function waitFor(ready) {
  for (let i = 0; i < 500; i++) {
    if (ready()) return;
    await new Promise((resolve) => setTimeout(resolve, 10));
  }
  throw new Error("timed out waiting");
}

const fakeLog = (agentDir) =>
  readFileSync(join(agentDir, "fake-pi-log.jsonl"), "utf8")
    .trim()
    .split("\n")
    .map((line) => JSON.parse(line));

describe("tinfirm-pi-acp", () => {
  it("runs a turn: updates, the turn's tokens, the platform tools in mcp.json", async () => {
    const agentDir = tempDir();
    const cwd = tempDir();
    const acp = start(agentDir, { TINFIRM_MODEL_PROVIDER: "anthropic", TINFIRM_MODEL: "anthropic/claude-test" });
    const sessionId = await newSession(acp, cwd);
    const response = await prompt(acp, sessionId);
    assert.deepEqual(response.result, {
      stopReason: "end_turn",
      usage: { inputTokens: 1200, outputTokens: 300, cachedReadTokens: 5000, cachedWriteTokens: 400, totalTokens: 6900 },
    });
    const kinds = acp.updates.map((u) => u.sessionUpdate);
    assert.deepEqual(kinds, [
      "agent_thought_chunk",
      "tool_call",
      "tool_call_update",
      "tool_call",
      "tool_call_update",
      "agent_message_chunk",
      "agent_message_chunk",
      "usage_update",
    ]);
    const read = acp.updates[1];
    assert.equal(read.toolCallId, "call-1");
    assert.equal(read.kind, "read");
    assert.equal(read.title, "read README.md");
    assert.deepEqual(read.locations, [{ path: join(cwd, "README.md") }]);
    assert.equal(acp.updates[2].status, "completed");
    assert.equal(acp.updates[2].content[0].content.text, "# Readme");
    assert.equal(acp.updates[3].title, "tinfirm: finish");
    assert.equal(acp.updates[3].kind, "other");
    assert.deepEqual(acp.updates.at(-1), {
      sessionUpdate: "usage_update",
      used: 6600,
      size: 200000,
      cost: { amount: 0.05, currency: "USD" },
    });
    // pi got the session ID, the provider and the model without the provider in front.
    const [started] = fakeLog(agentDir);
    assert.deepEqual(started.args, [
      "--mode", "rpc", "--no-approve", "--session-id", sessionId, "--provider", "anthropic", "--model", "claude-test",
    ]);
    assert.equal(realpathSync(started.cwd), realpathSync(cwd));
    assert.deepEqual(started.mcp, {
      mcpServers: { tinfirm: { url: MCP[0].url, headers: {}, exposure: "direct" } },
      autoEnableCodemode: false,
    });
    assert.equal(started.models, null);
    await acp.stop();
  });

  it("resumes a session with session/load, and refuses one pi does not have", async () => {
    const agentDir = tempDir();
    const cwd = tempDir();
    let acp = start(agentDir);
    const sessionId = await newSession(acp, cwd);
    await prompt(acp, sessionId);
    await acp.stop();

    acp = start(agentDir);
    await acp.request("initialize", { protocolVersion: 1 });
    const loaded = await acp.request("session/load", { sessionId, cwd, mcpServers: MCP });
    assert.deepEqual(loaded.result, {});
    const second = await prompt(acp, sessionId, "Go on");
    // Only this turn's tokens, although pi's session holds both turns.
    assert.equal(second.result.usage.totalTokens, 6900);
    const missing = await acp.request("session/load", { sessionId: "no-such-session", cwd, mcpServers: [] });
    assert.equal(missing.error.code, -32002);
    const invalid = await acp.request("session/load", { sessionId: "../etc", cwd, mcpServers: [] });
    assert.equal(invalid.error.code, -32602);
    await acp.stop();
  });

  it("writes and removes the gateway's base URL in models.json", async () => {
    const agentDir = tempDir();
    const cwd = tempDir();
    let acp = start(agentDir, {
      TINFIRM_MODEL_PROVIDER: "openai",
      TINFIRM_MODEL_BASE_URL: "https://litellm.example.test/v1",
    });
    await newSession(acp, cwd);
    await acp.stop();
    assert.deepEqual(JSON.parse(readFileSync(join(agentDir, "models.json"), "utf8")), {
      providers: { openai: { baseUrl: "https://litellm.example.test/v1" } },
    });
    // A later run without the gateway: the file is gone. A models.json of the agent's own stays.
    acp = start(agentDir, { TINFIRM_MODEL_PROVIDER: "openai" });
    await newSession(acp, cwd);
    await acp.stop();
    assert.equal(existsSync(join(agentDir, "models.json")), false);
    writeFileSync(join(agentDir, "models.json"), JSON.stringify({ providers: { ollama: { baseUrl: "http://x", api: "openai-completions" } } }));
    acp = start(agentDir, { TINFIRM_MODEL_PROVIDER: "openai" });
    await newSession(acp, cwd);
    await acp.stop();
    assert.equal(existsSync(join(agentDir, "models.json")), true);
  });

  it("replaces a symlink at mcp.json instead of writing through it", async () => {
    const agentDir = tempDir();
    const elsewhere = join(tempDir(), "target.json");
    writeFileSync(elsewhere, "keep");
    symlinkSync(elsewhere, join(agentDir, "mcp.json"));
    const acp = start(agentDir);
    await newSession(acp, tempDir());
    await acp.stop();
    assert.equal(readFileSync(elsewhere, "utf8"), "keep");
    assert.equal(JSON.parse(readFileSync(join(agentDir, "mcp.json"), "utf8")).mcpServers.tinfirm.exposure, "direct");
  });

  it("ends the prompt with -32000 when the provider refuses the key", async () => {
    const acp = start(tempDir(), { FAKE_PI_SCENARIO: "auth-error" });
    const sessionId = await newSession(acp, tempDir());
    const response = await prompt(acp, sessionId);
    assert.equal(response.error.code, -32000);
    assert.match(response.error.message, /invalid x-api-key/);
    assert.equal(response.error.data.provider, "anthropic");
    await acp.stop();
  });

  it("ends the prompt with the provider's message on other errors", async () => {
    const acp = start(tempDir(), { FAKE_PI_SCENARIO: "rate-limit" });
    const sessionId = await newSession(acp, tempDir());
    const response = await prompt(acp, sessionId);
    assert.equal(response.error.code, -32603);
    assert.match(response.error.message, /429 rate limit exceeded/);
    assert.match(acp.stderr.join(""), /pi retries the request \(attempt 1 of 3\)/);
    await acp.stop();
  });

  it("maps a length stop to max_tokens", async () => {
    const acp = start(tempDir(), { FAKE_PI_SCENARIO: "length" });
    const sessionId = await newSession(acp, tempDir());
    const response = await prompt(acp, sessionId);
    assert.equal(response.result.stopReason, "max_tokens");
    assert.equal(response.result.usage.totalTokens, 15);
    await acp.stop();
  });

  it("cancels a running turn with abort", async () => {
    const acp = start(tempDir(), { FAKE_PI_SCENARIO: "slow" });
    const sessionId = await newSession(acp, tempDir());
    const running = prompt(acp, sessionId);
    setTimeout(() => acp.notify("session/cancel", { sessionId }), 100);
    const response = await running;
    assert.equal(response.result.stopReason, "cancelled");
    await acp.stop();
  });

  it("keeps a cancel that arrives before the prompt reached pi", async () => {
    const acp = start(tempDir(), { FAKE_PI_SCENARIO: "slow" });
    const sessionId = await newSession(acp, tempDir());
    const running = prompt(acp, sessionId);
    acp.notify("session/cancel", { sessionId });
    const response = await running;
    assert.equal(response.result.stopReason, "cancelled");
    await acp.stop();
  });

  it("fails the prompt with pi's last stderr lines when pi exits", async () => {
    const acp = start(tempDir(), { FAKE_PI_SCENARIO: "crash" });
    const sessionId = await newSession(acp, tempDir());
    const response = await prompt(acp, sessionId);
    assert.equal(response.error.code, -32603);
    assert.match(response.error.message, /pi exited \(code 3\).*out of memory/);
    await acp.stop();
  });

  it("advertises steering and answers promptRequired without a running turn", async () => {
    const acp = start(tempDir());
    const init = await acp.request("initialize", { protocolVersion: 1, clientCapabilities: {} });
    assert.deepEqual(init.result._meta, { steering: { supported: true } });
    const created = await acp.request("session/new", { cwd: tempDir(), mcpServers: [] });
    const sessionId = created.result.sessionId;
    const idle = await acp.request("_session/steering", { sessionId, prompt: [{ type: "text", text: "Hi" }] });
    assert.deepEqual(idle.result, { outcome: "promptRequired", reason: "noRunningTurn" });
    const empty = await acp.request("_session/steering", { sessionId, prompt: [] });
    assert.equal(empty.error.code, -32602);
    const unknown = await acp.request("_session/steering", { sessionId: "nope", prompt: [{ type: "text", text: "Hi" }] });
    assert.equal(unknown.error.code, -32002);
    await acp.stop();
  });

  it("steers a running turn through pi's steering queue", async () => {
    const agentDir = tempDir();
    const acp = start(agentDir, { FAKE_PI_SCENARIO: "steer" });
    const sessionId = await newSession(acp, tempDir());
    const running = prompt(acp, sessionId);
    await waitFor(() => acp.updates.some((u) => u.sessionUpdate === "tool_call_update"));
    const steer = await acp.request("_session/steering", {
      sessionId,
      prompt: [{ type: "text", text: "Use the staging database." }],
      _meta: { steering: { idleBehavior: "promptRequired" } },
    });
    assert.deepEqual(steer.result, { outcome: "injected" });
    const response = await running;
    assert.equal(response.result.stopReason, "end_turn");
    const text = acp.updates
      .filter((u) => u.sessionUpdate === "agent_message_chunk")
      .map((u) => u.content.text)
      .join("");
    assert.equal(text, "Steered: Use the staging database.");
    assert.equal(fakeLog(agentDir).some((record) => record.cleared), false);
    await acp.stop();
  });

  it("gives a steered message back when the turn ends without it", async () => {
    const agentDir = tempDir();
    const acp = start(agentDir, { FAKE_PI_SCENARIO: "steer-ignored" });
    const sessionId = await newSession(acp, tempDir());
    const running = prompt(acp, sessionId);
    await waitFor(() => acp.updates.some((u) => u.sessionUpdate === "tool_call_update"));
    const steer = await acp.request("_session/steering", { sessionId, prompt: [{ type: "text", text: "Too late" }] });
    assert.deepEqual(steer.result, { outcome: "promptRequired", reason: "turnEnded" });
    assert.equal((await running).result.stopReason, "end_turn");
    await waitFor(() => fakeLog(agentDir).some((record) => record.cleared));
    assert.deepEqual(fakeLog(agentDir).find((record) => record.cleared).cleared, ["Too late"]);
    await acp.stop();
  });

  it("answers unknown methods with method not found", async () => {
    const acp = start(tempDir());
    const response = await acp.request("session/set_mode", { sessionId: "x", modeId: "y" });
    assert.equal(response.error.code, -32601);
    await acp.stop();
  });
});

describe("helpers", () => {
  it("passes the model with the provider, without repeating it", () => {
    assert.deepEqual(piArgs("s1", {}), ["--mode", "rpc", "--no-approve", "--session-id", "s1"]);
    assert.deepEqual(piArgs("s1", { TINFIRM_MODEL_PROVIDER: "anthropic" }), [
      "--mode", "rpc", "--no-approve", "--session-id", "s1",
    ]);
    assert.deepEqual(piArgs("s1", { TINFIRM_MODEL_PROVIDER: "openrouter", TINFIRM_MODEL: "anthropic/claude-sonnet-4.5" }).slice(5), [
      "--provider", "openrouter", "--model", "anthropic/claude-sonnet-4.5",
    ]);
    assert.deepEqual(piArgs("s1", { TINFIRM_MODEL_PROVIDER: "amazon-bedrock", TINFIRM_MODEL: "amazon-bedrock/us.anthropic.claude-x" }).slice(5), [
      "--provider", "amazon-bedrock", "--model", "us.anthropic.claude-x",
    ]);
  });

  it("passes the agent's effort as pi's thinking level", () => {
    assert.deepEqual(piArgs("s1", { TINFIRM_THINKING_LEVEL: "xhigh" }).slice(5), ["--thinking", "xhigh"]);
    assert.deepEqual(piArgs("s1", { TINFIRM_MODEL_PROVIDER: "anthropic", TINFIRM_MODEL: "claude-x", TINFIRM_THINKING_LEVEL: "low" }).slice(5), [
      "--provider", "anthropic", "--model", "claude-x", "--thinking", "low",
    ]);
    assert.deepEqual(piArgs("s1", { TINFIRM_THINKING_LEVEL: " " }).slice(5), []);
    // pi restores a resumed session's recorded level: without one, its own default instead.
    assert.deepEqual(piArgs("s1", {}, { resume: true }).slice(5), ["--thinking", "medium"]);
    assert.deepEqual(piArgs("s1", { TINFIRM_THINKING_LEVEL: "max" }, { resume: true }).slice(5), ["--thinking", "max"]);
  });

  it("recognises refused credentials", () => {
    for (const text of [
      '401 {"error":{"type":"authentication_error"}}',
      "Invalid API key provided",
      "No API key found for anthropic",
      "The security token included in the request is invalid.",
      "UnrecognizedClientException: bad key",
    ]) {
      assert.equal(isAuthError(text), true, text);
    }
    for (const text of ["429 rate limit exceeded", "prompt is too long", "529 overloaded", "error 4010 in tool"]) {
      assert.equal(isAuthError(text), false, text);
    }
  });

  it("subtracts session stats", () => {
    const before = { tokens: { input: 10, output: 5, cacheRead: 100, cacheWrite: 1 } };
    const after = { tokens: { input: 30, output: 15, cacheRead: 300, cacheWrite: 2 } };
    assert.deepEqual(turnUsage(before, after), {
      inputTokens: 20, outputTokens: 10, cachedReadTokens: 200, cachedWriteTokens: 1, totalTokens: 231,
    });
    assert.equal(turnUsage(null, null).totalTokens, 0);
  });

  it("names and classifies tools", () => {
    assert.equal(toolKind("bash"), "execute");
    assert.equal(toolKind("edit"), "edit");
    assert.equal(toolKind("grep"), "search");
    assert.equal(toolKind("mcp__tinfirm__ask"), "other");
    assert.equal(toolTitle("bash", { command: "mvn -q verify" }), "mvn -q verify");
    assert.equal(toolTitle("bash", { command: "x".repeat(300) }).length, 200);
    assert.equal(toolTitle("mcp__tinfirm__save_artifact", {}), "tinfirm: save_artifact");
  });

  it("builds mcp.json from ACP servers", () => {
    assert.deepEqual(
      mcpConfig([
        { type: "http", name: "tin firm", url: "http://h/mcp", headers: [{ name: "X-A", value: "1" }] },
        { name: "local", command: "srv", args: ["-v"], env: [{ name: "A", value: "b" }] },
        { type: "sse", name: "old", url: "http://h/sse", headers: [] },
      ]),
      {
        mcpServers: {
          tin_firm: { url: "http://h/mcp", headers: { "X-A": "1" }, exposure: "direct" },
          local: { command: "srv", args: ["-v"], env: { A: "b" }, exposure: "direct" },
        },
        autoEnableCodemode: false,
      },
    );
  });

  it("keeps a server named __proto__ an own key", () => {
    const config = mcpConfig([{ type: "http", name: "__proto__", url: "http://h/mcp", headers: [] }]);
    assert.equal(Object.getPrototypeOf(config.mcpServers), Object.prototype);
    assert.deepEqual(Object.keys(config.mcpServers), ["__proto__"]);
    assert.equal(JSON.parse(JSON.stringify(config)).mcpServers.__proto__.url, "http://h/mcp");
  });

  it("turns the prompt into pi's message", () => {
    assert.deepEqual(
      promptToPi([
        { type: "text", text: "a" },
        { type: "resource_link", uri: "file:///x", name: "x" },
        { type: "image", data: "AAA", mimeType: "image/png" },
      ]),
      { message: "a\n\n[x](file:///x)", images: [{ type: "image", data: "AAA", mimeType: "image/png" }] },
    );
  });
});
