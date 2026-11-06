import type { Agent, AgentStatus } from '../../../api/generated/models';
import { RUNTIME_LABELS } from '../../../shared/labels';

/** The words of the agent pages (plan §4.3). */

export const AGENT_STATUS_LABELS: Record<AgentStatus, string> = {
  ACTIVE: 'Active',
  PAUSED: 'Paused',
};

/** "Claude Code, then Codex": the runtimes in order, the first the default. */
export function runtimesText(runtimes: Agent['runtimes']): string {
  return runtimes.map((r) => RUNTIME_LABELS[r]).join(', then ');
}

/** "Analysts and Finance", "Analysts, Finance and Ops". */
export function namesText(names: readonly string[]): string {
  if (names.length <= 1) return names[0] ?? '';
  return `${names.slice(0, -1).join(', ')} and ${names[names.length - 1]}`;
}

/** The groups, then the people, the agent is shared with, by name. */
function sharedNames(agent: Pick<Agent, 'shares' | 'memberShares'>): string[] {
  return [...agent.shares.map((g) => g.name), ...agent.memberShares.map((m) => m.name)];
}

/** Whom the agent is shared with, in one line. */
export function sharingText(agent: Pick<Agent, 'shares' | 'memberShares' | 'ownerName'>): string {
  const names = sharedNames(agent);
  if (names.length === 0) {
    return `Not shared: only ${agent.ownerName ?? 'its owner'} uses it.`;
  }
  return `Shared with ${namesText(names)}.`;
}

/** Why the reader cannot use the agent, and whom to ask (plan §4.3). */
export function notUsableText(agent: Pick<Agent, 'shares' | 'memberShares' | 'ownerName'>): string {
  const owner = agent.ownerName ? `its owner ${agent.ownerName}` : 'an admin';
  const names = sharedNames(agent);
  if (names.length === 0) {
    return `It is not shared with anyone yet; ask ${owner} to share it with you or your group.`;
  }
  return `Shared with ${namesText(names)}; ask ${owner} to share it with you.`;
}
