import type { RunnerHost } from '../../api/generated/models';

/** The first runner version with the always-on model proxy, which turns paid by a budget need (package 2, §7). */
export const GATEWAY_RUNNER_VERSION = '0.2.0';

/**
 * Why a host that connected takes no turn paid by a budget (`gatewayRuns` false: its runner is older
 * than 0.2.0), in words for its scope; null when it may, or when it never connected (its version is
 * not known yet).
 */
export function gatewayRunsNote(host: RunnerHost): string | null {
  if (host.gatewayRuns || !host.lastSeenAt) return null;
  const runner = host.version ? `Runner ${host.version}` : 'Its runner';
  const until =
    host.scope === 'COMPANY'
      ? 'Until then it runs nothing for the company.'
      : 'Until then it runs only your own subscriptions.';
  return `${runner} is too old for turns paid by a budget: upgrade it to ${GATEWAY_RUNNER_VERSION} or later. ${until}`;
}

/** Whether a company host takes turns now: online, not draining, and new enough for a budget's. */
export function takesTurns(host: RunnerHost): boolean {
  return host.online && !host.draining && host.gatewayRuns;
}
