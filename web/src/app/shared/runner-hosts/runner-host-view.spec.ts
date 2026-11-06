import type { RunnerHost } from '../../api/generated/models';
import { aRunnerHost } from '../../testing/fixtures';
import { takesTurns } from './runner-host-view';

describe('a runner host’s turns', () => {
  it.each<[string, Partial<RunnerHost>, boolean]>([
    ['online', {}, true],
    ['offline', { online: false }, false],
    ['draining', { draining: true }, false],
    // Its version decides nothing, low or high (ADR-0109).
    ['online with runner 0.0.1', { version: '0.0.1' }, true],
    ['online with runner 9.9.9', { version: '9.9.9' }, true],
  ])('takes turns when %s: %s', (_, overrides, takes) => {
    expect(takesTurns(aRunnerHost(overrides))).toBe(takes);
  });
});
