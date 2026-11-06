/**
 * A runner host of protocol v2 in Node: a port of the server's test FakeRunner
 * (server/src/test/java/.../support/FakeRunner.java) that a test drives instead of a sandbox. It connects to
 * `/api/runner/v1/connect` with a company host's token, sends `hello` as runner 0.2.0 (the first version a budget's
 * run is sent to; its model requests carry the host's token all the same, as 0.3.0's do, since the relay requires
 * it by default), answers every `host.config` with `host.config.applied`, sends heartbeats for its running runs,
 * reconnects with its active runs after a dropped connection, and accepts every `run.assign` at once.
 *
 * What a run then does is scripted, by a marker in the person's message (the prompt's `# Request`) or by the
 * control API:
 *
 * | Marker                       | The run                                                                       |
 * |------------------------------|-------------------------------------------------------------------------------|
 * | none, or `[e2e:hold]`        | Stays running until a test finishes it (the default; see `defaultScript`)    |
 * | `[e2e:answer]`               | Answers and finishes `SUCCEEDED` with a `COMPLETED` result                   |
 * | `[e2e:ask]`                  | Asks a question with two options and ends its turn                          |
 * | `[e2e:approval]`             | Calls a tool that needs approval and ends its turn                          |
 * | `[e2e:tool:<name>]`          | Calls the server-executed tool `<name>` (e.g. `jira_get`), then finishes     |
 * | `[e2e:repository:<name>]`    | Requests a repository (`request_access`) and ends its turn                   |
 * | `[e2e:network:<host>]`       | Requests a network host and ends its turn                                    |
 * | `[e2e:push]`                 | Finishes with every writable repository pushed (a change request follows)   |
 * | `[e2e:blocked]`              | Finishes with a `BLOCKED` result and an open question                       |
 * | `[e2e:model]`                | Sends one model request through the server's relay, then answers            |
 * | `[e2e:fail]`, `[e2e:fail:<CLASS>]` | Finishes `FAILED`, by default with `USAGE_LIMIT`                       |
 *
 * TEST FIXTURE ONLY.
 */
import { randomUUID } from 'node:crypto';
import { sleep } from './http-util.ts';

export type Json = Record<string, unknown>;

export type RunState = 'running' | 'finished' | 'cancelled';

export interface RunRequestRecord {
  requestId: string;
  kind: 'ASK' | 'ACCESS' | 'ARTIFACT' | 'NETWORK' | 'TOOL';
  payload: Json;
  ack?: Json;
}

export interface RunnerRun {
  runId: string;
  epoch: number;
  /** `workItem.id` and `job.id` of run.assign: the conversation's id. */
  conversationId: string;
  key: string;
  title: string;
  agent: { id: string; name: string; runtime: string; model?: string | null; effort?: string };
  prompt: string;
  /** The prompt's `# Request` section: the person's messages this turn answers. */
  request: string;
  /** The script the marker chose, or the default. */
  script: string;
  scriptArg: string | null;
  tools: { name: string; description: string }[];
  platformTools: string[] | null;
  workspaces: {
    componentId: string;
    dir: string;
    name: string;
    branch: string;
    readOnly: boolean;
    hasGitToken: boolean;
  }[];
  allowedHosts: string[];
  /** Whether run.assign carried the relay's root and a one-run token (a budget's run), and the root. */
  modelAccess: { format: string; baseUrl: string | null } | null;
  credentialLabel: string | null;
  state: RunState;
  seq: number;
  assignedAt: string;
  finishedAt: string | null;
  outcome: string | null;
  requests: RunRequestRecord[];
  /** Notes of what the script did, for the status page and failed tests. */
  log: string[];
}

export interface FinishOptions {
  outcome?: 'SUCCEEDED' | 'FAILED' | 'CANCELLED';
  /** The agent's last message; also sent as an AGENT_MESSAGE event. */
  summary?: string | null;
  /** The `finish` call's result; null for none. Defaults to a COMPLETED result with the summary. */
  result?: {
    outcome: 'COMPLETED' | 'BLOCKED';
    summary: string;
    nextSteps?: string[];
    openQuestions?: string[];
  } | null;
  failureClass?: string | null;
  failureMessage?: string | null;
  /** Report every repository the run may write as pushed, one commit ahead. */
  pushed?: boolean;
  costUsd?: number;
}

