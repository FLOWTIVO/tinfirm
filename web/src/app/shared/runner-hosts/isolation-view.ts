import type {
  AgentIsolation,
  AgentIsolationSetting,
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

/** One choice of an agent's isolation in its editor. */
export interface IsolationOption {
  readonly value: AgentIsolationSetting;
  readonly label: string;
}

/**
 * The choices of an agent's isolation (ADR-0113, decision 4): automatic, which names the level its
 * runs go to now, and each level the company's hosts report; a chosen level no host reports any
 * more stays listed, so that the select shows it.
 */
export function isolationOptions(isolation: AgentIsolation): IsolationOption[] {
  const now =
    isolation.setting === 'AUTOMATIC' && isolation.level
      ? ` (now: ${LEVEL_WORDS[isolation.level]})`
      : '';
  const options: IsolationOption[] = [
    {
      value: 'AUTOMATIC',
      label: `Automatic: the strongest level your company’s hosts report${now}`,
    },
  ];
  const levels = [...isolation.offered];
  if (isolation.setting !== 'AUTOMATIC' && !levels.includes(isolation.setting)) {
    levels.push(isolation.setting);
  }
  for (const level of levels) {
    options.push({ value: level, label: `Only hosts that report ${LEVEL_WORDS[level]}` });
  }
  return options;
}

/** Whether the editor shows the choice: the hosts report more than one level, or one is chosen. */
export function showsIsolationChoice(isolation: AgentIsolation | null | undefined): boolean {
  return !!isolation && (isolation.offered.length > 1 || isolation.setting !== 'AUTOMATIC');
}

/** Said before a change to a chosen level is saved: what it costs open conversations. */
export const ISOLATION_CHANGE_WARNING =
  'Open conversations whose session is on a host of another level start a new session of the runtime on their next turn. The conversation and its workspace stay; what the runtime remembered does not.';

/** The notice of a chosen level no company host reports any more; null otherwise. */
export function chosenUnavailableText(isolation: AgentIsolation | null | undefined): string | null {
  if (!isolation?.chosenUnavailable || isolation.setting === 'AUTOMATIC') return null;
  return `No company runner host reports ${LEVEL_WORDS[isolation.setting]} any more: new conversations with this agent are refused, and turns of open ones wait, until one does or another level is chosen.`;
}

/** The warning where hostile input meets a container; null when it does not. */
export function containerWarningText(isolation: AgentIsolation | null | undefined): string | null {
  if (!isolation?.containerWarning) return null;
  return 'This agent’s runs can start from outside the company (a trigger on a tracker, a chat channel or a webhook) and run in a container, where an escape reaches the runner host and its credentials. Choose a stronger level, or ask an admin for a runner host that reports vm-jail or machine.';
}
