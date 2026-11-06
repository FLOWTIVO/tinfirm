/**
 * Stubs of the company's systems for the e2e runs, in the spirit of the server's WireMock stubs
 * (server/src/test/java/.../support/JiraStub.java and GitHubStub.java), on one port:
 *
 * - Jira Data Center's REST API v2 under `/jira`: "who am I" (`/rest/api/2/myself`) per token, issues, search,
 *   comments and transitions;
 * - GitHub's REST API under `/github` (as GitHub Enterprise Server's `https://<host>/api/v3`): "who am I" (`/user`)
 *   per token, issues, search, comments, labels, closing, and pull requests for the change requests after a push;
 * - an MCP server under `/mcp`, which needs no token (`mcp-stub.ts`);
 * - Slack's Web API under `/slack/api`, for the chat systems (`slack-stub.ts`).
 *
 * Every request names its token, and the stub records which identity each call acted as (V1: "the stub saw Alice's
 * token on Alice's call"). A token the stub does not know is a 401, as the systems answer. Tokens are added from the
 * seed's configuration and through the control API.
 *
 * Both also play the OAuth app of their system (CONVENTIONS §15.2's profiles): GitHub's at the API's web origin
 * (`/login/oauth/authorize`, `/login/oauth/access_token`), Jira Data Center's at `/jira/rest/oauth2/latest/...`. The
 * authorization endpoint signs in at once as the identity a test chose (`setOAuthIdentity`; `deny` refuses), and
 * the token endpoint issues a token the stub then knows as that identity. TEST FIXTURE ONLY: the tokens are test
 * values.
 */
import type { Server, ServerResponse } from 'node:http';
import { bearer, closeServer, match, type Request, sendJson, startServer } from './http-util.ts';
import { handleMcp, isMcpPath } from './mcp-stub.ts';
import { SlackStub } from './slack-stub.ts';

export type StubSystem = 'jira' | 'github';

export interface StubCall {
  at: string;
  system: StubSystem;
  method: string;
  /** The path below the system's base, with the query. */
  path: string;
  /** Who the token is on the system, or null for none or an unknown one. */
  identity: string | null;
  status: number;
  /** A write's body (a comment's text, a transition), for assertions. */
  body?: unknown;
}

const JIRA_TIME = '2026-10-01T10:00:00.000+0000';

/** An issue a spec puts on the Jira stub (W5): every field it leaves out keeps the default's. */
export interface JiraIssueInput {
  key: string;
  summary?: string;
  description?: string;
  status?: string;
  type?: string;
  labels?: string[];
  /** The assignee's user name at Jira (a seeded person's first name), or null for none. */
  assignee?: string | null;
  reporter?: string;
  resolved?: boolean;
}

/** An issue the Jira stub keeps: changed by specs and by the writes Tinfirm makes. */
export interface JiraIssue {
  id: string;
  key: string;
  summary: string;
  description: string;
  status: string;
  type: string;
  labels: string[];
  assignee: string | null;
  reporter: string;
  resolved: boolean;
  created: string;
  updated: string;
  comments: { id: string; author: string; body: string; created: string }[];
}

/** Jira's time format: `2026-10-01T10:00:00.000+0000`. */
function jiraTime(date: Date): string {
  return date.toISOString().replace('Z', '+0000');
}

/** The issues every Jira stub has from the start, unchanged since 1 October. */
const DEFAULT_JIRA_KEYS = ['SHOP-1', 'SHOP-2', 'SHOP-3'];

/** The clauses of a JQL the server sends (JiraPolls): projects, the bound, the account's own, a key, words. */
interface Jql {
  projects: string[] | null;
  since: Date | null;
  assignedToMe: boolean;
  key: string | null;
  text: string | null;
  descending: boolean;
}

export function parseJql(jql: string): Jql {
  const projectsIn = /project\s+in\s+\(([^)]*)\)/i.exec(jql);
  const projectIs = /project\s*=\s*"([^"]+)"/i.exec(jql);
  const since = /updated\s*>=\s*"(\d{4}-\d{2}-\d{2} \d{2}:\d{2})"/i.exec(jql);
  const key = /key\s*=\s*"([^"]+)"/i.exec(jql);
  const text = /text\s*~\s*"((?:[^"\\]|\\.)*)"/i.exec(jql);
  return {
    projects: projectsIn
      ? [...projectsIn[1].matchAll(/"([^"]+)"/g)].map((m) => m[1].toUpperCase())
      : projectIs
        ? [projectIs[1].toUpperCase()]
        : null,
    since: since ? new Date(`${since[1].replace(' ', 'T')}:00Z`) : null,
    assignedToMe: /assignee\s*=\s*currentUser\(\)/i.test(jql),
    key: key ? key[1].toUpperCase() : null,
    text: text ? text[1].replace(/\\(.)/g, '$1').toLowerCase() : null,
    descending: /ORDER BY updated DESC/i.test(jql),
  };
}
const GITHUB_TIME = '2026-10-01T10:00:00Z';