export interface RunnerStatus {
  connected: boolean;
  runnerHostId: string | null;
  companyId: string | null;
  welcomedAt: string | null;
  configVersion: number;
  lastClose: { code: number; reason: string; at: string } | null;
  errors: Json[];
  defaultScript: string;
  runs: number;
}

const RUNTIME_IMAGES: Record<string, string> = {
  CLAUDE_CODE: 'tinfirm-runner-claude:local',
  CODEX: 'tinfirm-runner-codex:local',
  PI: 'tinfirm-runner-pi:local',
  OPENCODE: 'tinfirm-runner-opencode:local',
};

const SCRIPTS = new Set([
  'hold',
  'answer',
  'ask',
  'approval',
  'tool',
  'repository',
  'network',
  'push',
  'blocked',
  'model',
  'fail',
]);

const MARKER = /\[e2e:([a-z]+)(?::([^\]\s]+))?\]/i;

/** The prompt's `# Request` section: from its heading to the next top-level heading. */
export function requestSection(prompt: string): string {
  const lines = prompt.split('\n');
  const start = lines.findIndex((line) => line.trim() === '# Request');
  if (start < 0) return '';
  const rest = lines.slice(start + 1);
  const end = rest.findIndex((line) => /^# /.test(line));
  return (end < 0 ? rest : rest.slice(0, end)).join('\n').trim();
}

export function scriptOf(
  request: string,
  fallback: string,
): { script: string; arg: string | null } {
  const found = MARKER.exec(request);
  if (found && SCRIPTS.has(found[1].toLowerCase()))
    return { script: found[1].toLowerCase(), arg: found[2] ?? null };
  return { script: fallback, arg: null };
}

export class ScriptedRunner {
  /** The script of a run whose request names none. `hold` waits for the test; `answer` answers every turn. */
  defaultScript = 'hold';

  private readonly serverUrl: string;
  private readonly version: string;
  private socket: WebSocket | null = null;
  private token: string | null = null;
  private wanted = false;
  private reconnecting = false;
  private heartbeat: ReturnType<typeof setInterval> | null = null;
  private heartbeatSeconds = 10;
  private pslSha256: string | null = null;
  private appliedVersion = -1;
  private readonly ceiling = 16;
  private readonly runsById = new Map<string, RunnerRun>();
  private readonly secrets = new Map<string, { apiKey: string | null }>();
  private readonly pending = new Map<string, (ack: Json) => void>();
  private readonly waiters: (() => void)[] = [];
  private readonly status_: RunnerStatus = {
    connected: false,
    runnerHostId: null,
    companyId: null,
    welcomedAt: null,
    configVersion: -1,
    lastClose: null,
    errors: [],
    defaultScript: 'hold',
    runs: 0,
  };

  constructor(serverUrl: string, version: string) {
    this.serverUrl = serverUrl;
    this.version = version;
  }

  status(): RunnerStatus {
    return {
      ...this.status_,
      defaultScript: this.defaultScript,
      runs: this.runsById.size,
      errors: [...this.status_.errors],
    };
  }

  runs(filter: { conversationId?: string; state?: RunState } = {}): RunnerRun[] {
    return [...this.runsById.values()].filter(
      (run) =>
        (!filter.conversationId || run.conversationId === filter.conversationId) &&
        (!filter.state || run.state === filter.state),
    );
  }

  run(runId: string): RunnerRun {
    const run = this.runsById.get(runId);
    if (!run) throw new Error(`The runner was assigned no run ${runId}`);
    return run;
  }

  /** Waits for a run of the conversation (the newest first) in the state, or any run when no filter is given. */
  async awaitRun(
    filter: { conversationId?: string; runId?: string; state?: RunState },
    timeoutMs = 30_000,
  ): Promise<RunnerRun> {
    const deadline = Date.now() + timeoutMs;
    for (;;) {
      const found = [...this.runsById.values()]
        .reverse()
        .find(
          (run) =>
            (!filter.conversationId || run.conversationId === filter.conversationId) &&
            (!filter.runId || run.runId === filter.runId) &&
            (!filter.state || run.state === filter.state),
        );
      if (found) return found;
      const left = deadline - Date.now();
      if (left <= 0) {
        throw new Error(
          `No run ${JSON.stringify(filter)} within ${timeoutMs} ms; the runner has ${this.runsById.size} runs`,
        );
      }
      await new Promise<void>((resolvePromise) => {
        const timer = setTimeout(resolvePromise, Math.min(left, 1000));
        this.waiters.push(() => {
          clearTimeout(timer);
          resolvePromise();
        });
      });
    }
  }

  // ---- the connection ---------------------------------------------------------------------------------------------

  /** Connects with the host's token (closing an earlier connection) and waits for `welcome`. */
  async connect(token: string): Promise<RunnerStatus> {
    this.wanted = true;
    this.token = token;
    this.close();
    await this.open();
    return this.status();
  }

  /** Closes the connection for good: no reconnect. */
  disconnect(): void {
    this.wanted = false;
    this.close();
  }

  private close(): void {
    if (this.heartbeat) clearInterval(this.heartbeat);
    this.heartbeat = null;
    const socket = this.socket;
    this.socket = null;
    if (socket && socket.readyState <= 1) socket.close(1000, 'done');
    this.status_.connected = false;
  }

  private open(): Promise<void> {
    const url = `${this.serverUrl.replace(/^http/, 'ws')}/api/runner/v1/connect`;
    // Node's WebSocket (undici) takes headers beside the protocols; the browser's API does not.
    const init = { headers: { Authorization: `Bearer ${this.token}` } } as unknown as string[];
    const socket = new WebSocket(url, init);
    this.socket = socket;
    this.appliedVersion = -1;
    return new Promise((resolvePromise, reject) => {
      let welcomed = false;
      const timer = setTimeout(() => {
        if (!welcomed) reject(new Error('The server sent no welcome within 15 s'));
      }, 15_000);
      socket.onopen = () => this.send(this.hello());
      socket.onmessage = (event) => {
        let frame: Json;
        try {
          frame = JSON.parse(String(event.data)) as Json;
        } catch {
          return;
        }
        if (frame['type'] === 'welcome' && !welcomed) {
          welcomed = true;
          clearTimeout(timer);
          this.onWelcome(frame);
          resolvePromise();
          return;
        }
        this.onFrame(frame);
      };
      socket.onerror = () => {
        // The close event follows with the code.
      };
      socket.onclose = (event) => {
        clearTimeout(timer);
        if (this.socket === socket) {
          this.status_.connected = false;
          this.status_.lastClose = {
            code: event.code,
            reason: event.reason,
            at: new Date().toISOString(),
          };
          if (this.heartbeat) clearInterval(this.heartbeat);
          this.heartbeat = null;
          this.socket = null;
          // 4401: a bad or revoked token; 4409: replaced by a newer connection of the same host.
          if (this.wanted && event.code !== 4401 && event.code !== 4409) void this.reconnect();
        }
        if (!welcomed)
          reject(new Error(`The server closed the connection (${event.code} ${event.reason})`));
      };
    });
  }

  private async reconnect(): Promise<void> {
    if (this.reconnecting) return;
    this.reconnecting = true;
    try {
      for (let attempt = 0; this.wanted && !this.status_.connected; attempt++) {
        await sleep(Math.min(5000, 500 * 2 ** attempt));
        if (!this.wanted || this.socket) break;
        try {
          await this.open();
        } catch {
          // Tried again.
        }
      }
    } finally {
      this.reconnecting = false;
    }
  }

  private hello(): Json {
    const activeRuns = this.runs({ state: 'running' }).map((run) => ({
      runId: run.runId,
      epoch: run.epoch,
      lastSeq: run.seq,
    }));
    return {
      type: 'hello',
      protocolVersion: 2,
      runnerVersion: this.version,
      hostname: 'e2e-runner',
      os: 'linux',
      arch: 'x86_64',
      executors: ['docker'],
      isolationTiers: ['container'],
      maxConcurrentRuns: this.ceiling,
      credentials: [],
      runtimes: Object.entries(RUNTIME_IMAGES).map(([runtime, image]) => ({
        runtime,
        image,
        version: 'e2e',
      })),
      activeRuns,
      ...(this.pslSha256 ? { publicSuffixList: { sha256: this.pslSha256 } } : {}),
    };
  }

  private onWelcome(frame: Json): void {
    this.status_.connected = true;
    this.status_.runnerHostId = String(frame['runnerHostId']);
    this.status_.companyId = String(frame['companyId']);
    this.status_.welcomedAt = new Date().toISOString();
    this.heartbeatSeconds = Number(frame['heartbeatIntervalSeconds'] ?? 10);
    this.heartbeat = setInterval(
      () => {
        const runs = this.runs({ state: 'running' }).map((run) => ({
          runId: run.runId,
          epoch: run.epoch,
        }));
        if (runs.length > 0) this.send({ type: 'run.heartbeat', runs });
      },
      Math.max(1, this.heartbeatSeconds) * 1000,
    );
  }

  private send(frame: Json): void {
    const socket = this.socket;
    if (!socket || socket.readyState !== 1)
      throw new Error(`The runner is not connected; cannot send ${String(frame['type'])}`);
    socket.send(JSON.stringify(frame));
  }

  private notify(): void {
    for (const waiter of this.waiters.splice(0)) waiter();
  }

  private onFrame(frame: Json): void {
    switch (frame['type']) {
      case 'host.config':
        return this.onHostConfig(frame);
      case 'psl.update':
        this.pslSha256 = String(frame['sha256']);
        return;
      case 'run.assign':
        return this.onAssign(frame);
      case 'run.cancel':
        return this.onCancel(frame);
      case 'run.request.ack': {
        const resolveAck = this.pending.get(String(frame['requestId']));
        if (resolveAck) {
          this.pending.delete(String(frame['requestId']));
          resolveAck(frame);
        }
        return;
      }
      case 'error':
        this.status_.errors.push({ ...frame, at: new Date().toISOString() });
        if (this.status_.errors.length > 50) this.status_.errors.shift();
        return;
      default:
        return;
    }
  }

  private onHostConfig(frame: Json): void {
    const version = Number(frame['version'] ?? 0);
    if (version < this.appliedVersion) return;
    this.appliedVersion = version;
    this.status_.configVersion = version;
    const desired = frame['desiredMaxConcurrentRuns'];
    this.send({
      type: 'host.config.applied',
      version,
      draining: false,
      maxConcurrentRuns:
        typeof desired === 'number' ? Math.min(desired, this.ceiling) : this.ceiling,
      maxConcurrentRunsCeiling: this.ceiling,
      credentials: [],
    });
  }

  private onAssign(frame: Json): void {
    const runId = String(frame['runId']);
    const epoch = Number(frame['epoch']);
    const known = this.runsById.get(runId);
    if (known && known.state === 'running' && known.epoch === epoch) {
      this.send({ type: 'run.accepted', runId, epoch });
      return;
    }
    const prompt = String(frame['prompt'] ?? '');
    const request = requestSection(prompt);
    const { script, arg } = scriptOf(request, this.defaultScript);
    const modelAccess = frame['modelAccess'] as Json | undefined;
    const workItem = (frame['workItem'] ?? {}) as Json;
    const run: RunnerRun = {
      runId,
      epoch,
      conversationId: String(workItem['id'] ?? ''),
      key: String(workItem['key'] ?? ''),
      title: String(workItem['title'] ?? ''),
      agent: frame['agent'] as RunnerRun['agent'],
      prompt,
      request,
      script,
      scriptArg: arg,
      tools: ((frame['tools'] as Json[] | undefined) ?? []).map((tool) => ({
        name: String(tool['name']),
        description: String(tool['description'] ?? ''),
      })),
      platformTools: (frame['platformTools'] as string[] | undefined) ?? null,
      workspaces: ((frame['workspaces'] as Json[] | undefined) ?? []).map((workspace) => ({
        componentId: String(workspace['componentId']),
        dir: String(workspace['dir']),
        name: String(workspace['name']),
        branch: String(workspace['branch']),
        readOnly: workspace['readOnly'] === true,
        hasGitToken: !!(workspace['git'] as Json | null | undefined)?.['token'],
      })),
      allowedHosts: (frame['allowedHosts'] as string[] | undefined) ?? [],
      modelAccess: modelAccess
        ? {
            format: String(modelAccess['format']),
            baseUrl: (modelAccess['baseUrl'] as string | undefined) ?? null,
          }
        : null,
      credentialLabel: (frame['credentialLabel'] as string | undefined) ?? null,
      state: 'running',
      seq: 0,
      assignedAt: new Date().toISOString(),
      finishedAt: null,
      outcome: null,
      requests: [],
      log: [],
    };
    this.runsById.set(runId, run);
    this.secrets.set(runId, { apiKey: (modelAccess?.['apiKey'] as string | undefined) ?? null });
    this.send({ type: 'run.accepted', runId, epoch });
    this.event(runId, 'STATUS', { phase: 'running', message: 'The e2e runner works on it' });
    run.log.push(`accepted, script ${script}${arg ? `:${arg}` : ''}`);
    this.notify();
    void this.play(run).catch((error: unknown) => {
      run.log.push(`script failed: ${String((error as Error)?.message ?? error)}`);
    });
  }

  private onCancel(frame: Json): void {
    const run = this.runsById.get(String(frame['runId']));
    if (!run || run.state !== 'running' || run.epoch !== Number(frame['epoch'])) return;
    run.log.push('cancelled by the server');
    this.finish(run.runId, { outcome: 'CANCELLED', summary: null, result: null });
    run.state = 'cancelled';
    this.notify();
  }

  // ---- what a run does ----------------------------------------------------------------------------------------

  /** Plays the run's script; `hold` does nothing and leaves the run to the test. */
  private async play(run: RunnerRun): Promise<void> {
    await sleep(150);
    switch (run.script) {
      case 'hold':
        return;
      case 'answer':
        this.message(run.runId, `Done: ${firstLine(run.request)}`);
        this.finish(run.runId, {});
        return;
      case 'ask':
        await this.ask(run.runId, {
          question: 'Which issue should I start with?',
          options: ['SHOP-1', 'SHOP-2'],
        });
        return;
      case 'approval':
        await this.approval(run.runId, { tool: run.scriptArg ?? undefined });
        return;
      case 'tool': {
        const tool = run.scriptArg ?? run.tools.find((entry) => entry.name.endsWith('_get'))?.name;
        if (!tool) throw new Error('The run lists no tool to call');
        await this.tool(run.runId, { tool, arguments: defaultArguments(tool), endTurn: true });
        return;
      }
      case 'repository':
        await this.access(run.runId, {
          repository: run.scriptArg ?? 'acme/shop',
          reason: 'The e2e run needs it',
        });
        return;
      case 'network':
        await this.network(run.runId, {
          host: run.scriptArg ?? 'registry.npmjs.org',
          reason: 'The e2e run needs it',
        });
        return;
      case 'push':
        this.message(run.runId, 'I pushed the change.');
        this.finish(run.runId, { pushed: true, summary: 'Pushed the change' });
        return;
      case 'blocked':
        this.finish(run.runId, {
          summary: 'I need a decision before I go on.',
          result: {
            outcome: 'BLOCKED',
            summary: 'Blocked on a decision',
            openQuestions: ['Should I change the API too?'],
          },
        });
        return;
      case 'model': {
        const answer = await this.callModel(run.runId);
        this.finish(run.runId, { summary: `The model answered ${answer.status}` });
        return;
      }
      case 'fail':
        this.finish(run.runId, {
          outcome: 'FAILED',
          failureClass: run.scriptArg ?? 'USAGE_LIMIT',
          failureMessage: `The e2e runner failed the run (${run.scriptArg ?? 'USAGE_LIMIT'})`,
          summary: null,
          result: null,
        });
        return;
      default:
        return;
    }
  }

  /** Sends a run event; answers its sequence number. */
  event(runId: string, eventType: string, payload: Json): number {
    const run = this.active(runId);
    run.seq += 1;
    this.send({
      type: 'run.event',
      runId,
      epoch: run.epoch,
      seq: run.seq,
      eventType,
      at: new Date().toISOString(),
      payload,
    });
    return run.seq;
  }

  /** An AGENT_MESSAGE event: what the agent says while it works. */
  message(runId: string, text: string): number {
    return this.event(runId, 'AGENT_MESSAGE', { text });
  }

  /** Ends the run with `run.finished`: by default SUCCEEDED with a COMPLETED result. */
  finish(runId: string, options: FinishOptions): RunnerRun {
    const run = this.active(runId);
    const outcome = options.outcome ?? 'SUCCEEDED';
    const summary =
      options.summary === undefined
        ? `Done: ${firstLine(run.request) || run.title}`
        : options.summary;
    const result =
      options.result !== undefined
        ? options.result
        : outcome === 'SUCCEEDED' && summary
          ? { outcome: 'COMPLETED' as const, summary }
          : null;
    if (summary && outcome !== 'CANCELLED') this.message(runId, summary);
    const usage = {
      inputTokens: 1200,
      outputTokens: 300,
      costUsd: options.costUsd ?? 0.01,
      totalTokens: 1500,
    };
    this.event(runId, 'USAGE', usage);
    this.send({
      type: 'run.finished',
      runId,
      epoch: run.epoch,
      outcome,
      lastSeq: run.seq,
      failureClass: options.failureClass ?? null,
      failureMessage: options.failureMessage ?? null,
      summary,
      result,
      sessionId: null,
      git: run.workspaces.map((workspace) => {
        const pushed = !!options.pushed && !workspace.readOnly;
        return {
          componentId: workspace.componentId,
          branch: workspace.branch,
          pushed,
          headSha: pushed ? 'e2e0000000000000000000000000000000000001' : null,
          commits: pushed ? 1 : 0,
        };
      }),
      usage,
      artifacts: [],
      resumed: false,
      resumeOutcome: 'FIRST_RUN',
    });
    run.state = outcome === 'CANCELLED' ? 'cancelled' : 'finished';
    run.outcome = outcome;
    run.finishedAt = new Date().toISOString();
    run.log.push(`finished ${outcome}${options.failureClass ? ` ${options.failureClass}` : ''}`);
    this.secrets.delete(runId);
    this.notify();
    return run;
  }

  /** A `run.request`; answers the server's ack. */
  async request(runId: string, kind: RunRequestRecord['kind'], payload: Json): Promise<Json> {
    const run = this.active(runId);
    const requestId = randomUUID();
    const record: RunRequestRecord = { requestId, kind, payload };
    run.requests.push(record);
    const ack = new Promise<Json>((resolvePromise, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(requestId);
        reject(new Error(`No run.request.ack of ${kind} ${requestId} within 70 s`));
      }, 70_000);
      this.pending.set(requestId, (frame) => {
        clearTimeout(timer);
        resolvePromise(frame);
      });
    });
    this.send({ type: 'run.request', runId, epoch: run.epoch, requestId, kind, payload });
    record.ack = await ack;
    run.log.push(
      `${kind} answered ${String(record.ack['status'])}${record.ack['code'] ? ` ${String(record.ack['code'])}` : ''}`,
    );
    return record.ack;
  }

  /**
   * The agent asks a person (`ask`); the server stores a QUESTION decision and answers PENDING. By default the agent
   * then ends its turn, as a real one does: the answer starts the next turn.
   */
  async ask(
    runId: string,
    input: { question: string; options?: string[]; endTurn?: boolean },
  ): Promise<Json> {
    const ack = await this.request(runId, 'ASK', {
      question: input.question,
      ...(input.options ? { options: input.options } : {}),
    });
    if (input.endTurn !== false && ack['status'] === 'PENDING')
      this.finish(runId, { summary: null, result: null });
    return ack;
  }

  /** A server-executed tool call (`run.request` TOOL): DONE with its result, PENDING for an approval, or REJECTED. */
  async tool(
    runId: string,
    input: { tool: string; arguments?: Json; endTurn?: boolean },
  ): Promise<Json> {
    const run = this.active(runId);
    this.event(runId, 'TOOL_CALL', {
      toolCallId: `e2e-${run.seq + 1}`,
      title: input.tool,
      kind: 'fetch',
      status: 'in_progress',
      input: input.arguments ?? {},
    });
    const ack = await this.request(runId, 'TOOL', {
      tool: input.tool,
      arguments: input.arguments ?? defaultArguments(input.tool),
    });
    if (input.endTurn) {
      if (ack['status'] === 'PENDING')
        this.finish(runId, { summary: `I asked to run ${input.tool}.` });
      else
        this.finish(runId, {
          summary: `${input.tool} answered ${String(ack['status'])}${ack['code'] ? ` (${String(ack['code'])})` : ''}.`,
        });
    }
    return ack;
  }

  /**
   * Asks for an approval: calls a tool whose mode is "Needs your approval" (by default the first
   * write of a tracker, e.g. `jira_comment`), which the server parks as an APPROVAL decision
   * (PENDING); then ends the turn.
   */
  async approval(
    runId: string,
    input: { tool?: string; arguments?: Json; endTurn?: boolean },
  ): Promise<Json> {
    const run = this.active(runId);
    const asks = (entry: { description: string }) => /approval/i.test(entry.description);
    const tool =
      input.tool ??
      run.tools.find((entry) => entry.name.endsWith('_comment') && asks(entry))?.name ??
      run.tools.find(asks)?.name ??
      run.tools.find((entry) => entry.name.endsWith('_comment'))?.name;
    if (!tool)
      throw new Error(
        `The run lists no tool that asks for an approval: ${run.tools.map((t) => t.name).join(', ')}`,
      );
    const ack = await this.tool(runId, {
      tool,
      arguments: input.arguments ?? defaultArguments(tool),
    });
    if (input.endTurn !== false) {
      this.finish(runId, {
        summary:
          ack['status'] === 'PENDING'
            ? `I asked to run ${tool}.`
            : `${tool} answered ${String(ack['status'])}.`,
      });
    }
    return ack;
  }

  /** `request_access` for a repository or a system's tools: an approval; the turn ends. */
  async access(
    runId: string,
    input: { repository?: string; tool?: string; reason: string; endTurn?: boolean },
  ): Promise<Json> {
    const payload: Json = {
      reason: input.reason,
      ...(input.repository ? { repository: input.repository } : { tool: input.tool }),
    };
    const ack = await this.request(runId, 'ACCESS', payload);
    if (input.endTurn !== false) this.finish(runId, { summary: null, result: null });
    return ack;
  }

  /** `request_network_access`: an approval of a host; the turn ends. */
  async network(
    runId: string,
    input: { host: string; reason: string; endTurn?: boolean },
  ): Promise<Json> {
    const ack = await this.request(runId, 'NETWORK', { host: input.host, reason: input.reason });
    if (input.endTurn !== false) this.finish(runId, { summary: null, result: null });
    return ack;
  }

  /**
   * One model request through the server's relay with the run's token, as the runtime would send it, and with this
   * host's own token in `X-Tinfirm-Runner-Token`, as the model proxy of runner host 0.3.0 and later sends it: the
   * relay requires it by default (`TINFIRM_MODEL_RELAY_REQUIRE_HOST_TOKEN`), whatever version the host said in `hello`.
   */
  async callModel(runId: string, body?: Json): Promise<{ status: number; body: unknown }> {
    const run = this.active(runId);
    const apiKey = this.secrets.get(runId)?.apiKey;
    if (!run.modelAccess?.baseUrl || !apiKey)
      throw new Error('The run has no model access through the relay');
    if (!this.token) throw new Error('The host is not connected');
    const anthropic = run.modelAccess.format === 'anthropic';
    const url = anthropic
      ? `${run.modelAccess.baseUrl}/v1/messages`
      : `${run.modelAccess.baseUrl}/responses`;
    const response = await fetch(url, {
      method: 'POST',
      headers: {
        authorization: `Bearer ${apiKey}`,
        'x-tinfirm-runner-token': this.token,
        'content-type': 'application/json',
        ...(anthropic ? { 'anthropic-version': '2023-06-01' } : {}),
      },
      body: JSON.stringify(
        body ?? {
          model: run.agent.model ?? 'claude-sonnet-4-5',
          max_tokens: 16,
          messages: [{ role: 'user', content: 'Hello' }],
        },
      ),
    });
    const text = await response.text();
    let parsed: unknown = text;
    try {
      parsed = JSON.parse(text);
    } catch {
      // Not JSON.
    }
    run.log.push(`model request answered ${response.status}`);
    return { status: response.status, body: parsed };
  }

  private active(runId: string): RunnerRun {
    const run = this.run(runId);
    if (run.state !== 'running') throw new Error(`Run ${runId} is ${run.state}, not running`);
    return run;
  }
}

function firstLine(request: string): string {
  const line = request
    .split('\n')
    .map((text) =>
      text
        .replace(/^>\s?/, '')
        .replace(/\[e2e:[^\]]*\]/gi, '')
        .trim(),
    )
    .find((text) => text && !/^\*\*|^_/.test(text));
  return (line ?? '').slice(0, 200);
}

/** Arguments a fixed tool accepts, by its name's suffix. */
function defaultArguments(tool: string): Json {
  const github = tool.startsWith('github');
  const key = github ? 'acme/shop#1' : 'SHOP-1';
  if (tool.endsWith('_search')) return { query: github ? 'is:open' : 'project = SHOP', limit: 5 };
  if (tool.endsWith('_comment')) return { key, body: 'A comment from the e2e runner.' };
  if (tool.endsWith('_transition')) return { key, transition: 'Done' };
  if (tool.endsWith('_label')) return { key, add: ['e2e'] };
  return { key };
}
