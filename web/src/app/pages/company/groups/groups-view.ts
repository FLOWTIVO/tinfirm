import type { GroupMember } from '../../../api/generated/models';
import { initials } from '../../../shared/forms';

/** A person the member picker offers: a member of the company, or of the group being changed. */
export type PickablePerson = GroupMember;

/**
 * The picker's people: the company's members, then a member of the group the list does not hold
 * (when the members could not all be read), by name.
 */
export function pickablePeople(
  members: readonly PickablePerson[],
  groupMembers: readonly PickablePerson[],
): PickablePerson[] {
  const known = new Set(members.map((m) => m.userId));
  return [...members, ...groupMembers.filter((m) => !known.has(m.userId))].sort((a, b) =>
    a.displayName.localeCompare(b.displayName),
  );
}

/** Whether a person matches the picker's filter, by name or email, ignoring case. */
export function matchesFilter(person: PickablePerson, filter: string): boolean {
  const q = filter.trim().toLowerCase();
  return (
    !q || person.displayName.toLowerCase().includes(q) || person.email.toLowerCase().includes(q)
  );
}

/** How many initials a group's row stacks before "+2". */
const STACKED = 4;

/** A group's members as its row stacks them: a few initials, and how many more. */
export function stackOf(members: readonly GroupMember[]): { initials: string[]; more: number } {
  return {
    initials: members.slice(0, STACKED).map((m) => initials(m.displayName)),
    more: Math.max(0, members.length - STACKED),
  };
}