/**
 * A GitHub user's numeric id, stable per login and distinct between logins (FNV-1a), since the server tells
 * accounts apart by it: two people with logins of one length must not be one identity.
 */
function userId(login: string): number {
  let hash = 0x811c9dc5;
  for (const char of login) {
    hash ^= char.codePointAt(0)!;
    hash = Math.imul(hash, 0x01000193) >>> 0;
  }
  return 7000 + (hash % 1_000_000);
}

export class SystemStubs {
  private server?: Server;
  /** Slack's Web API, for the chat systems (W6). */
  readonly slack = new SlackStub();
  private readonly identities: Record<StubSystem, Map<string, string>> = {
    jira: new Map(),
    github: new Map(),
  };
  private readonly log: StubCall[] = [];
  private readonly pulls = new Map<
    string,
    { number: number; head: string; base: string; body: string; title: string; state: string }
  >();
  private nextId = 1000;
  private readonly oauthIdentity: Record<StubSystem, string> = {
    jira: 'oauth-user',
    github: 'oauth-user',
  };
  private readonly oauthCodes = new Map<string, { system: StubSystem; identity: string }>();
  private readonly jiraIssues = new Map<string, JiraIssue>();

  async start(port: number): Promise<void> {
    this.server = await startServer(port, (request, response) => this.handle(request, response));
  }

  async stop(): Promise<void> {
    if (this.server) await closeServer(this.server);
  }

  /** The system answers "who am I" for this token with this identity (a Jira user name, a GitHub login). */
  addIdentity(system: StubSystem, token: string, identity: string): void {
    this.identities[system].set(token, identity);
  }

  removeIdentity(system: StubSystem, token: string): void {
    this.identities[system].delete(token);
  }

  /** Who the next OAuth sign-in on the system is; `deny` makes the authorization endpoint refuse. */
  setOAuthIdentity(system: StubSystem, identity: string): void {
    this.oauthIdentity[system] = identity;
  }

  calls(system?: StubSystem): StubCall[] {
    return this.log.filter((call) => !system || call.system === system);
  }

  clearCalls(): void {
    this.log.length = 0;
  }

  // ---- Jira's issues (W5: what triggers poll) ----------------------------------------------------------------------

  /** A Jira issue as it stands: a spec's, or one of the defaults. */
  jiraIssue(key: string): JiraIssue {
    const upper = key.toUpperCase();
    const stored = this.jiraIssues.get(upper);
    if (stored) return stored;
    const number = Number(upper.split('-')[1] ?? 0);
    return {
      id: String(10_000 + (Number.isFinite(number) ? number : 0)),
      key: upper,
      summary: `Issue ${upper} of the e2e Jira`,
      description: `The description of ${upper}.`,
      status: 'Open',
      type: 'Task',
      labels: ['e2e'],
      assignee: 'alice',
      reporter: 'olivia',
      resolved: false,
      created: JIRA_TIME,
      updated: JIRA_TIME,
      comments: [],
    };
  }

  /** Creates or changes an issue, as someone did just now: it is updated now and a poll reads the change. */
  putJiraIssue(input: JiraIssueInput): JiraIssue {
    const now = jiraTime(new Date());
    const before = this.jiraIssues.get(input.key.toUpperCase());
    const issue: JiraIssue = {
      ...(before ?? { ...this.jiraIssue(input.key), created: now }),
      ...Object.fromEntries(Object.entries(input).filter(([, v]) => v !== undefined)),
      key: input.key.toUpperCase(),
      updated: now,
    } as JiraIssue;
    this.jiraIssues.set(issue.key, issue);
    return issue;
  }

  /** A comment on an issue by someone at Jira (a user name), just now. */
  commentJiraIssue(key: string, author: string, body: string): JiraIssue {
    const issue = this.putJiraIssue({ key });
    issue.comments.push({ id: String(this.nextId++), author, body, created: issue.updated });
    return issue;
  }

