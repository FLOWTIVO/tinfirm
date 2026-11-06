#!/usr/bin/env node
// A stand-in for `pi --mode rpc` in the tests of tinfirm-pi-acp.mjs: the commands and events the
// adapter uses, with sessions kept as JSON files in PI_CODING_AGENT_DIR/fake-sessions.
// FAKE_PI_SCENARIO picks what a prompt does: `ok` (text, thinking, a tool call, an MCP tool call),
// `auth-error`, `rate-limit`, `length`, `slow` (waits for `abort`), `crash` (exits mid-turn),
// `steer` (a tool call, then waits for a `steer`, takes it into the turn and answers it) and
// `steer-ignored` (waits for a `steer`, queues it and ends the turn without it).
// It records its arguments and the agent directory's mcp.json and models.json in
// PI_CODING_AGENT_DIR/fake-pi-log.jsonl.

import { appendFileSync, existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";

const dir = process.env.PI_CODING_AGENT_DIR;
const scenario = process.env.FAKE_PI_SCENARIO ?? "ok";
const args = process.argv.slice(2);
const sessionId = args[args.indexOf("--session-id") + 1];
const sessions = join(dir, "fake-sessions");
mkdirSync(sessions, { recursive: true });
const file = join(sessions, `${sessionId}.json`);
const session = existsSync(file)
  ? JSON.parse(readFileSync(file, "utf8"))
  : { messageCount: 0, tokens: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 }, cost: 0 };
const read = (name) => (existsSync(join(dir, name)) ? JSON.parse(readFileSync(join(dir, name), "utf8")) : null);
appendFileSync(
  join(dir, "fake-pi-log.jsonl"),
  `${JSON.stringify({ args, cwd: process.cwd(), mcp: read("mcp.json"), models: read("models.json") })}\n`,
);
process.stderr.write(`fake pi started with ${args.join(" ")}\n`);

const out = (record) => process.stdout.write(`${JSON.stringify(record)}\n`);
const save = () => writeFileSync(file, JSON.stringify(session));
let abort = null;
let steered = null;
const queue = [];
const log = (record) => appendFileSync(join(dir, "fake-pi-log.jsonl"), `${JSON.stringify(record)}\n`);

function addTokens(input, output, cacheRead, cacheWrite, cost) {
  const t = session.tokens;
  t.input += input;
  t.output += output;
  t.cacheRead += cacheRead;
  t.cacheWrite += cacheWrite;
  t.total = t.input + t.output + t.cacheRead + t.cacheWrite;
  session.cost += cost;
}

function assistant(stopReason, extra = {}) {
  return { role: "assistant", content: [], provider: "anthropic", model: "claude-test", stopReason, ...extra };
}

