import type { RunnerHost } from '../../api/generated/models';

/**
 * Whether a company host takes turns now: online and not draining. Its runner version decides
 * nothing (ADR-0109): every host that speaks runner protocol 2 has the always-on model proxy.
 */
export function takesTurns(host: RunnerHost): boolean {
  return host.online && !host.draining;
}
