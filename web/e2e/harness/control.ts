/**
 * The harness's control API and status page, on E2E_CONTROL_PORT (18090). The stack process holds the fakes and the
 * scripted runner host; tests, the seed and people drive them over this API (control-client.ts is its typed client):
 *
 * - `GET /` is a small status page of the harness (services, the runner, its runs, the seeded people);
 * - `GET /health` answers 200 once the server (and the web app, when served) is ready, else 503;
 * - `/runner/...` connects the scripted runner host and drives its runs;
 * - `/stubs/...` reads the calls the Jira and GitHub stubs saw, teaches them tokens, and picks who their OAuth
 *   sign-in is;
 * - `/litellm/...` reads the fake LiteLLM's keys and sets a key's spend;
 * - `/slack/...` reads what the bot posted to the Slack stub, and names its users;
 * - `/lock/...` takes and lets go of the company's lock (company-lock.ts), which lets the specs run on parallel workers.
 *
 * It listens on 127.0.0.1 only. TEST FIXTURE ONLY.
 */
import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import type { Server, ServerResponse } from 'node:http';
import { CompanyLock, type LockMode } from './company-lock.ts';
import type { FakeLiteLlm } from './fake-litellm.ts';
import { closeServer, match, type Request, sendJson, sendText, startServer } from './http-util.ts';
import type { FinishOptions, Json, RunState, ScriptedRunner } from './scripted-runner.ts';
import type { JiraIssueInput, StubSystem, SystemStubs } from './stubs.ts';

export interface ControlDeps {
  runner: ScriptedRunner;
  stubs: SystemStubs;
  litellm: FakeLiteLlm;
  stateDir: string;
  urls: Record<string, string>;
  webMode: string;
  /** Whether the server (and the web app, when served) answer. */
  ready: () => boolean;
}

export class ControlServer {
  private server?: Server;
  private readonly deps: ControlDeps;
  private readonly lock = new CompanyLock();

  constructor(deps: ControlDeps) {
    this.deps = deps;
  }

  async start(port: number): Promise<void> {
    this.server = await startServer(port, (request, response) => this.handle(request, response));
  }

  async stop(): Promise<void> {
    if (this.server) await closeServer(this.server);
  }

