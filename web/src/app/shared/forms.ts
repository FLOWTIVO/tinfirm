import type { FieldTree } from '@angular/forms/signals';

/** The first validation message of a field, for `<hlm-field-error>`. */
export function firstError<T>(field: FieldTree<T>): string {
  return field().errors()[0]?.message ?? '';
}

/** "Ada Lovelace" → "AL"; falls back to the first letter of an email. */
export function initials(name: string | undefined | null): string {
  const parts = (name ?? '')
    .trim()
    .split(/[\s@._-]+/)
    .filter(Boolean);
  if (parts.length === 0) return '?';
  const letters =
    parts.length === 1 ? parts[0].slice(0, 2) : parts[0][0] + parts[parts.length - 1][0];
  return letters.toUpperCase();
}