  /** Every issue the stub has: the defaults, then the specs'. */
  private jiraIssueList(): JiraIssue[] {
    const keys = new Set([...DEFAULT_JIRA_KEYS, ...this.jiraIssues.keys()]);
    return [...keys].map((key) => this.jiraIssue(key));
  }

  // ---- HTTP -------------------------------------------------------------------------------------------------------

  private handle(request: Request, response: ServerResponse): void {
    if (isMcpPath(request.path)) return handleMcp(request, response);
    if (this.slack.isPath(request.path)) return this.slack.handle(request, response);
    const oauth = this.oauthRoute(request.path);
    if (oauth) return this.oauth(request, response, oauth.system, oauth.endpoint);
    const system: StubSystem | null = request.path.startsWith('/jira/')
      ? 'jira'
      : request.path.startsWith('/github/') || request.path === '/github'
        ? 'github'
        : null;
    if (!system) return sendJson(response, 404, { message: 'Not Found' });
    const token = bearer(request.headers['authorization']);
    const identity = token ? (this.identities[system].get(token) ?? null) : null;
    const path = request.path.slice(system.length + 1) || '/';
    const query = request.query.toString();
    const respond = (status: number, body: unknown, written?: unknown) => {
      this.log.push({
        at: new Date().toISOString(),
        system,
        method: request.method,
        path: path + (query ? `?${query}` : ''),
        identity,
        status,
        ...(written !== undefined ? { body: written } : {}),
      });
      if (this.log.length > 5000) this.log.splice(0, this.log.length - 5000);
      if (status === 204) {
        response.writeHead(204);
        response.end();
      } else {
        sendJson(response, status, body);
      }
    };
    if (!identity) {
      return system === 'jira'
        ? respond(401, {
            errorMessages: [
              'You are not authenticated. Authentication required to perform this operation.',
            ],
          })
        : respond(401, {
            message: 'Bad credentials',
            documentation_url: 'https://docs.github.com/rest',
          });
    }
    if (system === 'jira') this.jira(request, path, identity, respond);
    else this.github(request, path, identity, respond);
  }

  private oauthRoute(path: string): { system: StubSystem; endpoint: 'authorize' | 'token' } | null {
    if (path === '/login/oauth/authorize') return { system: 'github', endpoint: 'authorize' };
    if (path === '/login/oauth/access_token') return { system: 'github', endpoint: 'token' };
    if (path === '/jira/rest/oauth2/latest/authorize')
      return { system: 'jira', endpoint: 'authorize' };
    if (path === '/jira/rest/oauth2/latest/token') return { system: 'jira', endpoint: 'token' };
    return null;
  }

  /** The system's OAuth app: sign in at once as the chosen identity, and exchange the code for a token. */
  private oauth(
    request: Request,
    response: ServerResponse,
    system: StubSystem,
    endpoint: 'authorize' | 'token',
  ): void {
    const record = (status: number, identity: string | null) =>
      this.log.push({
        at: new Date().toISOString(),
        system,
        method: request.method,
        path: request.path,
        identity,
        status,
      });
    if (endpoint === 'authorize') {
      const redirect = request.query.get('redirect_uri');
      const state = request.query.get('state') ?? '';
      if (!redirect) {
        record(400, null);
        return sendJson(response, 400, {
          error: 'invalid_request',
          error_description: 'redirect_uri is missing',
        });
      }
      const identity = this.oauthIdentity[system];
      const target = new URL(redirect);
      if (identity === 'deny') {
        target.searchParams.set('error', 'access_denied');
      } else {
        const code = `code-${system}-${this.nextId++}`;
        this.oauthCodes.set(code, { system, identity });
        target.searchParams.set('code', code);
      }
      target.searchParams.set('state', state);
      record(302, identity);
      response.writeHead(302, { location: target.toString() });
      response.end();
      return;
    }
    const form = new URLSearchParams(request.body);
    const grant = form.get('grant_type');
    let identity: string | undefined;
    if (grant === 'authorization_code') {
      identity = this.oauthCodes.get(form.get('code') ?? '')?.identity;
      this.oauthCodes.delete(form.get('code') ?? '');
    } else if (grant === 'refresh_token') {
      identity = /^[a-z]+-refresh-(.+)-\d+$/.exec(form.get('refresh_token') ?? '')?.[1];
    }
    if (!identity) {
      record(400, null);
      return sendJson(response, 400, {
        error: 'invalid_grant',
        error_description: 'The code or refresh token is not valid',
      });
    }
    const serial = this.nextId++;
    const token = `${system}-oauth-${identity}-${serial}`;
    this.identities[system].set(token, identity);
    record(200, identity);
    sendJson(response, 200, {
      access_token: token,
      token_type: 'bearer',
      scope: system === 'github' ? 'repo' : 'WRITE',
      expires_in: 28_800,
      refresh_token: `${system}-refresh-${identity}-${serial}`,
    });
  }

