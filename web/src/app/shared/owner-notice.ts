/**
 * The OWNERs a notice names (security review, row 18; the package 6 contract, §5.6): "Ann",
 * "Ann and Bob", "Ann, Bob and Cleo".
 */
export function ownerNames(owners: readonly string[]): string {
  if (owners.length <= 1) return owners.join('');
  return `${owners.slice(0, -1).join(', ')} and ${owners[owners.length - 1]}`;
}
