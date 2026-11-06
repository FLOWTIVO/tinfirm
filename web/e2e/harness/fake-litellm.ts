/**
 * A LiteLLM for the e2e runs: a Node port of the server's test fake (server/src/test/java/.../support/FakeLiteLlm.java).
 * It serves the admin API Tinfirm uses as LiteLLM v1.105.1 does (`/key/generate`, `/key/update`, `/key/block`,
 * `/key/unblock`, `/key/delete`, `/key/info`, `/key/list`, `/team/info`, `/team/new`, `/team/update`, `/v1/models`)
 * with its master key, keeps the keys and teams it was asked for, and records every request. The model routes
 * (`/v1/messages`, `/v1/responses`, `/v1/chat/completions`, ...) take a virtual key, refuse a blocked one (401), a
 * spent budget (429 `budget_exceeded`) and a model the key does not allow (401), answer a canned completion, and add
 * `costPerRequestUsd` to the key's spend. The spend of a key can be set from the control API (V7: a spent budget).
 *
 * The server reaches it as the deployment's bundled gateway (TINFIRM_LITELLM_ADMIN_URL). TEST FIXTURE ONLY.
 */
import { createHash, randomBytes } from 'node:crypto';
import type { Server, ServerResponse } from 'node:http';
import { bearer, closeServer, type Request, sendJson, startServer } from './http-util.ts';

export interface FakeKey {
  key: string;
  token: string;
  alias: string;
  teamId: string;
  maxBudget: number;
  budgetDuration: string;
  models: string[];
  metadata: Record<string, string>;
  blocked: boolean;
  spend: number;
  resetAt: string;
}

export interface RecordedRequest {
  at: string;
  method: string;
  path: string;
  /** The model a model route was asked for. */
  model?: string;
  /** The alias of the key a model route was called with. */
  alias?: string;
  status: number;
}

const MODELS = ['claude-sonnet-4-5', 'claude-haiku-4-5', 'gpt-5.4'];

const MODEL_ROUTES = new Set([
  '/v1/messages',
  '/v1/messages/count_tokens',
  '/v1/responses',
  '/v1/chat/completions',
  '/v1/models',
]);

export function hash(key: string): string {
  return createHash('sha256').update(key, 'utf8').digest('hex');
}

function thirtyDaysFromNow(): string {
  return new Date(Date.now() + 30 * 24 * 3600 * 1000).toISOString().replace(/\.\d{3}Z$/, 'Z');
}

export class FakeLiteLlm {
  readonly masterKey: string;
  /** Added to a key's spend for every model request it passes. */
  costPerRequestUsd = 0;
  down = false;

  private server?: Server;
  private readonly keys = new Map<string, FakeKey>();
  private readonly teams = new Map<string, string>();
  private readonly failures = new Map<string, number[]>();
  private readonly log: RecordedRequest[] = [];

  constructor(masterKey: string) {
    this.masterKey = masterKey;
  }

  async start(port: number): Promise<void> {
    this.server = await startServer(port, (request, response) => this.handle(request, response));
  }

  async stop(): Promise<void> {
    if (this.server) await closeServer(this.server);
  }

  // ---- what it holds, for the control API --------------------------------------------------------------------

  /** The keys without their secret: alias, team, budget, spend, models, blocked, metadata. */
  listKeys(): Omit<FakeKey, 'key'>[] {
    return [...this.keys.values()].map(({ key: _key, ...rest }) => ({ ...rest }));
  }

  /** The key of a principal (its alias ends with the principal's id), or of an exact alias. */
  findKey(aliasOrPrincipalId: string): FakeKey | undefined {
    return [...this.keys.values()].find(
      (key) => key.alias === aliasOrPrincipalId || key.alias.endsWith(`-${aliasOrPrincipalId}`),
    );
  }

  /** Sets a key's spend, e.g. to its budget so that its next request is refused and the next sync reads it spent. */
  setSpend(aliasOrPrincipalId: string, spend: number, resetAt?: string): FakeKey {
    const key = this.findKey(aliasOrPrincipalId);
    if (!key) throw new Error(`No key for ${aliasOrPrincipalId}`);
    key.spend = spend;
    if (resetAt) key.resetAt = resetAt;
    return key;
  }

  /** The next `times` requests to the path answer `status`. */
  failNext(path: string, status: number, times = 1): void {
    const queue = this.failures.get(path) ?? [];
    for (let i = 0; i < times; i++) queue.push(status);
    this.failures.set(path, queue);
  }

  requests(pathPrefix = ''): RecordedRequest[] {
    return this.log.filter((entry) => entry.path.startsWith(pathPrefix));
  }

  // ---- HTTP -------------------------------------------------------------------------------------------------------

  private record(request: Request, status: number, extra: Partial<RecordedRequest> = {}): void {
    this.log.push({
      at: new Date().toISOString(),
      method: request.method,
      path: request.path,
      status,
      ...extra,
    });
    if (this.log.length > 2000) this.log.splice(0, this.log.length - 2000);
  }