  private jira(
    request: Request,
    path: string,
    identity: string,
    respond: (status: number, body: unknown, written?: unknown) => void,
  ): void {
    const user = (name: string) => ({ key: `key-${name}`, name, displayName: `Display ${name}` });
    const comment = (c: JiraIssue['comments'][number]) => ({
      id: c.id,
      body: c.body,
      author: user(c.author),
      created: c.created,
      updated: c.created,
    });
    const issue = (key: string) => {
      const i = this.jiraIssue(key);
      return {
        id: i.id,
        key: i.key,
        self: `/rest/api/2/issue/${i.id}`,
        fields: {
          summary: i.summary,
          description: i.description,
          status: { name: i.status },
          issuetype: { name: i.type },
          labels: i.labels,
          assignee: i.assignee ? user(i.assignee) : null,
          reporter: user(i.reporter),
          created: i.created,
          updated: i.updated,
          resolutiondate: i.resolved ? i.updated : null,
          comment: {
            startAt: 0,
            maxResults: i.comments.length,
            total: i.comments.length,
            comments: i.comments.map(comment),
          },
        },
      };
    };
    let params: Record<string, string> | null;
    if (request.method === 'GET' && path === '/rest/api/2/myself') {
      return respond(200, {
        ...user(identity),
        emailAddress: `${identity}@acme.test`,
        timeZone: 'UTC',
        active: true,
      });
    }
    if (request.method === 'GET' && path === '/rest/api/2/search') {
      const jql = parseJql(request.query.get('jql') ?? '');
      const matching = this.jiraIssueList()
        .filter((i) => !jql.projects || jql.projects.includes(i.key.split('-')[0]))
        .filter((i) => !jql.since || new Date(i.updated.replace('+0000', 'Z')) >= jql.since)
        .filter((i) => !jql.assignedToMe || i.assignee === identity)
        .filter((i) => !jql.key || i.key === jql.key)
        .filter(
          (i) =>
            !jql.text ||
            i.summary.toLowerCase().includes(jql.text) ||
            i.description.toLowerCase().includes(jql.text),
        )
        .sort((a, b) =>
          a.updated === b.updated ? a.key.localeCompare(b.key) : a.updated < b.updated ? -1 : 1,
        );
      if (jql.descending) matching.reverse();
      const startAt = Number(request.query.get('startAt') ?? 0);
      const maxResults = Number(request.query.get('maxResults') ?? 50);
      const page = matching.slice(startAt, startAt + maxResults).map((i) => issue(i.key));
      return respond(200, { startAt, maxResults, total: matching.length, issues: page });
    }
    if ((params = match('/rest/api/2/issue/:key', path)) && request.method === 'GET') {
      return respond(200, issue(params['key']));
    }
    if ((params = match('/rest/api/2/issue/:key/comment', path))) {
      const key = params['key'];
      if (request.method === 'GET') {
        const comments = this.jiraIssue(key).comments.map(comment);
        return respond(200, {
          startAt: 0,
          maxResults: comments.length,
          total: comments.length,
          comments,
        });
      }
      if (request.method === 'POST') {
        const written = request.json<{ body?: string }>();
        const changed = this.commentJiraIssue(key, identity, written.body ?? '');
        const made = changed.comments[changed.comments.length - 1];
        return respond(201, comment(made), written);
      }
    }
    if ((params = match('/rest/api/2/issue/:key/transitions', path))) {
      const transitions = [
        { id: '21', name: 'Start progress', to: { name: 'In Progress' } },
        { id: '31', name: 'Done', to: { name: 'Done' } },
      ];
      if (request.method === 'GET') return respond(200, { transitions });
      if (request.method === 'POST') {
        const written = request.json<{ transition?: { id?: string } }>();
        const to = transitions.find((t) => t.id === written.transition?.id);
        if (to) this.putJiraIssue({ key: params['key'], status: to.to.name });
        return respond(204, null, written);
      }
    }
    respond(404, { errorMessages: [`No stub for ${request.method} ${path}`] });
  }

