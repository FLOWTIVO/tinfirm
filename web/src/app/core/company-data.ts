import type { Page } from './paged-resource';

/** Upper bound for "load everything" helpers, so a huge company can't loop forever. */
const MAX_PAGES = 20;

/** Follows `nextCursor` until the end (or {@link MAX_PAGES}) and returns every item. */
export async function loadAll<T>(
  load: (cursor: string | undefined) => Promise<Page<T>>,
): Promise<T[]> {
  const items: T[] = [];
  let cursor: string | undefined;
  for (let i = 0; i < MAX_PAGES; i++) {
    const page = await load(cursor);
    items.push(...page.items);
    if (!page.nextCursor) break;
    cursor = page.nextCursor;
  }
  return items;
}

/**
 * Maps every item with `load`, at most `limit` at a time, in their order: a page that reads one
 * thing per row asks the server a few at a time, not all at once. `load` should not throw.
 */
export async function loadEach<T, R>(
  items: readonly T[],
  limit: number,
  load: (item: T) => Promise<R>,
): Promise<R[]> {
  const results = new Array<R>(items.length);
  let next = 0;
  const worker = async () => {
    while (next < items.length) {
      const index = next++;
      results[index] = await load(items[index]);
    }
  };
  await Promise.all(Array.from({ length: Math.min(limit, items.length) }, worker));
  return results;
}
