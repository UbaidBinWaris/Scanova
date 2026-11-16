/** Joins class names, skipping falsy ones. Components avoid conflicting utilities by design. */
export function cn(...classes: (string | false | null | undefined)[]): string {
  return classes.filter(Boolean).join(' ');
}
