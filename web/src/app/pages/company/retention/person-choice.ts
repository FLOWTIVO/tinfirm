import type { Member } from '../../../api/generated/models';

/**
 * A person a hold or an erasure names, by their principal in the company: a member still here
 * (`Member.principalId`), or one who left (from their checklist). The value is the principal's id.
 */
export interface PersonChoice {
  readonly value: string;
  readonly label: string;
  /** Their name alone, without "(left)". */
  readonly name: string;
  /** They left the company: everything of theirs is erased, not only their ended conversations. */
  readonly left: boolean;
}

export function personChoices(
  members: readonly Member[],
  left: { principalId: string; name: string } | null,
): PersonChoice[] {
  const choices = members
    .filter((m): m is Member & { principalId: string } => !!m.principalId)
    .filter((m) => m.principalId !== left?.principalId)
    .sort((a, b) => a.displayName.localeCompare(b.displayName))
    .map((m) => ({ value: m.principalId, label: m.displayName, name: m.displayName, left: false }));
  return left
    ? [
        { value: left.principalId, label: `${left.name} (left)`, name: left.name, left: true },
        ...choices,
      ]
    : choices;
}