  private error(
    request: Request,
    response: ServerResponse,
    status: number,
    type: string,
    message: string,
  ): void {
    this.record(request, status);
    sendJson(response, status, { error: { message, type, code: String(status) } });
  }

  private ok(
    request: Request,
    response: ServerResponse,
    body: unknown,
    extra: Partial<RecordedRequest> = {},
  ): void {
    this.record(request, 200, extra);
    sendJson(response, 200, body);
  }

  private handle(request: Request, response: ServerResponse): void {
    if (this.down)
      return this.error(request, response, 503, 'service_unavailable', 'LiteLLM is down');
    const failure = this.failures.get(request.path)?.shift();
    if (failure !== undefined)
      return this.error(request, response, failure, 'fake_failure', 'Failure asked for');
    const token = bearer(request.headers['authorization']);
    if (
      MODEL_ROUTES.has(request.path) &&
      !(request.path === '/v1/models' && token === this.masterKey)
    ) {
      return this.model(request, response, token);
    }
    if (token !== this.masterKey) {
      return this.error(
        request,
        response,
        401,
        'auth_error',
        'Authentication Error, Invalid proxy server token',
      );
    }
    this.admin(request, response);
  }

  private admin(request: Request, response: ServerResponse): void {
    const route = `${request.method} ${request.path}`;
    const body = request.method === 'POST' ? request.json<Record<string, unknown>>() : {};
    switch (route) {
      case 'GET /team/info': {
        const teamId = request.query.get('team_id') ?? '';
        if (!this.teams.has(teamId)) {
          return this.error(
            request,
            response,
            404,
            'not_found',
            `Team not found, passed team id: ${teamId}`,
          );
        }
        return this.ok(request, response, {
          team_id: teamId,
          team_info: { team_id: teamId, team_alias: this.teams.get(teamId) },
        });
      }
      case 'POST /team/new': {
        const teamId = String(body['team_id']);
        if (this.teams.has(teamId)) {
          return this.error(
            request,
            response,
            400,
            'bad_request',
            `Team id = ${teamId} already exists.`,
          );
        }
        this.teams.set(teamId, String(body['team_alias'] ?? ''));
        return this.ok(request, response, { team_id: teamId });
      }
      case 'POST /team/update': {
        const teamId = String(body['team_id']);
        if (!this.teams.has(teamId)) {
          return this.error(
            request,
            response,
            404,
            'not_found',
            `Team not found, passed team_id=${teamId}`,
          );
        }
        if ('metadata' in body) {
          return this.error(
            request,
            response,
            400,
            'bad_request',
            'The fake refuses metadata on a team update: it would replace the stored one',
          );
        }
        if ('team_alias' in body) this.teams.set(teamId, String(body['team_alias']));
        return this.ok(request, response, { team_id: teamId });
      }

      case 'POST /key/generate':
        return this.generate(request, response, body);
      case 'POST /key/update': {
        const key = this.keys.get(String(body['key']));
        if (!key) return this.error(request, response, 404, 'not_found', 'Key not found');
        if ('metadata' in body) {
          return this.error(
            request,
            response,
            400,
            'bad_request',
            'The fake refuses metadata on update',
          );
        }
        if ('max_budget' in body) key.maxBudget = Number(body['max_budget']);
        if ('budget_duration' in body) {
          key.budgetDuration = String(body['budget_duration']);
          key.resetAt = thirtyDaysFromNow();
        }
        if ('models' in body) key.models = (body['models'] as string[]) ?? [];
        return this.ok(request, response, { key: key.token });
      }
      case 'POST /key/block':
      case 'POST /key/unblock': {
        const key = this.keys.get(String(body['key']));
        if (!key) return this.error(request, response, 404, 'not_found', 'Key not found.');
        key.blocked = request.path === '/key/block';
        return this.ok(request, response, { token: key.token, blocked: key.blocked });
      }
      case 'POST /key/delete': {
        const deleted: string[] = [];
        for (const token of (body['keys'] as string[] | undefined) ?? []) {
          if (this.keys.delete(token)) deleted.push(token);
        }
        for (const alias of (body['key_aliases'] as string[] | undefined) ?? []) {
          for (const [token, key] of this.keys) {
            if (key.alias === alias) {
              this.keys.delete(token);
              deleted.push(alias);
            }
          }
        }
        if (deleted.length === 0)
          return this.error(request, response, 404, 'not_found', 'No keys found');
        return this.ok(request, response, { deleted_keys: deleted });
      }
      case 'GET /key/info': {
        const token = request.query.get('key') ?? '';
        const key = this.keys.get(token);
        if (!key)
          return this.error(request, response, 404, 'not_found', 'Key not found in database');
        return this.ok(request, response, { key: token, info: this.keyJson(key, false) });
      }
      case 'GET /key/list': {
        const teamId = request.query.get('team_id');
        const size = Number(request.query.get('size') ?? '10');
        const page = Number(request.query.get('page') ?? '1');
        const team = [...this.keys.values()].filter((key) => key.teamId === teamId);
        const pages = Math.max(1, Math.ceil(team.length / size));
        return this.ok(request, response, {
          keys: team.slice((page - 1) * size, page * size).map((key) => this.keyJson(key, true)),
          total_count: team.length,
          current_page: page,
          total_pages: pages,
        });
      }
      case 'GET /v1/models':
        return this.ok(request, response, {
          object: 'list',
          data: MODELS.map((id) => ({ id, object: 'model' })),
        });
      default:
        return this.error(request, response, 404, 'not_found', `No route ${route}`);
    }
  }

