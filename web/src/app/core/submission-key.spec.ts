import { HttpErrorResponse } from '@angular/common/http';
import { isRetryable, SubmissionKey, submitWithKey } from './submission-key';

const failed = (status: number) => new HttpErrorResponse({ status });

describe('SubmissionKey (package R)', () => {
  it('keeps the key of a request that failed on the way, and only of that request', () => {
    const keys = new SubmissionKey();
    const first = keys.keyFor({ decisionId: 'd1', body: { outcome: 'APPROVED' } });
    keys.settle(failed(503));
    expect(keys.keyFor({ decisionId: 'd1', body: { outcome: 'APPROVED' } })).toBe(first);
    expect(keys.keyFor({ decisionId: 'd1', body: { outcome: 'DENIED' } })).not.toBe(first);
  });

  it('keeps the key by the failures the caller names, when it names them', () => {
    const keys = new SubmissionKey((error) => error instanceof TypeError);
    const first = keys.keyFor({ a: 1 });
    keys.settle(new TypeError('Failed to fetch'));
    expect(keys.keyFor({ a: 1 })).toBe(first);
    keys.settle(failed(503));
    expect(keys.keyFor({ a: 1 })).not.toBe(first);
  });

  it.each([undefined, failed(409), failed(400), new Error('boom')])(
    'starts afresh after an answer (%s)',
    (outcome) => {
      const keys = new SubmissionKey();
      const first = keys.keyFor({ a: 1 });
      keys.settle(outcome);
      expect(keys.keyFor({ a: 1 })).not.toBe(first);
    },
  );

  it.each`
    status | retryable
    ${0}   | ${true}
    ${408} | ${true}
    ${500} | ${true}
    ${503} | ${true}
    ${400} | ${false}
    ${409} | ${false}
    ${422} | ${false}
  `('a $status may be sent again: $retryable', ({ status, retryable }) => {
    expect(isRetryable(failed(status))).toBe(retryable);
  });
});

describe('submitWithKey', () => {
  it('sends the same key again after a failure on the way, and a new one once answered', async () => {
    const keys = new SubmissionKey();
    const seen: string[] = [];
    await expect(
      submitWithKey(keys, { body: 'Hello' }, async (key) => {
        seen.push(key);
        throw failed(0);
      }),
    ).rejects.toBeInstanceOf(HttpErrorResponse);
    await submitWithKey(keys, { body: 'Hello' }, async (key) => seen.push(key));
    await submitWithKey(keys, { body: 'Hello' }, async (key) => seen.push(key));
    expect(seen[0]).toMatch(/^[0-9a-f-]{36}$/);
    expect(seen[1]).toBe(seen[0]);
    expect(seen[2]).not.toBe(seen[0]);
  });
});