  private github(
    request: Request,
    path: string,
    identity: string,
    respond: (status: number, body: unknown, written?: unknown) => void,
  ): void {
    const base = `${this.publicBase()}`;
    const issue = (repository: string, number: number) => ({
      id: 50_000 + number,
      number,
      title: `Issue #${number} of ${repository}`,
      body: `The body of ${repository}#${number}.`,
      state: 'open',
      html_url: `${base}/${repository}/issues/${number}`,
      repository_url: `${base}/repos/${repository}`,
      user: { login: 'olivia' },
      labels: [{ name: 'e2e' }],
      assignees: [{ login: 'alice' }],
      created_at: GITHUB_TIME,
      updated_at: GITHUB_TIME,
      comments: 0,
    });
    let params: Record<string, string> | null;
    if (request.method === 'GET' && path === '/user') {
      return respond(200, {
        id: userId(identity),
        login: identity,
        name: `Name of ${identity}`,
      });
    }
    if (request.method === 'GET' && path === '/search/issues') {
      return respond(200, {
        total_count: 2,
        incomplete_results: false,
        items: [issue('acme/shop', 1), issue('acme/shop', 2)],
      });
    }
    if ((params = match('/repos/:owner/:repo', path)) && request.method === 'GET') {
      return respond(200, {
        full_name: `${params['owner']}/${params['repo']}`,
        default_branch: 'main',
        private: true,
      });
    }
    if ((params = match('/repos/:owner/:repo/issues/:number', path))) {
      const repository = `${params['owner']}/${params['repo']}`;
      const number = Number(params['number']);
      if (request.method === 'GET') return respond(200, issue(repository, number));
      if (request.method === 'PATCH')
        return respond(200, { ...issue(repository, number), state: 'closed' }, request.json());
    }
    if ((params = match('/repos/:owner/:repo/issues/:number/comments', path))) {
      if (request.method === 'GET') return respond(200, []);
      if (request.method === 'POST') {
        const id = this.nextId++;
        const repository = `${params['owner']}/${params['repo']}`;
        return respond(
          201,
          { id, html_url: `${base}/${repository}/issues/${params['number']}#issuecomment-${id}` },
          request.json(),
        );
      }
    }
    if (match('/repos/:owner/:repo/issues/:number/labels', path) && request.method === 'POST') {
      return respond(200, [], request.json());
    }
    if (
      match('/repos/:owner/:repo/issues/:number/labels/:name', path) &&
      request.method === 'DELETE'
    ) {
      return respond(200, []);
    }
    if ((params = match('/repos/:owner/:repo/pulls', path))) {
      const repository = `${params['owner']}/${params['repo']}`;
      if (request.method === 'GET') {
        const head = request.query.get('head')?.split(':').pop();
        const open = [...this.pulls.entries()]
          .filter(
            ([id, pull]) =>
              id.startsWith(`${repository}#`) && pull.state === 'open' && pull.head === head,
          )
          .map(([, pull]) => this.pullJson(repository, pull));
        return respond(200, open);
      }
      if (request.method === 'POST') {
        const input = request.json<{
          title?: string;
          head?: string;
          base?: string;
          body?: string;
        }>();
        const number = this.nextId++;
        const pull = {
          number,
          title: input.title ?? '',
          head: input.head ?? '',
          base: input.base ?? 'main',
          body: input.body ?? '',
          state: 'open',
        };
        this.pulls.set(`${repository}#${number}`, pull);
        return respond(201, this.pullJson(repository, pull), input);
      }
    }
    if ((params = match('/repos/:owner/:repo/pulls/:number', path))) {
      const repository = `${params['owner']}/${params['repo']}`;
      const pull = this.pulls.get(`${repository}#${params['number']}`);
      if (!pull) return respond(404, { message: 'Not Found' });
      if (request.method === 'GET') return respond(200, this.pullJson(repository, pull));
      if (request.method === 'PATCH') {
        const input = request.json<{ body?: string }>();
        if (input.body !== undefined) pull.body = input.body;
        return respond(200, this.pullJson(repository, pull), input);
      }
    }
    respond(404, { message: `No stub for ${request.method} ${path}` });
  }

  private pullJson(
    repository: string,
    pull: {
      number: number;
      title: string;
      head: string;
      base: string;
      body: string;
      state: string;
    },
  ) {
    return {
      number: pull.number,
      title: pull.title,
      body: pull.body,
      state: pull.state,
      draft: false,
      merged: false,
      merged_at: null,
      html_url: `${this.publicBase()}/${repository}/pull/${pull.number}`,
      head: { ref: pull.head },
      base: { ref: pull.base },
    };
  }

  /** The web origin links point to; GitHub's are https, and the server keeps https links only. */
  private publicBase(): string {
    return 'https://127.0.0.1';
  }
}
