#!/usr/bin/env node
// Tinfirm's ACP adapter for pi (ADR-0041).
//
// Speaks the Agent Client Protocol (ACP v1, JSON-RPC 2.0, one JSON object per line) on stdin and
// stdout, and drives `pi --mode rpc`, pi's documented JSONL protocol, for each session. It is
// written for the runner host's ACP client, not for editors:
//
// - `session/new` and `session/load` write the client's MCP servers (the run's Tinfirm platform
//   tools) into pi's `mcp.json` with `direct` exposure, so the model sees them like built-in
//   tools, and start pi with `--session-id`. pi keeps its sessions under the agent's home,
//   which the runner host keeps between the runs of a job, so `session/load` resumes one.
// - `session/prompt` sends pi's `prompt` command and maps pi's events to `session/update`
//   notifications until `agent_settled`. The response carries the turn's tokens, the
//   difference of pi's `get_session_stats` before and after the turn, and a `usage_update`
//   carries the context occupancy and the cost of this process's turns.
// - A provider error ends the prompt with a JSON-RPC error: `-32000` (authentication required)
//   when the provider refused the credential, else `-32603` with pi's message, which the runner
//   host classifies (usage limits, rate limits, context overflow).
// - `_session/steering` (the steering extension of claude-agent-acp and codex-acp, advertised in
//   `initialize`'s `_meta.steering.supported`) gives the running turn a message with pi's `steer`,
//   which pi delivers after the turn's current tool calls, before its next model request. It
//   answers `injected` once pi took the message from its steering queue (`queue_update`), and
//   `promptRequired` when no turn runs or the turn ended with the message still queued, which it
//   then clears from pi's queue: the client keeps the message for a prompt of its own.
// - `session/cancel` clears pi's steering queue and sends pi's `abort`.
//
// The provider and model come from the environment the runner host sets: `TINFIRM_MODEL_PROVIDER`
// (pi's provider ID, such as `anthropic` or `amazon-bedrock`), `TINFIRM_MODEL` (optional; with or
// without the provider in front) and `TINFIRM_MODEL_BASE_URL` (a gateway, written to pi's
// `models.json` as the provider's base URL). The credential itself is in the provider's own
// variables (`ANTHROPIC_API_KEY`, `AWS_BEARER_TOKEN_BEDROCK`, ...), which pi reads. The agent's
// effort is in `TINFIRM_THINKING_LEVEL` (`low` ... `max`), passed as `--thinking`; pi uses the
// nearest level its model offers. pi records a session's level and restores it on resume, so a
// resumed session without one gets pi's own default, as a new session would.
//
// Only stdout carries ACP; diagnostics go to stderr, which the runner host shows as the run's
// log. No dependencies beyond Node.

