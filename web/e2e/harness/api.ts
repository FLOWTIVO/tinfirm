/**
 * A client of the real API as one person: Playwright's request context, which keeps the session cookie, plus the
 * CSRF header the server wants on every mutating request (`X-XSRF-TOKEN` from the `XSRF-TOKEN` cookie, CONVENTIONS
 * §5). The seed uses it, and the fixtures give one to every persona.
 */
import { type APIRequestContext, type APIResponse, request } from '@playwright/test';

export class ApiError extends Error {
  readonly status: number;
  readonly body: unknown;

  constructor(method: string, path: string, status: number, body: unknown) {
    const problem = body as { code?: string; detail?: string; title?: string } | null;
    super(
      `${method} ${path} answered ${status}${problem?.code ? ` ${problem.code}` : ''}: ${problem?.detail ?? problem?.title ?? JSON.stringify(body)}`,
    );
    this.status = status;
    this.body = body;
  }
}

/** A JSON answer as the API sent it: the specs read the fields they assert on. */
// eslint-disable-next-line @typescript-eslint/no-explicit-any -- a test client; the specs type what they read
export type Body = any;

export interface CallOptions {
  /** Statuses that are not an error; by default every 2xx. */
  ok?: number[];
  headers?: Record<string, string>;
}

export class Api {
  readonly context: APIRequestContext;
  readonly baseURL: string;
  /** Who is logged in, from `POST /api/auth/login`. */
  me: { id: string; email: string; displayName: string; instanceAdmin: boolean } | null = null;

  constructor(context: APIRequestContext, baseURL: string) {
    this.context = context;
    this.baseURL = baseURL;
  }

  /**
   * A client with no session: its cookie jar starts empty. Inside a test, Playwright's `request.newContext()` takes the
   * test's options, among them the storage state of the test's persona (`test.use({ persona })`); a client that kept
   * that session would act as the persona, and a login on it would rotate the persona's session id (session fixation)
   * and so log the persona's page and API client out.
   */
  static async anonymous(baseURL: string): Promise<Api> {
    const context = await request.newContext({
      baseURL,
      storageState: { cookies: [], origins: [] },
    });
    return new Api(context, baseURL);
  }

  /** Logs in with email and password (local accounts, CONVENTIONS §5), on a client of its own (`anonymous`). */
  static async login(baseURL: string, email: string, password: string): Promise<Api> {
    const api = await Api.anonymous(baseURL);
    // The SPA's first call: it sets the XSRF-TOKEN cookie that the login needs.
    await api.get('/api/auth/providers');
    api.me = await api.post('/api/auth/login', { email, password });
    return api;
  }

  async dispose(): Promise<void> {
    await this.context.dispose();
  }

  /** Writes the session as Playwright's storage state, for a browser context of this person. */
  async saveStorageState(path: string): Promise<void> {
    await this.context.storageState({ path });
  }

  async get<T = Body>(path: string, options: CallOptions = {}): Promise<T> {
    return this.call<T>('GET', path, undefined, options);
  }

  async post<T = Body>(path: string, body?: unknown, options: CallOptions = {}): Promise<T> {
    return this.call<T>('POST', path, body, options);
  }

  async put<T = Body>(path: string, body?: unknown, options: CallOptions = {}): Promise<T> {
    return this.call<T>('PUT', path, body, options);
  }

  async patch<T = Body>(path: string, body?: unknown, options: CallOptions = {}): Promise<T> {
    return this.call<T>('PATCH', path, body, options);
  }

  async delete<T = Body>(path: string, options: CallOptions = {}): Promise<T> {
    return this.call<T>('DELETE', path, undefined, options);
  }

  /** The raw answer, for a test that asserts on the status (e.g. a 404 for another person's conversation). */
  async raw(method: string, path: string, body?: unknown): Promise<APIResponse> {
    const headers = method === 'GET' || method === 'HEAD' ? {} : await this.csrfHeaders();
    return this.context.fetch(path, {
      method,
      headers,
      ...(body !== undefined ? { data: body } : {}),
    });
  }

  /** Every item of a cursor-paged list (`{items, nextCursor}`). */
  async all<T = Body>(path: string): Promise<T[]> {
    const items: T[] = [];
    let cursor: string | undefined;
    do {
      const separator = path.includes('?') ? '&' : '?';
      const page = await this.get<{ items: T[]; nextCursor?: string | null }>(
        `${path}${separator}limit=100${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`,
      );
      items.push(...page.items);
      cursor = page.nextCursor ?? undefined;
    } while (cursor);
    return items;
  }

  private async csrfHeaders(): Promise<Record<string, string>> {
    let token = await this.cookie('XSRF-TOKEN');
    if (!token) {
      await this.context.get('/api/auth/providers');
      token = await this.cookie('XSRF-TOKEN');
    }
    return token ? { 'X-XSRF-TOKEN': token } : {};
  }

  private async cookie(name: string): Promise<string | undefined> {
    const state = await this.context.storageState();
    return state.cookies.find((cookie) => cookie.name === name)?.value;
  }

  private async call<T>(
    method: string,
    path: string,
    body: unknown,
    options: CallOptions,
  ): Promise<T> {
    const headers = { ...(method === 'GET' ? {} : await this.csrfHeaders()), ...options.headers };
    const response = await this.context.fetch(path, {
      method,
      headers,
      ...(body !== undefined ? { data: body } : {}),
    });
    const text = await response.text();
    let parsed: unknown = text;
    try {
      parsed = text ? JSON.parse(text) : null;
    } catch {
      // Not JSON.
    }
    const ok = options.ok ? options.ok.includes(response.status()) : response.ok();
    if (!ok) throw new ApiError(method, path, response.status(), parsed);
    return parsed as T;
  }
}