  private async handle(request: Request, response: ServerResponse): Promise<void> {
    const { runner, stubs, litellm } = this.deps;
    const { method, path } = request;
    const body = method === 'POST' || method === 'PUT' ? request.json<Json>() : {};
    let params: Record<string, string> | null;
    try {
      if (method === 'GET' && path === '/')
        return sendText(response, 200, this.page(), 'text/html');
      if (method === 'GET' && path === '/health') {
        const ready = this.deps.ready();
        return sendJson(response, ready ? 200 : 503, {
          ready,
          web: this.deps.webMode,
          urls: this.deps.urls,
        });
      }
      // ---- the company's lock ----
      if (method === 'GET' && path === '/lock') return sendJson(response, 200, this.lock.status());
      if (method === 'POST' && path === '/lock/acquire') {
        // A long poll, shorter than a client's own timeouts; the client asks again until it holds the lock.
        const held = await this.lock.acquire(
          String(body['owner']),
          body['mode'] === 'exclusive' ? 'exclusive' : ('shared' satisfies LockMode),
          Math.min(Number(body['waitMs'] ?? 60_000), 120_000),
        );
        return sendJson(response, 200, { held });
      }
      if (method === 'POST' && path === '/lock/release') {
        this.lock.release(String(body['owner']));
        return sendJson(response, 200, { released: true });
      }
      // ---- the runner ----
      if (method === 'GET' && path === '/runner') return sendJson(response, 200, runner.status());
      if (method === 'POST' && path === '/runner/connect') {
        return sendJson(response, 200, await runner.connect(String(body['token'])));
      }
      if (method === 'POST' && path === '/runner/disconnect') {
        runner.disconnect();
        return sendJson(response, 200, runner.status());
      }
      if (method === 'POST' && path === '/runner/default-script') {
        runner.defaultScript = String(body['script'] ?? 'hold');
        return sendJson(response, 200, runner.status());
      }
      if (method === 'GET' && path === '/runner/runs') {
        return sendJson(response, 200, runner.runs(this.runFilter(request)));
      }
      if (method === 'GET' && path === '/runner/runs/await') {
        const timeoutMs = Number(request.query.get('timeoutMs') ?? '30000');
        const filter = {
          ...this.runFilter(request),
          runId: request.query.get('runId') ?? undefined,
        };
        return sendJson(response, 200, await runner.awaitRun(filter, timeoutMs));
      }
      if ((params = match('/runner/runs/:runId', path)) && method === 'GET') {
        return sendJson(response, 200, runner.run(params['runId']));
      }
      if ((params = match('/runner/runs/:runId/:action', path)) && method === 'POST') {
        return sendJson(
          response,
          200,
          await this.runAction(params['runId'], params['action'], body),
        );
      }
      // ---- Slack ----
      if (method === 'GET' && path === '/slack/posts') {
        return sendJson(
          response,
          200,
          stubs.slack.postsTo(request.query.get('channel') ?? undefined),
        );
      }
      if (method === 'DELETE' && path === '/slack/posts') {
        stubs.slack.clear();
        return sendJson(response, 200, { cleared: true });
      }
      if (method === 'POST' && path === '/slack/users') {
        stubs.slack.setUser(String(body['id']), String(body['name']));
        return sendJson(response, 200, { added: true });
      }
      // ---- the stubs ----
      if (method === 'GET' && path === '/stubs/calls') {
        const system = request.query.get('system') as StubSystem | null;
        return sendJson(response, 200, stubs.calls(system ?? undefined));
      }
      if (method === 'DELETE' && path === '/stubs/calls') {
        stubs.clearCalls();
        return sendJson(response, 200, { cleared: true });
      }
      if (method === 'POST' && path === '/stubs/identities') {
        stubs.addIdentity(
          body['system'] as StubSystem,
          String(body['token']),
          String(body['identity']),
        );
        return sendJson(response, 200, { added: true });
      }
      if (method === 'POST' && path === '/stubs/oauth') {
        stubs.setOAuthIdentity(body['system'] as StubSystem, String(body['identity']));
        return sendJson(response, 200, { identity: body['identity'] });
      }
      if (method === 'POST' && path === '/stubs/jira/issues') {
        return sendJson(response, 200, stubs.putJiraIssue(body as unknown as JiraIssueInput));
      }
      if ((params = match('/stubs/jira/issues/:key', path)) && method === 'GET') {
        return sendJson(response, 200, stubs.jiraIssue(params['key']));
      }
      if ((params = match('/stubs/jira/issues/:key/comments', path)) && method === 'POST') {
        return sendJson(
          response,
          200,
          stubs.commentJiraIssue(params['key'], String(body['author']), String(body['body'])),
        );
      }
      if (method === 'DELETE' && path === '/stubs/identities') {
        const input = request.json<Json>();
        stubs.removeIdentity(input['system'] as StubSystem, String(input['token']));
        return sendJson(response, 200, { removed: true });
      }
      // ---- the fake LiteLLM ----
      if (method === 'GET' && path === '/litellm/keys')
        return sendJson(response, 200, litellm.listKeys());
      if (method === 'GET' && path === '/litellm/requests') {
        return sendJson(response, 200, litellm.requests(request.query.get('prefix') ?? ''));
      }
      if (method === 'POST' && path === '/litellm/spend') {
        const key = litellm.setSpend(
          String(body['principalId'] ?? body['alias']),
          Number(body['spend']),
          body['resetAt'] as string | undefined,
        );
        return sendJson(response, 200, {
          alias: key.alias,
          spend: key.spend,
          maxBudget: key.maxBudget,
          resetAt: key.resetAt,
        });
      }
      if (method === 'POST' && path === '/litellm/cost') {
        litellm.costPerRequestUsd = Number(body['costPerRequestUsd'] ?? 0);
        return sendJson(response, 200, { costPerRequestUsd: litellm.costPerRequestUsd });
      }
      if (method === 'POST' && path === '/litellm/down') {
        litellm.down = body['down'] === true;
        return sendJson(response, 200, { down: litellm.down });
      }
      if (method === 'POST' && path === '/litellm/fail') {
        litellm.failNext(String(body['path']), Number(body['status']), Number(body['times'] ?? 1));
        return sendJson(response, 200, { ok: true });
      }
      return sendJson(response, 404, { error: `No control route ${method} ${path}` });
    } catch (error) {
      return sendJson(response, 409, { error: String((error as Error)?.message ?? error) });
    }
  }

  private runFilter(request: Request): { conversationId?: string; state?: RunState } {
    return {
      conversationId: request.query.get('conversationId') ?? undefined,
      state: (request.query.get('state') as RunState | null) ?? undefined,
    };
  }

