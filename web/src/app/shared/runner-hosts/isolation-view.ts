import type {
  RunnerHardening,
  RunnerHostIsolation,
  RunnerIsolationLevel,
  RunPlacementNote,
} from '../../api/generated/models';

/**
 * The words of a runner host's isolation on its page (ADR-0113, decisions 1 to 3): what the host
 * reports, never what Tinfirm checked. The level in its protocol name, the hardening mark, and what
 * an escape reaches on a host of that kind, in the words of ADR-0112's three paths (through the
 * VMM, through KVM, through the bridge) and ADR-0113's table, kept short.
 */
export interface IsolationLines {
  /** "Reports machine", "Reports vm-jail, stated by the operator", "Reports no level: …". */
  readonly reports: string;
  /** The hardening mark; null for a host that reports no level. */
  readonly hardening: string | null;
  /** What an escape reaches; null for a host that reports no level. */
  readonly reaches: string | null;
  /** "Tier microvm": the technology, as the host named it. */
  readonly tiers: string;
}

const LEVEL_WORDS: Record<RunnerIsolationLevel, string> = {
  MACHINE: 'machine',
  VM_JAIL: 'vm-jail',
  CONTAINER: 'container',
};

const HARDENING_WORDS: Record<RunnerHardening, string> = {
  YES: 'Hardened: the machine that runs its VMs offers an escaped process little of its kernel.',
  NO: 'Not hardened: the jail is there, and the kernel offers an escaped process more to try.',
  NONE: 'No machine of its own: the provider’s hypervisor is the boundary, and Tinfirm sees no machine.',
};

const REACHES: Record<RunnerIsolationLevel, string> = {
  MACHINE:
    'The credentials are on another machine. An escape through the VM’s monitor finds no credential and no other run; one through KVM reaches the runs on its machine, not the credentials; one through the bridge reaches the runner host’s credentials.',
  VM_JAIL:
    'The credentials are on this machine, out of a VM’s user’s reach. An escape through the VM’s monitor reads none and reaches no other run; one through KVM or through the bridge reaches every credential.',
  CONTAINER:
    'The credentials are on this machine, behind the kernel alone. An escape from a container reaches every credential and the other runs.',
};

/** The host page's lines for what a host reports; null before its first report. */
export function isolationLines(
  isolation: RunnerHostIsolation | null | undefined,
): IsolationLines | null {
  if (!isolation) return null;
  const tiers = `Tier ${isolation.tiers.map((t) => t.toLowerCase()).join(', ')}`;
  const level = isolation.level;
  if (!level) {
    return {
      reports: 'Reports no level: its runs are placed as on a container host.',
      hardening: null,
      reaches: null,
      tiers,
    };
  }
  const stated = isolation.stated ? ', stated by the operator' : '';
  return {
    reports: `Reports ${LEVEL_WORDS[level]}${stated}`,
    hardening: isolation.hardened ? HARDENING_WORDS[isolation.hardened] : null,
    reaches: REACHES[level],
    tiers,
  };
}

/** A level in its protocol name: "machine", "vm-jail", "container". */
export function levelWord(level: RunnerIsolationLevel): string {
  return LEVEL_WORDS[level];
}

const PLACEMENT_NOTES: Record<RunPlacementNote, string> = {
  STAYED_ON_WEAKER_HOST:
    'It stayed on the host that holds the conversation’s session; the agent’s new conversations go to a stronger level.',
  FRESH_SESSION_CHOSEN_LEVEL:
    'It started a new session of the runtime on the level chosen for the agent; the session was on a host of another level.',
  FRESH_SESSION_STRONGER_LEVEL:
    'It started a new session of the runtime: the weaker host that held the session could not take it.',
};

/**
 * A run's level of isolation on its page (ADR-0113, decision 4): "Placed on a host that reports
 * machine", and why it ran there when its conversation's session was on another level.
 */
export function placementText(
  level: RunnerIsolationLevel | null | undefined,
  note: RunPlacementNote | null | undefined,
): string | null {
  if (!level) return null;
  const placed = `Placed on a host that reports ${LEVEL_WORDS[level]}.`;
  return note ? `${placed} ${PLACEMENT_NOTES[note]}` : placed;
}
