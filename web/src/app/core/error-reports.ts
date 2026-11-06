import { HttpErrorResponse } from '@angular/common/http';
import { ErrorHandler, inject, Injectable, Injector } from '@angular/core';
import { type ActivatedRouteSnapshot, Router } from '@angular/router';
import { Api } from '../api/generated/api';
import { reportClientError } from '../api/generated/fn/error-reports/report-client-error';
import type { ClientError, ClientErrorFrame } from '../api/generated/models';
import { skipAuthRedirect } from './http-context';
import { isChunkLoadError } from './stale-chunk';

/** At most this many errors a page load, and one of a kind a minute (ADR-0070, §1). */
export const MAX_REPORTS_PER_PAGE = 10;
const SAME_ERROR_MS = 60_000;

const MAX_FRAMES = 50;
const KIND = /^[A-Za-z_$][A-Za-z0-9_$.]{0,99}$/;
/** Chromium and Safari's JavaScriptCore with `at`: `at fn (url:1:2)` or `at url:1:2`. */
const AT_FRAME = /^\s*at (?:(.+?) \()?(.+?):(\d+):(\d+)\)?\s*$/;
/** Firefox and Safari: `fn@url:1:2`. */
const AT_SIGN_FRAME = /^\s*(.*?)@(.+?):(\d+):(\d+)\s*$/;

/** The frames of an error's stack, the innermost first, as the browser wrote them. */
export function framesOf(stack: string | undefined): ClientErrorFrame[] {
  const frames: ClientErrorFrame[] = [];
  for (const line of (stack ?? '').split('\n')) {
    if (frames.length === MAX_FRAMES) break;
    const match = AT_FRAME.exec(line) ?? AT_SIGN_FRAME.exec(line);
    if (!match) continue;
    const [, fn, file, lineNumber, column] = match;
    frames.push({
      ...(fn ? { function: fn.slice(0, 200) } : {}),
      file: file.slice(0, 500),
      line: Number(lineNumber),
      column: Number(column),
    });
  }
  return frames;
}

/** `/c/:companyId/conversations/:conversationId`: the route's pattern, never its URL. */
export function routePattern(root: ActivatedRouteSnapshot | null | undefined): string {
  const parts: string[] = [];
  for (let route = root; route; route = route.firstChild) {
    const path = route.routeConfig?.path;
    if (path) parts.push(path);
  }
  return ('/' + parts.join('/')).slice(0, 300);
}

/** What the server is told of an error: its type, message and frames; the server scrubs it. */
export function clientError(error: unknown, route: string): ClientError {
  if (error instanceof Error) {
    return {
      kind: KIND.test(error.name) ? error.name : 'Error',
      message: error.message.slice(0, 1000),
      route,
      frames: framesOf(error.stack),
    };
  }
  return { kind: 'Error', message: String(error).slice(0, 1000), route, frames: [] };
}

/**
 * Posts the web app's errors to the server's error reports (ADR-0070, §1), which keep them scrubbed for the
 * instance admins and, while delivery is automatic, send them to the vendor. Not a failed request (the server
 * captured its own failure) nor a stale chunk after a deploy, which reloads the page. Fire and forget: a report
 * that fails is dropped, and never sends the person to the login page.
 */
@Injectable({ providedIn: 'root' })
export class ErrorReporter {
  private readonly api = inject(Api);
  private readonly router = inject(Router);

  private sent = 0;
  private readonly lastSent = new Map<string, number>();

  report(error: unknown, now: number = Date.now()): void {
    if (error instanceof HttpErrorResponse || isChunkLoadError(error)) return;
    if (this.sent >= MAX_REPORTS_PER_PAGE) return;
    const body = clientError(error, routePattern(this.router.routerState.snapshot.root));
    const key = `${body.kind}\n${body.message ?? ''}\n${body.route ?? ''}`;
    const last = this.lastSent.get(key);
    if (last !== undefined && now - last < SAME_ERROR_MS) return;
    this.lastSent.set(key, now);
    this.sent++;
    this.api.invoke(reportClientError, { body }, skipAuthRedirect()).catch(() => undefined);
  }
}

/**
 * An ErrorHandler that logs to the console as Angular's own does, and then reports the error. The reporter is
 * looked up when the first error comes, so that the handler depends on nothing the router or HTTP need.
 */
@Injectable()
export class ReportingErrorHandler extends ErrorHandler {
  private readonly injector = inject(Injector);

  override handleError(error: unknown): void {
    super.handleError(error);
    try {
      this.injector.get(ErrorReporter).report(error);
    } catch {
      // Reporting an error never raises another.
    }
  }
}