async function runPrompt(message) {
  session.messageCount += 2;
  out({ type: "agent_start" });
  out({ type: "message_start", message: { role: "user", content: message } });
  switch (scenario) {
    case "auth-error":
      out({ type: "message_end", message: assistant("error", { errorMessage: '401 {"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}' }) });
      break;
    case "rate-limit":
      out({ type: "auto_retry_start", attempt: 1, maxAttempts: 3, delayMs: 10, errorMessage: "429 rate limit" });
      out({ type: "auto_retry_end", success: false, attempt: 3, finalError: "429 rate limit exceeded" });
      out({ type: "message_end", message: assistant("error", { errorMessage: "429 rate limit exceeded" }) });
      break;
    case "length":
      out({ type: "message_update", assistantMessageEvent: { type: "text_delta", contentIndex: 0, delta: "partial" } });
      out({ type: "message_end", message: assistant("length") });
      addTokens(10, 5, 0, 0, 0.001);
      break;
    case "slow":
      await new Promise((resolve) => {
        abort = resolve;
      });
      out({ type: "message_end", message: assistant("aborted") });
      break;
    case "steer":
    case "steer-ignored": {
      out({ type: "tool_execution_start", toolCallId: "call-1", toolName: "bash", args: { command: "make" } });
      out({ type: "tool_execution_end", toolCallId: "call-1", toolName: "bash", result: { content: [{ type: "text", text: "ok" }] }, isError: false });
      const text = await new Promise((resolve) => {
        steered = resolve;
        abort = () => resolve(null);
      });
      if (text !== null && scenario === "steer") {
        // pi takes the queued message into the turn: its user message starts, the queue empties.
        queue.splice(queue.indexOf(text), 1);
        out({ type: "message_start", message: { role: "user", content: [{ type: "text", text }] } });
        out({ type: "queue_update", steering: [...queue], followUp: [] });
        out({ type: "message_update", assistantMessageEvent: { type: "text_delta", contentIndex: 0, delta: `Steered: ${text}` } });
      }
      out({ type: "message_end", message: assistant(text === null ? "aborted" : "stop") });
      break;
    }
    case "crash":
      process.stderr.write("fake pi: out of memory\n");
      process.exit(3);
      break;
    default:
      out({ type: "message_update", assistantMessageEvent: { type: "thinking_delta", contentIndex: 0, delta: "Let me look." } });
      out({ type: "tool_execution_start", toolCallId: "call-1", toolName: "read", args: { path: "README.md" } });
      out({ type: "tool_execution_update", toolCallId: "call-1", toolName: "read", args: { path: "README.md" }, partialResult: {} });
      out({ type: "tool_execution_end", toolCallId: "call-1", toolName: "read", result: { content: [{ type: "text", text: "# Readme" }] }, isError: false });
      out({ type: "tool_execution_start", toolCallId: "call-2", toolName: "mcp__tinfirm__finish", args: { outcome: "COMPLETED", summary: "done" } });
      out({ type: "tool_execution_end", toolCallId: "call-2", toolName: "mcp__tinfirm__finish", result: { content: [{ type: "text", text: "stop now" }] }, isError: false });
      out({ type: "message_update", assistantMessageEvent: { type: "text_delta", contentIndex: 1, delta: "All " } });
      out({ type: "message_update", assistantMessageEvent: { type: "text_delta", contentIndex: 1, delta: "done." } });
      out({ type: "message_end", message: assistant("stop") });
      addTokens(1200, 300, 5000, 400, 0.05);
      break;
  }
  save();
  out({ type: "agent_end", messages: [], willRetry: false });
  out({ type: "agent_settled" });
}

let buffer = "";
process.stdin.on("data", (chunk) => {
  buffer += chunk;
  let index;
  while ((index = buffer.indexOf("\n")) !== -1) {
    const line = buffer.slice(0, index);
    buffer = buffer.slice(index + 1);
    if (!line.trim()) continue;
    const command = JSON.parse(line);
    const reply = (data, success = true, error) =>
      out({ id: command.id, type: "response", command: command.type, success, ...(data !== undefined ? { data } : {}), ...(error ? { error } : {}) });
    switch (command.type) {
      case "get_state":
        reply({ sessionId, messageCount: session.messageCount, isStreaming: false });
        break;
      case "get_session_stats":
        reply({
          sessionId,
          tokens: { ...session.tokens },
          cost: session.cost,
          contextUsage: { tokens: 6600, contextWindow: 200000, percent: 3 },
        });
        break;
      case "prompt":
        reply({ disposition: "started" });
        setImmediate(() => runPrompt(command.message));
        break;
      case "abort":
        abort?.();
        reply(undefined);
        break;
      case "steer":
        queue.push(command.message);
        reply({ disposition: "queued" });
        out({ type: "queue_update", steering: [...queue], followUp: [] });
        setImmediate(() => steered?.(command.message));
        break;
      case "clear_queue": {
        const cleared = queue.splice(0);
        log({ cleared });
        reply({ steering: cleared, followUp: [] });
        break;
      }
      default:
        reply(undefined, false, `unknown command ${command.type}`);
    }
  }
});
process.stdin.on("end", () => process.exit(0));
