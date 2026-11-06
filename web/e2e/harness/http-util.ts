/** Small helpers for the harness's own HTTP servers (the fakes, the stubs, the control API). */
import { createServer, type IncomingMessage, type Server, type ServerResponse } from 'node:http';

export interface Request {
  method: string;
  /** The path without the query. */
  path: string;
  query: URLSearchParams;
  headers: IncomingMessage['headers'];
  body: string;
  json<T = unknown>(): T;
}

export type Handler = (request: Request, response: ServerResponse) => void | Promise<void>;

export async function readBody(message: IncomingMessage): Promise<string> {
  const chunks: Buffer[] = [];
  for await (const chunk of message) chunks.push(chunk as Buffer);
  return Buffer.concat(chunks).toString('utf8');
}

export function sendJson(response: ServerResponse, status: number, body: unknown): void {
  const text = JSON.stringify(body);
  response.writeHead(status, {
    'content-type': 'application/json',
    'content-length': Buffer.byteLength(text),
  });
  response.end(text);
}

export function sendText(
  response: ServerResponse,
  status: number,
  body: string,
  type = 'text/plain',
): void {
  response.writeHead(status, {
    'content-type': `${type}; charset=utf-8`,
    'content-length': Buffer.byteLength(body),
  });
  response.end(body);
}

/** The bearer token of an Authorization header, or null. */
export function bearer(header: string | undefined): string | null {
  return header && /^bearer /i.test(header) ? header.slice(7).trim() : null;
}

/** Starts a server on 127.0.0.1 that hands every request, its body read, to the handler; errors answer 500. */
export function startServer(port: number, handler: Handler): Promise<Server> {
  const server = createServer(async (message, response) => {
    try {
      const url = new URL(message.url ?? '/', 'http://localhost');
      const body = await readBody(message);
      await handler(
        {
          method: message.method ?? 'GET',
          path: url.pathname,
          query: url.searchParams,
          headers: message.headers,
          body,
          json: <T>() => (body ? JSON.parse(body) : {}) as T,
        },
        response,
      );
    } catch (error) {
      if (!response.headersSent)
        sendJson(response, 500, { error: String((error as Error)?.message ?? error) });
      else response.end();
    }
  });
  return new Promise((resolvePromise, reject) => {
    server.once('error', reject);
    server.listen(port, '127.0.0.1', () => resolvePromise(server));
  });
}

export function closeServer(server: Server): Promise<void> {
  return new Promise((resolvePromise) => {
    server.closeAllConnections?.();
    server.close(() => resolvePromise());
  });
}

/** Matches `/a/:id/b` against a path; answers the named parts or null. */
export function match(pattern: string, path: string): Record<string, string> | null {
  const want = pattern.split('/');
  const have = path.split('/');
  if (want.length !== have.length) return null;
  const params: Record<string, string> = {};
  for (let i = 0; i < want.length; i++) {
    if (want[i].startsWith(':')) params[want[i].slice(1)] = decodeURIComponent(have[i]);
    else if (want[i] !== have[i]) return null;
  }
  return params;
}

export function sleep(ms: number): Promise<void> {
  return new Promise((resolvePromise) => setTimeout(resolvePromise, ms));
}

/** Polls `check` until it answers a value other than undefined, or throws after `timeoutMs` with `what`. */
export async function waitFor<T>(
  what: string,
  check: () => Promise<T | undefined> | T | undefined,
  timeoutMs = 30_000,
  intervalMs = 250,
): Promise<T> {
  const deadline = Date.now() + timeoutMs;
  let last: unknown;
  for (;;) {
    try {
      const value = await check();
      if (value !== undefined) return value;
    } catch (error) {
      last = error;
    }
    if (Date.now() > deadline) {
      throw new Error(
        `Timed out after ${timeoutMs} ms waiting for ${what}${last ? `: ${String(last)}` : ''}`,
      );
    }
    await sleep(intervalMs);
  }
}
