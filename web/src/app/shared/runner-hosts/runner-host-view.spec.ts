import type { RunnerHost } from '../../api/generated/models';
import { aPersonalRunnerHost, aRunnerHost, AT } from '../../testing/fixtures';
import { gatewayRunsNote, takesTurns } from './runner-host-view';

describe('a runner host’s budget runs', () => {
  it.each<[string, RunnerHost, string | null]>([
    ['a company host of 0.2.0', aRunnerHost(), null],
    [
      'a host that never connected',
      aRunnerHost({ gatewayRuns: false, version: null, lastSeenAt: null, online: false }),
      null,
    ],
    [
      'a company host of 0.1.4',
      aRunnerHost({ gatewayRuns: false, version: '0.1.4' }),
      'Runner 0.1.4 is too old for turns paid by a budget: upgrade it to 0.2.0 or later. Until then it runs nothing for the company.',
    ],
    [
      'a personal host of 0.1.4',
      aPersonalRunnerHost({ gatewayRuns: false, version: '0.1.4' }),
      'Runner 0.1.4 is too old for turns paid by a budget: upgrade it to 0.2.0 or later. Until then it runs only your own subscriptions.',
    ],
    [
      'a host whose version is unknown',
      aRunnerHost({ gatewayRuns: false, version: null, lastSeenAt: AT.start }),
      'Its runner is too old for turns paid by a budget: upgrade it to 0.2.0 or later. Until then it runs nothing for the company.',
    ],
  ])('%s', (_, host, note) => {
    expect(gatewayRunsNote(host)).toBe(note);
  });

  it.each<[string, Partial<RunnerHost>, boolean]>([
    ['online and new enough', {}, true],
    ['offline', { online: false }, false],
    ['draining', { draining: true }, false],
    ['too old', { gatewayRuns: false }, false],
  ])('takes turns when %s: %s', (_, overrides, takes) => {
    expect(takesTurns(aRunnerHost(overrides))).toBe(takes);
  });
});
