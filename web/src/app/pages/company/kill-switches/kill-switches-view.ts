import type {
  ExternalSystem,
  KillSwitch,
  KillSwitches,
  KillSwitchKind,
} from '../../../api/generated/models';

/**
 * The kill switches' page in groups (the package 6 contract, §5.4): the company's stop first, then
 * the paused systems, the suspended principals, and the other switches that are on. Pure, so that
 * every answer of `GET /kill-switches` is tested as a table.
 */
export interface SwitchGroups {
  readonly systems: KillSwitch[];
  readonly principals: KillSwitch[];
  readonly others: KillSwitch[];
}

const PRINCIPAL_KINDS: readonly KillSwitchKind[] = [
  'MEMBER_SUSPENDED',
  'SERVICE_IDENTITY_SUSPENDED',
];
const OTHER_KINDS: readonly KillSwitchKind[] = [
  'AGENT_PAUSED',
  'TRIGGER_PAUSED',
  'PERSONAL_MCP_SERVERS_OFF',
];

export function switchGroups(switches: KillSwitches): SwitchGroups {
  return {
    systems: switches.switches.filter((s) => s.kind === 'SYSTEM_PAUSED'),
    principals: switches.switches.filter((s) => PRINCIPAL_KINDS.includes(s.kind)),
    others: switches.switches.filter((s) => OTHER_KINDS.includes(s.kind)),
  };
}

/** Whether nothing at all is switched off. */
export function nothingOn(switches: KillSwitches): boolean {
  return !switches.runsStop && switches.switches.length === 0 && !switches.mySuspension;
}

/**
 * The systems an admin may pause: the company's own (a personal MCP server is stopped by the
 * company's switch of personal servers, `400 PERSONAL_SYSTEM`), not paused already, by name.
 */
export function pausableSystems(systems: readonly ExternalSystem[]): ExternalSystem[] {
  return systems
    .filter((s) => !s.personal && !s.paused)
    .sort((a, b) => a.name.localeCompare(b.name));
}

/** "Resume its 2 triggers too": the resume dialog's choice, or null when the pause paused none. */
export function resumeTriggersLabel(kill: KillSwitch): string | null {
  const n = kill.pausedTriggers?.length ?? 0;
  if (n === 0) return null;
  return n === 1 ? 'Resume its trigger too' : `Resume its ${n} triggers too`;
}