import { spawn } from "node:child_process";
import { randomUUID } from "node:crypto";
import { existsSync, lstatSync, mkdirSync, readFileSync, realpathSync, renameSync, rmSync, writeFileSync } from "node:fs";
import { homedir } from "node:os";
import { isAbsolute, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

export const ADAPTER_VERSION = "1.1.0";

const PROTOCOL_VERSION = 1;
const AUTH_REQUIRED = -32000;
const RESOURCE_NOT_FOUND = -32002;
const INVALID_PARAMS = -32602;
const METHOD_NOT_FOUND = -32601;
const INTERNAL_ERROR = -32603;

// pi's `tool_execution_update` can be frequent; one ACP progress message per tool call in this
// interval keeps the runner host's inactivity guard quiet during a long command.
const PROGRESS_INTERVAL_MS = 15_000;
// After `abort`, how long to wait for pi's `agent_settled` before the turn counts as ended.
const ABORT_GRACE_MS = 5_000;
const STDERR_TAIL = 20;

class AcpError extends Error {
  constructor(code, message, data) {
    super(message);
    this.code = code;
    this.data = data;
  }
}

/** Calls `onLine` for every LF-terminated line of `stream` (never splitting on U+2028/2029). */
export function readLines(stream, onLine, onEnd) {
  let pending = Buffer.alloc(0);
  stream.on("data", (chunk) => {
    pending = pending.length ? Buffer.concat([pending, chunk]) : chunk;
    let start = 0;
    let index;
    while ((index = pending.indexOf(0x0a, start)) !== -1) {
      let line = pending.subarray(start, index).toString("utf8");
      if (line.endsWith("\r")) line = line.slice(0, -1);
      start = index + 1;
      if (line.trim()) onLine(line);
    }
    pending = pending.subarray(start);
  });
  stream.on("end", () => {
    const rest = pending.toString("utf8").trim();
    if (rest) onLine(rest);
    onEnd?.();
  });
}

/** pi's agent directory: `PI_CODING_AGENT_DIR`, else `~/.pi/agent`. */
export function agentDir(env = process.env) {
  return env.PI_CODING_AGENT_DIR ? resolve(env.PI_CODING_AGENT_DIR) : join(homedir(), ".pi", "agent");
}

/** Writes `path` through a temporary file and a rename, which replaces a symlink at `path` instead of following it. */
function writeAtomically(path, text) {
  const temporary = `${path}.tinfirm-${process.pid}.tmp`;
  writeFileSync(temporary, text, { mode: 0o600 });
  renameSync(temporary, path);
}

/** pi's server names: letters, digits, `_` and `-`. */
function serverName(name, index) {
  const cleaned = String(name ?? "").replace(/[^A-Za-z0-9_-]/g, "_").slice(0, 64);
  return cleaned || `server_${index}`;
}

/**
 * pi's `mcp.json` for the client's MCP servers of a session (ACP `McpServer`). The objects are
 * built from entries, so a name such as `__proto__` is an own key, never the prototype.
 */
export function mcpConfig(servers) {
  const pairs = (list) => Object.fromEntries((list ?? []).map((entry) => [entry.name, entry.value]));
  const entries = [];
  (servers ?? []).forEach((server, index) => {
    const name = serverName(server?.name, index);
    if (typeof server?.url === "string" && server.type !== "sse") {
      entries.push([name, { url: server.url, headers: pairs(server.headers), exposure: "direct" }]);
    } else if (typeof server?.command === "string") {
      entries.push([name, { command: server.command, args: server.args ?? [], env: pairs(server.env), exposure: "direct" }]);
    }
  });
  return { mcpServers: Object.fromEntries(entries), autoEnableCodemode: false };
}

/** pi's `models.json` that points `provider` at a gateway, or `null` without one. */
export function modelsConfig(provider, baseUrl) {
  if (!provider || !baseUrl) return null;
  return { providers: { [provider]: { baseUrl } } };
}

/** Whether `config` is a `models.json` that only this adapter writes (see {@link modelsConfig}). */
function isGatewayOnly(config) {
  const providers = config?.providers;
  if (!providers || Object.keys(config).length !== 1 || Object.keys(providers).length !== 1) return false;
  const entry = Object.values(providers)[0];
  return entry && typeof entry === "object" && Object.keys(entry).length === 1 && typeof entry.baseUrl === "string";
}

/** Writes the session's MCP servers and the gateway of the environment into pi's agent directory. */
export function configurePi(servers, env = process.env) {
  const dir = agentDir(env);
  mkdirSync(dir, { recursive: true });
  writeAtomically(join(dir, "mcp.json"), `${JSON.stringify(mcpConfig(servers), null, 2)}\n`);
  const models = join(dir, "models.json");
  const gateway = modelsConfig(env.TINFIRM_MODEL_PROVIDER, env.TINFIRM_MODEL_BASE_URL);
  if (gateway) {
    writeAtomically(models, `${JSON.stringify(gateway, null, 2)}\n`);
  } else if (existsSync(models) && !lstatSync(models).isSymbolicLink()) {
    // A gateway of an earlier run must not outlive its credential; anything else stays.
    try {
      if (isGatewayOnly(JSON.parse(readFileSync(models, "utf8")))) rmSync(models);
    } catch {
      // Not JSON: not ours.
    }
  }
}

/** pi's own thinking level (`DEFAULT_THINKING_LEVEL`), for a session that names none. */
export const PI_DEFAULT_THINKING_LEVEL = "medium";

/** The CLI arguments of `pi --mode rpc` for a session; `resume` for `session/load`. */
export function piArgs(sessionId, env = process.env, { resume = false } = {}) {
  const args = ["--mode", "rpc", "--no-approve", "--session-id", sessionId];
  const provider = env.TINFIRM_MODEL_PROVIDER?.trim();
  let model = env.TINFIRM_MODEL?.trim();
  if (model) {
    if (provider && model.startsWith(`${provider}/`)) model = model.slice(provider.length + 1);
    if (provider) args.push("--provider", provider);
    args.push("--model", model);
  }
  const thinking = env.TINFIRM_THINKING_LEVEL?.trim() || (resume ? PI_DEFAULT_THINKING_LEVEL : "");
  if (thinking) args.push("--thinking", thinking);
  return args;
}

/** The ACP tool kind of a pi tool. */
export function toolKind(name) {
  switch (name) {
    case "read":
      return "read";
    case "edit":
    case "write":
      return "edit";
    case "bash":
    case "powershell":
      return "execute";
    case "grep":
    case "find":
    case "ls":
      return "search";
    default:
      // MCP tools (`mcp__<server>__<tool>`), codemode and the tools of extensions.
      return "other";
  }
}

const filePath = (args) => args?.path ?? args?.file_path ?? args?.filePath;

/** A short title of a pi tool call. */
export function toolTitle(name, args) {
  const clip = (text) => (text.length > 200 ? `${text.slice(0, 199)}…` : text);
  if ((name === "bash" || name === "powershell") && typeof args?.command === "string") return clip(args.command);
  if (["read", "edit", "write", "ls"].includes(name) && typeof filePath(args) === "string") {
    return clip(`${name} ${filePath(args)}`);
  }
  if ((name === "grep" || name === "find") && typeof args?.pattern === "string") return clip(`${name} ${args.pattern}`);
  const mcp = /^mcp__(.+?)__(.+)$/.exec(name ?? "");
  if (mcp) return `${mcp[1]}: ${mcp[2]}`;
  return name ?? "tool";
}

/** The text of a pi tool result (`{content: [{type: "text", text}], details}`). */
export function resultText(result) {
  if (typeof result === "string") return result;
  const parts = (result?.content ?? []).map((block) =>
    block?.type === "text" ? block.text : block?.type === "image" ? "[image]" : "",
  );
  return parts.filter(Boolean).join("\n");
}

/** Whether pi's error message says that the provider refused the credential. */
export function isAuthError(message) {
  const text = String(message ?? "");
  return (
    /\b401\b/.test(text) ||
    /unauthori[sz]ed/i.test(text) ||
    /invalid[ _-]?(api[ _-]?key|x-api-key|token|credentials?)/i.test(text) ||
    /\bauthentication[ _](error|failed)\b/i.test(text) ||
    /no api key/i.test(text) ||
    /api key (is )?(missing|required|not (found|set|valid))/i.test(text) ||
    /security token included in the request is invalid/i.test(text) ||
    /UnrecognizedClientException|InvalidSignatureException/.test(text)
  );
}

/** The tokens of the turn: the difference of two `get_session_stats` results. */
export function turnUsage(before, after) {
  const tokens = (stats) => stats?.tokens ?? {};
  const delta = (key) => Math.max(0, (tokens(after)[key] ?? 0) - (tokens(before)[key] ?? 0));
  const inputTokens = delta("input");
  const outputTokens = delta("output");
  const cachedReadTokens = delta("cacheRead");
  const cachedWriteTokens = delta("cacheWrite");
  return {
    inputTokens,
    outputTokens,
    cachedReadTokens,
    cachedWriteTokens,
    totalTokens: inputTokens + outputTokens + cachedReadTokens + cachedWriteTokens,
  };
}

/** The prompt of `session/prompt` as pi's `message` and `images`. */
export function promptToPi(blocks) {
  const texts = [];
  const images = [];
  for (const block of blocks ?? []) {
    if (block?.type === "text") texts.push(block.text ?? "");
    else if (block?.type === "image" && block.data) images.push({ type: "image", data: block.data, mimeType: block.mimeType });
    else if (block?.type === "resource_link") texts.push(`[${block.name ?? block.uri}](${block.uri})`);
    else if (block?.type === "resource" && typeof block.resource?.text === "string") texts.push(block.resource.text);
  }
  return { message: texts.join("\n\n"), images };
}

/** One `pi --mode rpc` process. */
class PiProcess {
  constructor(cwd, args, log) {
    this.log = log;
    this.pending = new Map();
    this.nextId = 1;
    this.listeners = new Set();
    this.stderr = [];
    this.exited = null;
    const command = process.env.TINFIRM_PI_COMMAND || "pi";
    this.child = spawn(command, args, { cwd, env: process.env, stdio: ["pipe", "pipe", "pipe"] });
    this.child.on("error", (error) => this.onExit(`cannot start pi: ${error.message}`));
    this.child.on("exit", (code, signal) => this.onExit(`pi exited (${signal ?? `code ${code}`})`));
    this.child.stdin.on("error", () => {});
    readLines(this.child.stdout, (line) => this.onLine(line));
    readLines(this.child.stderr, (line) => {
      this.stderr.push(line);
      if (this.stderr.length > STDERR_TAIL) this.stderr.shift();
      log(`pi: ${line}`);
    });
  }

  onExit(reason) {
    if (this.exited) return;
    const tail = this.stderr.slice(-5).join(" | ");
    this.exited = tail ? `${reason}: ${tail}` : reason;
    for (const { reject } of this.pending.values()) reject(new AcpError(INTERNAL_ERROR, this.exited));
    this.pending.clear();
    for (const listener of this.listeners) listener({ type: "process_exit", reason: this.exited });
  }

  onLine(line) {
    let record;
    try {
      record = JSON.parse(line);
    } catch {
      this.log(`pi: ${line}`);
      return;
    }
    if (record.type === "response" && record.id !== undefined && this.pending.has(record.id)) {
      const { resolve: done } = this.pending.get(record.id);
      this.pending.delete(record.id);
      done(record);
      return;
    }
    if (record.type === "extension_ui_request") {
      // Nobody answers dialogs in a sandbox; fire-and-forget requests need no answer.
      if (["select", "confirm", "input", "editor"].includes(record.method)) {
        this.send({ type: "extension_ui_response", id: record.id, cancelled: true });
      }
      return;
    }
    for (const listener of this.listeners) listener(record);
  }

  send(record) {
    if (!this.exited) this.child.stdin.write(`${JSON.stringify(record)}\n`);
  }

  /** Sends a command and resolves with pi's `response` record. */
  request(command) {
    if (this.exited) return Promise.reject(new AcpError(INTERNAL_ERROR, this.exited));
    const id = `tinfirm-${this.nextId++}`;
    return new Promise((resolveResponse, reject) => {
      this.pending.set(id, { resolve: resolveResponse, reject });
      this.send({ ...command, id });
    });
  }

  /** Sends a command and resolves with its `data`; a failed command rejects. */
  async call(command) {
    const response = await this.request(command);
    if (!response.success) {
      throw new AcpError(INTERNAL_ERROR, `pi ${command.type} failed: ${response.error ?? "unknown error"}`);
    }
    return response.data;
  }

  onEvent(listener) {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  stop() {
    if (this.exited) return;
    this.child.stdin.end();
    setTimeout(() => {
      if (!this.exited) this.child.kill("SIGTERM");
    }, 2_000).unref();
  }
}

/** One ACP session: a pi process and the state of its turns. */
class Session {
  constructor(id, cwd, pi, notify, log) {
    this.id = id;
    this.cwd = cwd;
    this.pi = pi;
    this.notify = notify;
    this.log = log;
    this.turn = null;
    this.baselineCost = null;
    this.progress = new Map();
    pi.onEvent((event) => this.onEvent(event));
  }

  update(update) {
    this.notify({ sessionId: this.id, update });
  }

  onEvent(event) {
    const turn = this.turn;
    switch (event.type) {
      case "message_update": {
        const delta = event.assistantMessageEvent;
        if (delta?.type === "text_delta" && delta.delta) {
          this.update({ sessionUpdate: "agent_message_chunk", content: { type: "text", text: delta.delta } });
        } else if (delta?.type === "thinking_delta" && delta.delta) {
          this.update({ sessionUpdate: "agent_thought_chunk", content: { type: "text", text: delta.delta } });
        }
        break;
      }
      case "message_end":
        if (turn && event.message?.role === "assistant") turn.lastAssistant = event.message;
        break;
      case "tool_execution_start": {
        const path = filePath(event.args);
        this.update({
          sessionUpdate: "tool_call",
          toolCallId: event.toolCallId,
          title: toolTitle(event.toolName, event.args),
          kind: toolKind(event.toolName),
          status: "in_progress",
          rawInput: event.args ?? {},
          ...(typeof path === "string" ? { locations: [{ path: isAbsolute(path) ? path : resolve(this.cwd, path) }] } : {}),
        });
        this.progress.set(event.toolCallId, Date.now());
        break;
      }
      case "tool_execution_update": {
        const last = this.progress.get(event.toolCallId) ?? 0;
        if (Date.now() - last >= PROGRESS_INTERVAL_MS) {
          this.progress.set(event.toolCallId, Date.now());
          this.update({ sessionUpdate: "tool_call_update", toolCallId: event.toolCallId, status: "in_progress" });
        }
        break;
      }
      case "tool_execution_end": {
        this.progress.delete(event.toolCallId);
        const text = resultText(event.result);
        this.update({
          sessionUpdate: "tool_call_update",
          toolCallId: event.toolCallId,
          status: event.isError ? "failed" : "completed",
          ...(text ? { content: [{ type: "content", content: { type: "text", text } }] } : {}),
          rawOutput: { output: text, isError: Boolean(event.isError) },
        });
        break;
      }
      case "auto_retry_start":
        this.log(`pi retries the request (attempt ${event.attempt} of ${event.maxAttempts}): ${event.errorMessage ?? ""}`);
        break;
      case "auto_retry_end":
        if (!event.success) this.log(`pi gave up retrying: ${event.finalError ?? ""}`);
        break;
      case "compaction_start":
        this.log(`pi compacts the conversation (${event.reason})`);
        break;
      case "compaction_end":
        if (event.errorMessage) this.log(`pi could not compact the conversation: ${event.errorMessage}`);
        break;
      case "extension_error":
        this.log(`pi extension error (${event.extensionPath ?? "?"}): ${event.error ?? ""}`);
        break;
      case "queue_update":
        if (turn) this.steersTaken(turn, Array.isArray(event.steering) ? event.steering : []);
        break;
      case "agent_settled":
        if (turn) {
          this.endSteers(turn);
          turn.settle();
        }
        break;
      case "process_exit":
        if (turn) {
          this.endSteers(turn);
          turn.settle(event.reason);
        }
        break;
      default:
        break;
    }
  }

  async stats() {
    try {
      return await this.pi.call({ type: "get_session_stats" });
    } catch (error) {
      this.log(`cannot read pi's session stats: ${error.message}`);
      return null;
    }
  }

  async prompt(blocks) {
    if (this.turn) throw new AcpError(INVALID_PARAMS, "a prompt of this session is still running");
    const { message, images } = promptToPi(blocks);
    let settle;
    const settled = new Promise((resolveSettled) => {
      settle = resolveSettled;
    });
    // The turn exists before the first await, so a cancel that arrives meanwhile is kept.
    const turn = {
      lastAssistant: null,
      cancelled: false,
      sent: false,
      over: false,
      // Steered messages pi holds in its queue, oldest first: each resolves its `_session/steering`.
      steers: [],
      settle: (exit) => settle(exit ?? null),
    };
    this.turn = turn;
    try {
      const before = await this.stats();
      if (this.baselineCost === null) this.baselineCost = before?.cost ?? 0;
      if (turn.cancelled) return { stopReason: "cancelled", usage: turnUsage(before, before) };
      turn.sent = true;
      const response = await this.pi.request({ type: "prompt", message, ...(images.length ? { images } : {}) });
      if (!response.success) throw providerError(response.error ?? "pi refused the prompt");
      const exit = response.data?.disposition === "handled" ? null : await settled;
      if (exit) throw new AcpError(INTERNAL_ERROR, exit);
      const after = await this.stats();
      const usage = turnUsage(before, after);
      const context = after?.contextUsage;
      const update = { sessionUpdate: "usage_update" };
      if (Number.isSafeInteger(context?.tokens) && Number.isSafeInteger(context?.contextWindow) && context.contextWindow > 0) {
        update.used = context.tokens;
        update.size = context.contextWindow;
      }
      if (typeof after?.cost === "number") {
        update.cost = { amount: Math.max(0, after.cost - this.baselineCost), currency: "USD" };
      }
      if (update.used !== undefined || update.cost) this.update(update);
      if (turn.cancelled) return { stopReason: "cancelled", usage };
      const last = turn.lastAssistant;
      if (last?.stopReason === "error") throw providerError(last.errorMessage ?? "the model request failed", last);
      if (last?.stopReason === "aborted") return { stopReason: "cancelled", usage };
      if (last?.stopReason === "length") return { stopReason: "max_tokens", usage };
      return { stopReason: "end_turn", usage };
    } finally {
      this.turn = null;
    }
  }

  /**
   * `_session/steering`: the message goes to the running turn through pi's steering queue, and the
   * answer waits until pi took it (`injected`) or the turn ended without it (`promptRequired`).
   */
  async steer(blocks) {
    const turn = this.turn;
    if (!turn || !turn.sent || turn.cancelled || turn.over) {
      return { outcome: "promptRequired", reason: "noRunningTurn" };
    }
    const { message, images } = promptToPi(blocks);
    return new Promise((resolveSteer, reject) => {
      // Kept before pi answers: its `queue_update` may come first.
      const entry = { message, queued: false, resolve: resolveSteer };
      turn.steers.push(entry);
      this.pi.request({ type: "steer", message, ...(images.length ? { images } : {}) }).then(
        (response) => {
          if (response.success && response.data?.disposition !== "handled") return;
          // Refused, or consumed by an extension's input handler: pi holds nothing of it.
          turn.steers.splice(turn.steers.indexOf(entry), 1);
          if (response.success) resolveSteer({ outcome: "injected" });
          else reject(new AcpError(INTERNAL_ERROR, `pi steer failed: ${response.error ?? "unknown error"}`));
        },
        (error) => {
          turn.steers.splice(turn.steers.indexOf(entry), 1);
          reject(error);
        },
      );
    });
  }

  /**
   * pi's steering queue is `queue` now (the texts of its messages). A steered message pi queued that
   * is no longer in it was taken into the turn; one pi has not queued yet waits.
   */
  steersTaken(turn, queue) {
    const left = new Map();
    for (const text of queue) left.set(text, (left.get(text) ?? 0) + 1);
    for (const entry of [...turn.steers]) {
      const count = left.get(entry.message) ?? 0;
      if (count > 0) {
        left.set(entry.message, count - 1);
        entry.queued = true;
      } else if (entry.queued) {
        turn.steers.splice(turn.steers.indexOf(entry), 1);
        entry.resolve({ outcome: "injected" });
      }
    }
  }

  /** The turn is over: steered messages pi did not take go back to the client, and out of pi's queue. */
  endSteers(turn) {
    turn.over = true;
    if (turn.steers.length === 0) return;
    for (const entry of turn.steers.splice(0)) entry.resolve({ outcome: "promptRequired", reason: "turnEnded" });
    this.pi.request({ type: "clear_queue" }).catch(() => {});
  }

  cancel() {
    const turn = this.turn;
    if (!turn) return;
    turn.cancelled = true;
    // Before the prompt went out there is nothing to abort: the turn ends as cancelled.
    if (!turn.sent) return;
    // pi goes on with queued messages after `abort`: steered messages go back to the client first.
    if (turn.steers.length > 0) {
      for (const entry of turn.steers.splice(0)) entry.resolve({ outcome: "promptRequired", reason: "cancelled" });
      this.pi.request({ type: "clear_queue" }).catch(() => {});
    }
    this.pi.request({ type: "abort" }).then(
      () => setTimeout(() => turn.settle(), ABORT_GRACE_MS).unref(),
      () => turn.settle(),
    );
  }

  close() {
    this.pi.stop();
  }
}

function providerError(message, assistant) {
  const data = { message: String(message) };
  if (assistant?.provider) data.provider = assistant.provider;
  if (assistant?.model) data.model = assistant.model;
  return new AcpError(isAuthError(message) ? AUTH_REQUIRED : INTERNAL_ERROR, String(message), data);
}

/** The ACP agent: requests from the client, notifications to it. */
export class Agent {
  constructor(write, log) {
    this.write = write;
    this.log = log;
    this.sessions = new Map();
  }

  notify(params) {
    this.write({ jsonrpc: "2.0", method: "session/update", params });
  }

  async handle(message) {
    const { id, method, params } = message;
    if (method === undefined) return; // A response: this agent sends no requests.
    try {
      const result = await this.dispatch(method, params ?? {});
      if (id !== undefined) this.write({ jsonrpc: "2.0", id, result: result ?? null });
    } catch (error) {
      if (id === undefined) {
        this.log(`${method}: ${error.message}`);
        return;
      }
      const code = error instanceof AcpError ? error.code : INTERNAL_ERROR;
      this.write({
        jsonrpc: "2.0",
        id,
        error: { code, message: error.message, ...(error.data ? { data: error.data } : {}) },
      });
    }
  }

  async dispatch(method, params) {
    switch (method) {
      case "initialize":
        return {
          protocolVersion: PROTOCOL_VERSION,
          agentCapabilities: {
            loadSession: true,
            mcpCapabilities: { http: true, sse: false },
            promptCapabilities: { image: true, audio: false, embeddedContext: true },
          },
          authMethods: [],
          agentInfo: { name: "tinfirm-pi-acp", title: "pi (Tinfirm)", version: ADAPTER_VERSION },
          _meta: { steering: { supported: true } },
        };
      case "authenticate":
        return {};
      case "session/new": {
        const sessionId = randomUUID();
        await this.open(sessionId, params, false);
        return { sessionId };
      }
      case "session/load": {
        const sessionId = params.sessionId;
        if (typeof sessionId !== "string" || !/^[A-Za-z0-9._-]{1,200}$/.test(sessionId)) {
          throw new AcpError(INVALID_PARAMS, "sessionId is not a pi session ID");
        }
        const session = await this.open(sessionId, params, true);
        const state = await session.pi.call({ type: "get_state" }).catch(() => null);
        if (!state?.messageCount) {
          this.close(sessionId);
          throw new AcpError(RESOURCE_NOT_FOUND, `session ${sessionId} not found`);
        }
        return {};
      }
      case "session/prompt": {
        const session = this.sessions.get(params.sessionId);
        if (!session) throw new AcpError(RESOURCE_NOT_FOUND, `session ${params.sessionId} not found`);
        return session.prompt(params.prompt);
      }
      case "session/cancel":
        this.sessions.get(params.sessionId)?.cancel();
        return null;
      case "_session/steering": {
        const session = this.sessions.get(params.sessionId);
        if (!session) throw new AcpError(RESOURCE_NOT_FOUND, `session ${params.sessionId} not found`);
        if (!Array.isArray(params.prompt) || params.prompt.length === 0) {
          throw new AcpError(INVALID_PARAMS, "steer params require a non-empty prompt array");
        }
        return session.steer(params.prompt);
      }
      default:
        throw new AcpError(METHOD_NOT_FOUND, `method not found: ${method}`);
    }
  }

  async open(sessionId, params, resume) {
    const cwd = typeof params.cwd === "string" && params.cwd ? params.cwd : process.cwd();
    this.close(sessionId);
    configurePi(params.mcpServers);
    const pi = new PiProcess(cwd, piArgs(sessionId, process.env, { resume }), this.log);
    const session = new Session(sessionId, cwd, pi, (update) => this.notify(update), this.log);
    this.sessions.set(sessionId, session);
    // pi answers once it has loaded its configuration and session; a pi that cannot start fails here.
    await pi.call({ type: "get_state" });
    return session;
  }

  close(sessionId) {
    this.sessions.get(sessionId)?.close();
    this.sessions.delete(sessionId);
  }

  closeAll() {
    for (const id of [...this.sessions.keys()]) this.close(id);
  }
}

/** Runs the adapter on this process's stdio. */
export function main() {
  const write = (message) => process.stdout.write(`${JSON.stringify(message)}\n`);
  const log = (text) => process.stderr.write(`${text}\n`);
  const agent = new Agent(write, log);
  readLines(
    process.stdin,
    (line) => {
      let message;
      try {
        message = JSON.parse(line);
      } catch {
        write({ jsonrpc: "2.0", id: null, error: { code: -32700, message: "parse error" } });
        return;
      }
      agent.handle(message);
    },
    () => {
      agent.closeAll();
      setTimeout(() => process.exit(0), 2_500).unref();
    },
  );
  for (const signal of ["SIGTERM", "SIGINT"]) {
    process.on(signal, () => {
      agent.closeAll();
      setTimeout(() => process.exit(0), 2_500).unref();
    });
  }
}

/** Whether this module is the program (`node tinfirm-pi-acp.mjs`), not imported by a test. */
function isProgram() {
  try {
    return realpathSync(process.argv[1] ?? "") === realpathSync(fileURLToPath(import.meta.url));
  } catch {
    return false;
  }
}

if (isProgram()) {
  main();
}
