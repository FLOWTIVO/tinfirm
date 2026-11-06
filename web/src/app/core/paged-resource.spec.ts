import { Injector, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { settle } from '../testing/test-helpers';
import { type Page, pagedResource } from './paged-resource';

describe('pagedResource', () => {
  function setup() {
    const key = signal<string | undefined>('a');
    const calls: { key: string; cursor?: string }[] = [];
    const pending: { resolve: (page: Page<string>) => void; reject: (error: unknown) => void }[] =
      [];
    const list = pagedResource<string, string>({
      params: () => key(),
      load: (k, cursor) => {
        calls.push({ key: k, cursor });
        return new Promise<Page<string>>((resolve, reject) => pending.push({ resolve, reject }));
      },
      injector: TestBed.inject(Injector),
    });
    return {
      key,
      calls,
      list,
      resolve: (page: Page<string>) => pending.shift()!.resolve(page),
      reject: (error: unknown) => pending.shift()!.reject(error),
    };
  }

  it('appends pages with "load more" and restarts when the params change', async () => {
    const { key, calls, list, resolve } = setup();
    await settle();
    expect(list.firstLoad()).toBe(true);
    resolve({ items: ['1', '2'], nextCursor: 'c2' });
    await settle();
    expect(list.items()).toEqual(['1', '2']);
    expect(list.hasMore()).toBe(true);

    list.loadMore();
    await settle();
    expect(list.loadingMore()).toBe(true);
    expect(list.items()).toEqual(['1', '2']);
    resolve({ items: ['3'], nextCursor: null });
    await settle();
    expect(list.items()).toEqual(['1', '2', '3']);
    expect(list.hasMore()).toBe(false);
    expect(calls).toEqual([{ key: 'a' }, { key: 'a', cursor: 'c2' }]);

    key.set('b');
    await settle();
    expect(list.items()).toEqual([]);
    expect(list.firstLoad()).toBe(true);
    resolve({ items: ['x'] });
    await settle();
    expect(list.items()).toEqual(['x']);
  });

  it('keeps the list visible while refreshing and when a later page fails', async () => {
    const { list, resolve, reject } = setup();
    await settle();
    resolve({ items: ['1'], nextCursor: 'c2' });
    await settle();

    list.loadMore();
    await settle();
    reject(new Error('boom'));
    await settle();
    expect(list.moreFailed()).toBe(true);
    expect(list.failed()).toBe(false);
    expect(list.items()).toEqual(['1']);

    list.refresh();
    await settle();
    expect(list.refreshing()).toBe(true);
    expect(list.items()).toEqual(['1']);
    resolve({ items: ['0', '1'] });
    await settle();
    expect(list.items()).toEqual(['0', '1']);
  });

  it('reports an empty result and supports local edits', async () => {
    const { list, resolve } = setup();
    await settle();
    resolve({ items: ['only'] });
    await settle();
    list.update((items) => items.filter((i) => i !== 'only'));
    expect(list.items()).toEqual([]);
    expect(list.empty()).toBe(true);
  });
});
