/**
 * The typed client of the harness's control API (control.ts): the scripted runner host's controls, the stubs' calls
 * and the fake LiteLLM. The fixtures hand it to the specs as `runner`, `stubs` and `litellm`.
 */
import type { FinishOptions, Json, RunnerRun, RunnerStatus, RunState } from './scripted-runner.ts';
import type { SlackPost } from './slack-stub.ts';
import type { JiraIssue, JiraIssueInput, StubCall, StubSystem } from './stubs.ts';

export type { FinishOptions, RunnerRun, RunnerStatus, RunState, SlackPost, StubCall, StubSystem };

/** A run.request.ack as the server sent it. */
export interface Ack {
  type: 'run.request.ack';
  runId: string;
  requestId: string;
  status: 'PENDING' | 'DONE' | 'REJECTED';
  payload?: Json | null;
  code?: string | null;
  message?: string | null;
}

async function call<T>(baseUrl: string, method: string, path: string, body?: unknown): Promise<T> {
  const response = await fetch(`${baseUrl}${path}`, {
    method,
    headers: body !== undefined ? { 'content-type': 'application/json' } : {},
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
  const text = await response.text();
  const parsed = text ? (JSON.parse(text) as unknown) : null;
  if (!response.ok) {
    const error = (parsed as { error?: string } | null)?.error ?? text;
    throw new Error(`control ${method} ${path} answered ${response.status}: ${error}`);
  }
  return parsed as T;
}

/** The scripted runner host (scripted-runner.ts) through the control API. */
export class RunnerControl {
  private readonly baseUrl: string;

  constructor(baseUrl: string) {
    this.baseUrl = baseUrl;
  }

  status(): Promise<RunnerStatus> {
    return call(this.baseUrl, 'GET', '/runner');
  }

  connect(token: string): Promise<RunnerStatus> {
    return call(this.baseUrl, 'POST', '/runner/connect', { token });
  }

  disconnect(): Promise<RunnerStatus> {
    return call(this.baseUrl, 'POST', '/runner/disconnect', {});
  }

  /** The script of runs whose request has no marker: `hold` (the default) or e.g. `answer`. */
  setDefaultScript(script: string): Promise<RunnerStatus> {
    return call(this.baseUrl, 'POST', '/runner/default-script', { script });
  }

  runs(filter: { conversationId?: string; state?: RunState } = {}): Promise<RunnerRun[]> {
    return call(
      this.baseUrl,
      'GET',
      `/runner/runs?${new URLSearchParams(filter as Record<string, string>)}`,
    );
  }

  /** Waits until the runner was assigned a run (of the conversation, in the state); the newest first. */
  awaitRun(
    filter: { conversationId?: string; runId?: string; state?: RunState },
    timeoutMs = 30_000,
  ): Promise<RunnerRun> {
    const query = new URLSearchParams({
      ...(filter as Record<string, string>),
      timeoutMs: String(timeoutMs),
    });
    return call(this.baseUrl, 'GET', `/runner/runs/await?${query}`);
  }

  run(runId: string): Promise<RunnerRun> {
    return call(this.baseUrl, 'GET', `/runner/runs/${runId}`);
  }

  /** Finishes the run: by default SUCCEEDED with a COMPLETED result. */
  finish(runId: string, options: FinishOptions = {}): Promise<RunnerRun> {
    return call(this.baseUrl, 'POST', `/runner/runs/${runId}/finish`, options);
  }

  /** What the agent says while it works (an AGENT_MESSAGE event). */
  message(runId: string, text: string): Promise<{ seq: number }> {
    return call(this.baseUrl, 'POST', `/runner/runs/${runId}/message`, { text });
  }

  event(runId: string, eventType: string, payload: Json): Promise<{ seq: number }> {
    return call(this.baseUrl, 'POST', `/runner/runs/${runId}/event`, { eventType, payload });
  }

  /** The agent asks a question (a QUESTION decision) and, unless `endTurn` is false, ends its turn. */
  ask(
    runId: string,
    input: { question: string; options?: string[]; endTurn?: boolean },
  ): Promise<Ack> {
    return call(this.baseUrl, 'POST', `/runner/runs/${runId}/ask`, input);
  }

  /** Calls a server-executed tool (`run.request` TOOL); `endTurn` finishes the run after the ack. */
  tool(runId: string, input: { tool: string; arguments?: Json; endTurn?: boolean }): Promise<Ack> {
    return call(this.baseUrl, 'POST', `/runner/runs/${runId}/tool`, input);
  }

  /** Requests an approval: a call of a tool that asks first (by default a tracker's comment); ends the turn. */
  approval(
    runId: string,
    input: { tool?: string; arguments?: Json; endTurn?: boolean } = {},
  ): Promise<Ack> {
    return call(this.baseUrl, 'POST', `/runner/runs/${runId}/approval`, input);
  }

  /** `request_access` for a repository (or a system's tools); ends the turn. */
  requestAccess(
    runId: string,
    input: { repository?: string; tool?: string; reason: string; endTurn?: boolean },
  ): Promise<Ack> {
    return call(this.baseUrl, 'POST', `/runner/runs/${runId}/access`, input);
  }

  /** `request_network_access` for a host; ends the turn. */
  requestNetwork(
    runId: string,
    input: { host: string; reason: string; endTurn?: boolean },
  ): Promise<Ack> {
    return call(this.baseUrl, 'POST', `/runner/runs/${runId}/network`, input);
  }

  /** One model request through the server's relay with the run's token. */
  callModel(runId: string, body?: Json): Promise<{ status: number; body: unknown }> {
    return call(this.baseUrl, 'POST', `/runner/runs/${runId}/model`, body ? { body } : {});
  }
}

/** The company's lock (company-lock.ts): the fixtures hold it around every test. */
export class LockControl {
  private readonly baseUrl: string;

  constructor(baseUrl: string) {
    this.baseUrl = baseUrl;
  }

  /** Waits until the owner holds the lock in the mode. */
  async acquire(owner: string, mode: 'shared' | 'exclusive'): Promise<void> {
    while (
      !(await call<{ held: boolean }>(this.baseUrl, 'POST', '/lock/acquire', { owner, mode })).held
    ) {
      // Asked again: the long poll ended before the lock was free.
    }
  }

  release(owner: string): Promise<unknown> {
    return call(this.baseUrl, 'POST', '/lock/release', { owner });
  }
}

/**
 * The Jira and GitHub stubs (stubs.ts). Each test has an instance of its own: `clearCalls()` marks where its calls
 * begin, and leaves the call log of the tests on other workers as it is.
 */
export class StubsControl {
  private readonly baseUrl: string;
  private seen = 0;

  constructor(baseUrl: string) {
    this.baseUrl = baseUrl;
  }

  /** The calls the stubs saw since `clearCalls()`, oldest first, each with the identity its token is. */
  async calls(system?: StubSystem): Promise<StubCall[]> {
    const calls = (await call<StubCall[]>(this.baseUrl, 'GET', '/stubs/calls')).slice(this.seen);
    return system ? calls.filter((stubCall) => stubCall.system === system) : calls;
  }

  /** From now on, `calls()` answers only the calls that come after this one. */
  async clearCalls(): Promise<void> {
    this.seen = (await call<StubCall[]>(this.baseUrl, 'GET', '/stubs/calls')).length;
  }

  /** Who the system's next OAuth sign-in is (an identity such as `dave`); `deny` refuses the sign-in. */
  setOAuthIdentity(system: StubSystem, identity: string): Promise<unknown> {
    return call(this.baseUrl, 'POST', '/stubs/oauth', { system, identity });
  }

  /** The system answers "who am I" for this token with this identity. */
  addIdentity(system: StubSystem, token: string, identity: string): Promise<unknown> {
    return call(this.baseUrl, 'POST', '/stubs/identities', { system, token, identity });
  }

  /** Creates or changes a Jira issue, as someone did just now (W5: a trigger's poll reads it). */
  putJiraIssue(input: JiraIssueInput): Promise<JiraIssue> {
    return call(this.baseUrl, 'POST', '/stubs/jira/issues', input);
  }

  /** A Jira issue as it stands, with its comments (Tinfirm's write-back among them). */
  jiraIssue(key: string): Promise<JiraIssue> {
    return call(this.baseUrl, 'GET', `/stubs/jira/issues/${encodeURIComponent(key)}`);
  }

  /** A comment on a Jira issue by a Jira user (a user name), just now. */
  commentJiraIssue(key: string, author: string, body: string): Promise<JiraIssue> {
    return call(this.baseUrl, 'POST', `/stubs/jira/issues/${encodeURIComponent(key)}/comments`, {
      author,
      body,
    });
  }
}

/** The Slack stub (slack-stub.ts): what the bot posted, and the names of its users. */
export class SlackControl {
  private readonly baseUrl: string;

  constructor(baseUrl: string) {
    this.baseUrl = baseUrl;
  }

  /** The bot's posts, oldest first; to one channel (a direct message is `D<user>`) when given. */
  posts(channel?: string): Promise<SlackPost[]> {
    return call(
      this.baseUrl,
      'GET',
      `/slack/posts${channel ? `?channel=${encodeURIComponent(channel)}` : ''}`,
    );
  }

  /** `users.info` names the user so. */
  setUser(id: string, name: string): Promise<unknown> {
    return call(this.baseUrl, 'POST', '/slack/users', { id, name });
  }
}

/** The fake LiteLLM (fake-litellm.ts). */
export class LiteLlmControl {
  private readonly baseUrl: string;

  constructor(baseUrl: string) {
    this.baseUrl = baseUrl;
  }

  keys(): Promise<
    {
      alias: string;
      spend: number;
      maxBudget: number;
      blocked: boolean;
      resetAt: string;
      models: string[];
    }[]
  > {
    return call(this.baseUrl, 'GET', '/litellm/keys');
  }

  /** Sets the spend of a principal's key (V7: at its budget, the next request is refused and the key is spent). */
  setSpend(
    principalId: string,
    spend: number,
    resetAt?: string,
  ): Promise<{ alias: string; spend: number; maxBudget: number }> {
    return call(this.baseUrl, 'POST', '/litellm/spend', { principalId, spend, resetAt });
  }

  /** What every model request adds to its key's spend. */
  setCostPerRequest(costPerRequestUsd: number): Promise<unknown> {
    return call(this.baseUrl, 'POST', '/litellm/cost', { costPerRequestUsd });
  }

  requests(
    prefix = '',
  ): Promise<{ method: string; path: string; status: number; alias?: string; model?: string }[]> {
    return call(this.baseUrl, 'GET', `/litellm/requests?prefix=${encodeURIComponent(prefix)}`);
  }
}

/** The control API's health: 200 once the stack is ready. */
export async function controlHealth(
  baseUrl: string,
): Promise<{ ready: boolean; web: string; urls: Record<string, string> } | null> {
  try {
    const response = await fetch(`${baseUrl}/health`, { signal: AbortSignal.timeout(2000) });
    return (await response.json()) as { ready: boolean; web: string; urls: Record<string, string> };
  } catch {
    return null;
  }
}
