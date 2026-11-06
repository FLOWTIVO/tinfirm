import type { AgentMemberShare, Member } from '../api/generated/models';

/**
 * Whom an agent or a skill is shared with on their own (CONVENTIONS §15.5; the skills contract
 * §4.3): the people the sharing lists, and its filter. The agent editor and the skill page share
 * them.
 */

/** A person something may be shared with on their own, by their principal. */
export interface SharePerson {
  readonly principalId: string;
  readonly name: string;
  readonly email: string | null;
}

/**
 * The people the sharing lists: the company's members, but not the owner (who uses it already),
 * then anyone it is shared with whom the members could not all be read for, by name. A member
 * without a principal cannot be shared with.
 */
export function sharePeople(
  members: readonly Member[],
  shared: readonly AgentMemberShare[],
  owner: { readonly principalId?: string | null; readonly userId?: string | null },
): SharePerson[] {
  const people: SharePerson[] = members
    .filter((m): m is Member & { principalId: string } => !!m.principalId)
    .filter((m) => m.principalId !== owner.principalId && m.userId !== owner.userId)
    .map((m) => ({ principalId: m.principalId, name: m.displayName, email: m.email }));
  const known = new Set(people.map((p) => p.principalId));
  for (const m of shared) {
    if (!known.has(m.principalId))
      people.push({ principalId: m.principalId, name: m.name, email: null });
  }
  return people.sort((a, b) => a.name.localeCompare(b.name));
}

/** Whether a person matches the sharing's filter, by name or email, ignoring case. */
export function matchesPerson(person: SharePerson, filter: string): boolean {
  const q = filter.trim().toLowerCase();
  return (
    !q || person.name.toLowerCase().includes(q) || (person.email ?? '').toLowerCase().includes(q)
  );
}
