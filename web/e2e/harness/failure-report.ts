/**
 * `node e2e/harness/failure-report.ts`: what each failed attempt of the last run saw, for a reader of the CI job's log
 * without its artifact at hand (CI's e2e job runs it after the tests). For each directory under `test-results/`:
 *
 * - its `error-context.md`, the page's snapshot when the test failed;
 * - the requests to `/api/` in its trace that failed, never answered, or took more than 2 s.
 *
 * Then the server's warnings and errors (`e2e/.state/server.log`, Spring's ECS lines). It reads only; it never fails.
 * Runs with Node's type stripping, so erasable TypeScript only, and no dependency: a trace is a zip, read here.
 */
import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { inflateRawSync } from 'node:zlib';

const SLOW_MS = 2_000;
const CONTEXT_LINES = 200;
const SERVER_LINES = 300;

/** The entries of a zip whose names match, inflated; a zip64 or an unknown method is left out. */
function unzip(file: string, wanted: (name: string) => boolean): Map<string, Buffer> {
  const zip = readFileSync(file);
  const entries = new Map<string, Buffer>();
  let end = zip.length - 22;
  while (end >= 0 && zip.readUInt32LE(end) !== 0x06054b50) end--;
  if (end < 0) return entries;
  const count = zip.readUInt16LE(end + 10);
  let at = zip.readUInt32LE(end + 16);
  for (let i = 0; i < count; i++) {
    const method = zip.readUInt16LE(at + 10);
    const size = zip.readUInt32LE(at + 20);
    const nameLength = zip.readUInt16LE(at + 28);
    const extraLength = zip.readUInt16LE(at + 30);
    const commentLength = zip.readUInt16LE(at + 32);
    const local = zip.readUInt32LE(at + 42);
    const name = zip.toString('utf8', at + 46, at + 46 + nameLength);
    at += 46 + nameLength + extraLength + commentLength;
    if (!wanted(name)) continue;
    const start = local + 30 + zip.readUInt16LE(local + 26) + zip.readUInt16LE(local + 28);
    const data = zip.subarray(start, start + size);
    if (method === 0) entries.set(name, data);
    else if (method === 8) entries.set(name, inflateRawSync(data));
  }
  return entries;
}

interface Resource {
  startedDateTime?: string;
  time?: number;
  request?: { method?: string; url?: string };
  response?: { status?: number; _failureText?: string };
}

/** The requests to the API that failed, never answered, or were slow, in the order they started. */
function apiTrouble(trace: string): string[] {
  const lines: { started: string; text: string }[] = [];
  for (const [, data] of unzip(trace, (name) => name.endsWith('.network'))) {
    for (const line of data.toString('utf8').split('\n')) {
      if (!line.trim()) continue;
      let event: { type?: string; snapshot?: Resource };
      try {
        event = JSON.parse(line);
      } catch {
        continue;
      }
      const resource = event.snapshot;
      if (event.type !== 'resource-snapshot' || !resource?.request?.url) continue;
      const url = new URL(resource.request.url);
      if (!url.pathname.startsWith('/api/')) continue;
      const status = resource.response?.status ?? -1;
      const time = Math.round(resource.time ?? 0);
      if (status > 0 && status < 400 && time <= SLOW_MS) continue;
      const started = resource.startedDateTime ?? '';
      // The query's names only: a callback's carry a code or a state, which the log must not keep.
      const names = [...new Set(url.searchParams.keys())];
      const query = names.length ? `?${names.map((name) => `${name}=…`).join('&')}` : '';
      lines.push({
        started,
        text: `${started} ${resource.request.method ?? ''} ${url.pathname}${query} → ${status > 0 ? status : `no answer (${resource.response?._failureText ?? 'no reason'})`} in ${time} ms`,
      });
    }
  }
  return lines.sort((a, b) => a.started.localeCompare(b.started)).map((line) => line.text);
}

function group(title: string, body: () => void): void {
  console.log(`::group::${title}`);
  try {
    body();
  } catch (error) {
    console.log(`(could not read it: ${(error as Error).message})`);
  }
  console.log('::endgroup::');
}

const results = resolve('test-results');
const failed = existsSync(results)
  ? readdirSync(results, { withFileTypes: true }).filter((entry) => entry.isDirectory())
  : [];
for (const entry of failed) {
  const dir = resolve(results, entry.name);
  group(entry.name, () => {
    const context = resolve(dir, 'error-context.md');
    if (existsSync(context)) {
      const lines = readFileSync(context, 'utf8').split('\n');
      console.log(lines.slice(0, CONTEXT_LINES).join('\n'));
      if (lines.length > CONTEXT_LINES) console.log(`… ${lines.length - CONTEXT_LINES} more lines`);
    }
    const trace = resolve(dir, 'trace.zip');
    if (existsSync(trace)) {
      const trouble = apiTrouble(trace);
      console.log(
        `\nAPI requests that failed, never answered, or took over ${SLOW_MS} ms: ${trouble.length}`,
      );
      for (const line of trouble) console.log(`  ${line}`);
    }
  });
}

const serverLog = resolve('e2e/.state/server.log');
if (existsSync(serverLog)) {
  group('The server’s warnings and errors', () => {
    const picked: string[] = [];
    for (const line of readFileSync(serverLog, 'utf8').split('\n')) {
      if (!line.startsWith('{')) continue;
      let entry: Record<string, unknown>;
      try {
        entry = JSON.parse(line);
      } catch {
        continue;
      }
      const log = entry['log'] as { level?: string; logger?: string } | undefined;
      const level = (entry['log.level'] as string | undefined) ?? log?.level;
      if (level !== 'WARN' && level !== 'ERROR') continue;
      const logger = (entry['log.logger'] as string | undefined) ?? log?.logger ?? '';
      const error = entry['error'] as { message?: string; stack_trace?: string } | undefined;
      const stack = (entry['error.stack_trace'] as string | undefined) ?? error?.stack_trace;
      const cause = (entry['error.message'] as string | undefined) ?? error?.message;
      picked.push(
        [
          `${String(entry['@timestamp'] ?? '')} ${level} ${logger}: ${String(entry['message'] ?? '')}`,
          cause ? `    ${cause}` : '',
          stack
            ? stack
                .split('\n')
                .slice(0, 8)
                .map((frame) => `    ${frame}`)
                .join('\n')
            : '',
        ]
          .filter(Boolean)
          .join('\n'),
      );
    }
    console.log(`${picked.length} warnings and errors; the last ${SERVER_LINES}:`);
    console.log(picked.slice(-SERVER_LINES).join('\n'));
  });
}
