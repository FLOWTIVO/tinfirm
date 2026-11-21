import type { RunnerHost, RunnerHostProblem } from '../../api/generated/models';

/**
 * Whether a company host takes turns now: online, not draining, and reporting no problem
 * (ADR-0117). Its runner version decides nothing (ADR-0109): every host that speaks runner
 * protocol 2 has the always-on model proxy.
 */
export function takesTurns(host: RunnerHost): boolean {
  return host.online && !host.draining && !host.problems?.length;
}

/** What a host's problem code means, in words; the host's own message follows it. */
export const HOST_PROBLEM_LABELS: Record<RunnerHostProblem['code'], string> = {
  VM_DRIVER_NO_VM: 'Its VM driver can make no VM',
};
