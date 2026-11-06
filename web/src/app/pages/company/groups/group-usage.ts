import type { AccountGrant, GroupUsage } from '../../../api/generated/models';
import { ACCESS_LABELS } from '../../../shared/labels';

/**
 * What a group may use (plan §5, Members › Groups), as `getGroupUsage` answers it for one group: the
 * agents shared with it, the systems' credentials lent to it by grants (ADR-0072), and the service
 * accounts that name it among their users, each in its order.
 */

/** A group's usage as the page holds it: loading (no usage, no error), read, or failed. */
export interface GroupUsageState {
  readonly usage: GroupUsage | null;
  readonly error: unknown;
}

/** "GitHub and Jira", "GitHub, Jira and Slack". */
function andList(names: readonly string[]): string {
  return names.length < 2
    ? (names[0] ?? '')
    : `${names.slice(0, -1).join(', ')} and ${names[names.length - 1]}`;
}

/**
 * "GitHub and Jira with svc-dev, read-only": the credentials lent to a group in as few lines as its
 * row needs, one per credential name and access.
 */
export function accountLines(usage: GroupUsage): string[] {
  const lines = new Map<string, { names: string[]; credential: string; access: string }>();
  for (const grant of usage.grants) {
    const key = `${grant.credentialName} ${grant.access}`;
    const line = lines.get(key) ?? {
      names: [],
      credential: grant.credentialName,
      access: ACCESS_LABELS[grant.access],
    };
    line.names.push(grant.systemName);
    lines.set(key, line);
  }
  return [...lines.values()]
    .map((l) => `${andList([...l.names].sort())} with ${l.credential}, ${l.access}`)
    .sort();
}

/** "GitHub with svc-dev, read-only": a grant as the group's members act with it. */
export function grantPhrase(grant: AccountGrant): string {
  return `${grant.systemName} with ${grant.credentialName}, ${ACCESS_LABELS[grant.access]}`;
}

/** "Nothing shared with it yet", or the counts: "1 agent, 2 credentials, 1 service account". */
export function usageSummary(usage: GroupUsage): string {
  const parts: string[] = [];
  const identities = usage.serviceIdentities.length;
  if (usage.agents.length) {
    parts.push(`${usage.agents.length} ${usage.agents.length === 1 ? 'agent' : 'agents'}`);
  }
  if (usage.grants.length) {
    parts.push(
      `${usage.grants.length} ${usage.grants.length === 1 ? 'credential' : 'credentials'}`,
    );
  }
  if (identities) {
    parts.push(`${identities} ${identities === 1 ? 'service account' : 'service accounts'}`);
  }
  return parts.length ? parts.join(', ') : 'Nothing shared with it or lent to it yet';
}