  private async runAction(runId: string, action: string, body: Json): Promise<unknown> {
    const { runner } = this.deps;
    switch (action) {
      case 'finish':
        return runner.finish(runId, body as FinishOptions);
      case 'message':
        return { seq: runner.message(runId, String(body['text'])) };
      case 'event':
        return {
          seq: runner.event(runId, String(body['eventType']), (body['payload'] as Json) ?? {}),
        };
      case 'ask':
        return runner.ask(
          runId,
          body as { question: string; options?: string[]; endTurn?: boolean },
        );
      case 'tool':
        return runner.tool(runId, body as { tool: string; arguments?: Json; endTurn?: boolean });
      case 'approval':
        return runner.approval(
          runId,
          body as { tool?: string; arguments?: Json; endTurn?: boolean },
        );
      case 'access':
        return runner.access(
          runId,
          body as { repository?: string; tool?: string; reason: string; endTurn?: boolean },
        );
      case 'network':
        return runner.network(runId, body as { host: string; reason: string; endTurn?: boolean });
      case 'model':
        return runner.callModel(runId, body['body'] as Json | undefined);
      default:
        throw new Error(`No run action ${action}`);
    }
  }

  /** The status page: plain, accessible HTML that works at phone width (the harness's own axe and phone checks). */
  private page(): string {
    const { runner, urls, webMode } = this.deps;
    const status = runner.status();
    const seedFile = resolve(this.deps.stateDir, 'seed.json');
    const seed = existsSync(seedFile) ? (JSON.parse(readFileSync(seedFile, 'utf8')) as Json) : null;
    const people = (seed?.['people'] as Record<string, Json> | undefined) ?? {};
    const runs = runner.runs().slice(-20).reverse();
    const row = (cells: string[]) => `<tr>${cells.map((cell) => `<td>${cell}</td>`).join('')}</tr>`;
    const head = (cells: string[]) =>
      `<tr>${cells.map((cell) => `<th scope="col">${cell}</th>`).join('')}</tr>`;
    return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Tinfirm e2e harness</title>
<style>
  :root { color-scheme: light; font-family: system-ui, sans-serif; color: #1c2b22; background: #eef2ec; }
  body { margin: 0; }
  main { max-width: 60rem; margin: 0 auto; padding: 1rem; }
  h1 { font-size: 1.5rem; margin: 0.5rem 0 1rem; }
  h2 { font-size: 1.125rem; margin: 1.5rem 0 0.5rem; }
  section { background: #fff; border-radius: 0.5rem; padding: 0.75rem 1rem; margin-bottom: 1rem; }
  .scroll { overflow-x: auto; }
  table { border-collapse: collapse; width: 100%; font-size: 0.875rem; }
  th, td { text-align: left; padding: 0.375rem 0.5rem; border-bottom: 1px solid #d5ddd3; vertical-align: top; }
  code { font-family: ui-monospace, monospace; overflow-wrap: anywhere; }
  .ok { color: #1d6b38; font-weight: 600; }
  .off { color: #8a2d1f; font-weight: 600; }
  p { overflow-wrap: anywhere; }
</style>
</head>
<body>
<main>
<h1>Tinfirm e2e harness</h1>
<section aria-labelledby="services">
<h2 id="services">Services</h2>
<div class="scroll"><table>
${head(['Service', 'Address'])}
${Object.entries(urls)
  .map(([name, url]) => row([escape(name), `<code>${escape(url)}</code>`]))
  .join('\n')}
${row(['web app', escape(webMode)])}
</table></div>
</section>
<section aria-labelledby="runner">
<h2 id="runner">Runner host</h2>
<p>${status.connected ? '<span class="ok">Connected</span>' : '<span class="off">Not connected</span>'}
${status.runnerHostId ? ` as <code>${escape(status.runnerHostId)}</code>` : ''}. Runs without a marker: <code>${escape(status.defaultScript)}</code>.</p>
<div class="scroll"><table>
${head(['Run', 'Conversation', 'Script', 'State'])}
${runs.length === 0 ? row(['No runs yet', '', '', '']) : runs.map((run) => row([`<code>${escape(run.runId)}</code>`, escape(run.key), escape(run.script), escape(run.state)])).join('\n')}
</table></div>
</section>
<section aria-labelledby="people">
<h2 id="people">Seeded people</h2>
<div class="scroll"><table>
${head(['Persona', 'Email', 'Role'])}
${
  Object.keys(people).length === 0
    ? row(['Not seeded yet: npm run e2e:seed', '', ''])
    : Object.entries(people)
        .map(([name, person]) =>
          row([
            escape(name),
            `<code>${escape(String(person['email']))}</code>`,
            escape(String(person['role'] ?? '')),
          ]),
        )
        .join('\n')
}
</table></div>
</section>
</main>
</body>
</html>`;
  }
}

function escape(text: string): string {
  return text.replace(/[&<>"']/g, (char) => `&#${char.charCodeAt(0)};`);
}
