import type { HttpTestingController, TestRequest } from '@angular/common/http/testing';
import type { Problem } from '../api/generated/models';
import { problem, settle } from './test-helpers';

/** A refusal a route answers: the status and the problem's code (and detail, details). */
export class Refuse {
  constructor(
    readonly status: number,
    readonly code: string,
    readonly extra: Partial<Problem> & { details?: Record<string, unknown> } = {},
  ) {}
}

/** What a route answers: a body, a refusal, or a function of the request. */
export type Answer = unknown | Refuse | ((req: TestRequest) => unknown);

/**
 * Answers every request a page makes, by `"METHOD path"` (the path without the query), until it
 * makes none: for pages that read many things at once. A request no route answers fails the test.
 * Answers the requests it served, in order, so a test can look at their bodies.
 */
export async function serve(
  http: HttpTestingController,
  routes: Record<string, Answer>,
  rounds = 30,
): Promise<TestRequest[]> {
  const served: TestRequest[] = [];
  let quiet = 0;
  for (let i = 0; i < rounds && quiet < 2; i++) {
    await settle();
    const pending = http.match(() => true);
    if (pending.length === 0) {
      quiet++;
      continue;
    }
    quiet = 0;
    for (const req of pending) {
      const key = `${req.request.method} ${req.request.url}`;
      if (!(key in routes)) throw new Error(`No route answers ${req.request.urlWithParams}`);
      const route = routes[key];
      const answer =
        typeof route === 'function' ? (route as (r: TestRequest) => unknown)(req) : route;
      served.push(req);
      if (answer instanceof Refuse) {
        req.flush(
          { ...problem(answer.status, answer.code), ...answer.extra },
          { status: answer.status, statusText: 'Refused' },
        );
      } else {
        req.flush(answer as never);
      }
    }
  }
  await settle();
  return served;
}
