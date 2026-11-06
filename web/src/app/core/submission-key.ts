import { HttpErrorResponse } from '@angular/common/http';

/**
 * One `Idempotency-Key` per submission (package R, the contract's §3.5; CONVENTIONS §15.14): the
 * same request sent again after it failed on the way (no answer, or a 5xx) keeps its key, so the
 * server answers the first one's outcome instead of doing it twice; any other request gets a new
 * key. A submission the server answered, done or refused for good, is settled, and the next one
 * starts afresh.
 */
export class SubmissionKey {
  private pending: { readonly request: string; readonly key: string } | null = null;

  /** `retryable` says which failure keeps the key: by default an HTTP one on the way. */
  constructor(private readonly retryable: (error: unknown) => boolean = isRetryable) {}

  /** The key for this request: the pending one's when it is the same request, else a new one. */
  keyFor(request: unknown): string {
    const fingerprint = JSON.stringify(request ?? null);
    if (this.pending?.request !== fingerprint) {
      this.pending = { request: fingerprint, key: crypto.randomUUID() };
    }
    return this.pending.key;
  }

  /** After an answer: keeps the key only when the request may be retried as it was. */
  settle(error?: unknown): void {
    if (error === undefined || !this.retryable(error)) this.pending = null;
  }
}

/** A failure on the way, where the same request may be sent again: no answer, a timeout or a 5xx. */
export function isRetryable(error: unknown): boolean {
  if (!(error instanceof HttpErrorResponse)) return false;
  return error.status === 0 || error.status === 408 || error.status >= 500;
}

/**
 * Sends one submission with its key (`call` gets it, for its `Idempotency-Key` header), and
 * settles the key with the answer: the same request sent again after a failure on the way keeps it.
 */
export async function submitWithKey<T>(
  submission: SubmissionKey,
  request: unknown,
  call: (key: string) => Promise<T>,
): Promise<T> {
  const key = submission.keyFor(request);
  try {
    const answer = await call(key);
    submission.settle();
    return answer;
  } catch (error) {
    submission.settle(error);
    throw error;
  }
}