  private generate(
    request: Request,
    response: ServerResponse,
    body: Record<string, unknown>,
  ): void {
    const alias = String(body['key_alias']);
    const teamId = String(body['team_id']);
    if (!this.teams.has(teamId))
      return this.error(request, response, 400, 'bad_request', 'Team not found');
    if ([...this.keys.values()].some((key) => key.alias === alias)) {
      return this.error(
        request,
        response,
        400,
        'bad_request',
        `Key with alias '${alias}' already exists.`,
      );
    }
    if (body['key_type'] !== 'llm_api') {
      return this.error(request, response, 400, 'bad_request', 'The fake expects key_type llm_api');
    }
    const secret = `sk-${randomBytes(24).toString('base64url')}`;
    const metadata: Record<string, string> = {};
    for (const [name, value] of Object.entries(
      (body['metadata'] as Record<string, unknown>) ?? {},
    )) {
      metadata[name] = String(value);
    }
    const key: FakeKey = {
      key: secret,
      token: hash(secret),
      alias,
      teamId,
      maxBudget: Number(body['max_budget'] ?? 0),
      budgetDuration: String(body['budget_duration'] ?? '30d'),
      models: (body['models'] as string[]) ?? [],
      metadata,
      blocked: body['blocked'] === true,
      spend: 0,
      resetAt: thirtyDaysFromNow(),
    };
    this.keys.set(key.token, key);
    this.ok(request, response, { key: secret, token: key.token, key_alias: alias });
  }

  private keyJson(key: FakeKey, withToken: boolean): Record<string, unknown> {
    return {
      ...(withToken ? { token: key.token } : {}),
      key_alias: key.alias,
      team_id: key.teamId,
      spend: key.spend,
      max_budget: key.maxBudget,
      budget_duration: key.budgetDuration,
      budget_reset_at: key.resetAt,
      blocked: key.blocked,
      status: key.blocked ? 'revoked' : 'active',
      models: key.models,
      metadata: key.metadata,
    };
  }

  private model(request: Request, response: ServerResponse, secret: string | null): void {
    const key = secret ? this.keys.get(hash(secret)) : undefined;
    if (!key)
      return this.error(
        request,
        response,
        401,
        'auth_error',
        'Authentication Error, Invalid proxy server token',
      );
    if (key.blocked)
      return this.error(
        request,
        response,
        401,
        'auth_error',
        'Authentication Error - Key is blocked.',
      );
    if (key.spend >= key.maxBudget) {
      return this.error(
        request,
        response,
        429,
        'budget_exceeded',
        `Budget has been exceeded! Current cost: ${key.spend}, Max budget: ${key.maxBudget}`,
      );
    }
    if (request.path === '/v1/models') {
      return this.ok(request, response, {
        object: 'list',
        data: MODELS.map((id) => ({ id, object: 'model' })),
      });
    }
    const body = request.json<Record<string, unknown>>();
    const model = String(body['model'] ?? '');
    if (key.models.length > 0 && !key.models.includes(model)) {
      return this.error(
        request,
        response,
        401,
        'key_model_access_denied',
        `key not allowed to access model ${model}`,
      );
    }
    key.spend += this.costPerRequestUsd;
    const extra = { model, alias: key.alias };
    if (request.path === '/v1/messages/count_tokens')
      return this.ok(request, response, { input_tokens: 10 }, extra);
    if (request.path === '/v1/messages') {
      return this.ok(
        request,
        response,
        {
          id: `msg_e2e_${randomBytes(6).toString('hex')}`,
          type: 'message',
          role: 'assistant',
          model,
          content: [{ type: 'text', text: 'A canned answer of the e2e LiteLLM.' }],
          stop_reason: 'end_turn',
          usage: { input_tokens: 10, output_tokens: 5 },
        },
        extra,
      );
    }
    return this.ok(
      request,
      response,
      {
        id: `resp_e2e_${randomBytes(6).toString('hex')}`,
        object: request.path === '/v1/responses' ? 'response' : 'chat.completion',
        model,
        output_text: 'A canned answer of the e2e LiteLLM.',
        choices: [
          {
            index: 0,
            message: { role: 'assistant', content: 'A canned answer of the e2e LiteLLM.' },
          },
        ],
        usage: { input_tokens: 10, output_tokens: 5, prompt_tokens: 10, completion_tokens: 5 },
      },
      extra,
    );
  }
}
