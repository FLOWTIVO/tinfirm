import {
  computed,
  type Injector,
  linkedSignal,
  resource,
  type ResourceRef,
  type Signal,
  untracked,
  type WritableSignal,
} from '@angular/core';

/** The `{ items, nextCursor }` shape every list endpoint returns. */
export interface Page<T> {
  items: T[];
  nextCursor?: string | null;
}

type Mode = 'initial' | 'more' | 'refresh';

interface PageRequest<P> {
  readonly params: P;
  readonly cursor?: string;
  readonly mode: Mode;
}

interface Loaded<T> {
  readonly items: readonly T[];
  readonly nextCursor: string | null;
}

const EMPTY: Loaded<never> = { items: [], nextCursor: null };

export interface PagedResource<T> {
  /** Every item loaded so far, across "Load more" pages. */
  readonly items: Signal<readonly T[]>;
  readonly hasMore: Signal<boolean>;
  /** Loading the first page, with nothing to show yet. */
  readonly firstLoad: Signal<boolean>;
  readonly loadingMore: Signal<boolean>;
  /** Re-reading from the first page while the current items stay visible. */
  readonly refreshing: Signal<boolean>;
  /** The first page failed: show an error state instead of the list. */
  readonly failed: Signal<boolean>;
  /** A later page or a refresh failed: keep the list and show the error inline. */
  readonly moreFailed: Signal<boolean>;
  readonly error: Signal<unknown>;
  /** Loaded, and there is nothing. */
  readonly empty: Signal<boolean>;
  loadMore(): void;
  /** Re-reads from the first page (after a change), keeping current items until it arrives. */
  refresh(): void;
  /** Retries the last failed request. */
  retry(): void;
  /** Local edit of the loaded items, e.g. after a successful update. */
  update(fn: (items: readonly T[]) => readonly T[]): void;
}

/**
 * A `resource()` over a cursor-paginated endpoint with "Load more" semantics. The list restarts
 * whenever `params` changes (e.g. another company); `undefined` params leave it idle.
 */
export function pagedResource<T, P>(options: {
  params: () => P | undefined;
  load: (params: P, cursor: string | undefined) => Promise<Page<T>>;
  injector?: Injector;
}): PagedResource<T> {
  const request = linkedSignal<P | undefined, PageRequest<P> | undefined>({
    source: options.params,
    computation: (params) => (params === undefined ? undefined : { params, mode: 'initial' }),
  });

  const res: ResourceRef<Loaded<T> | undefined> = resource({
    params: () => request(),
    loader: async ({ params: req }): Promise<Loaded<T>> => {
      const page = await options.load(req.params, req.cursor);
      const before: readonly T[] = req.mode === 'more' ? untracked(state).items : [];
      return { items: [...before, ...page.items], nextCursor: page.nextCursor ?? null };
    },
    injector: options.injector,
  });

  const state: WritableSignal<Loaded<T>> = linkedSignal<
    { value: Loaded<T> | undefined; mode: Mode | undefined },
    Loaded<T>
  >({
    source: () => ({ value: res.hasValue() ? res.value() : undefined, mode: request()?.mode }),
    computation: (source, previous) => {
      if (source.value) return source.value;
      if (source.mode === undefined || source.mode === 'initial' || !previous) return EMPTY;
      return previous.value;
    },
  });

  const mode = computed(() => request()?.mode);
  const hasError = computed(() => res.status() === 'error');

  return {
    items: computed(() => state().items),
    hasMore: computed(() => state().nextCursor !== null),
    firstLoad: computed(() => mode() === 'initial' && res.isLoading()),
    loadingMore: computed(() => mode() === 'more' && res.isLoading()),
    refreshing: computed(() => mode() === 'refresh' && res.isLoading()),
    failed: computed(() => hasError() && mode() === 'initial'),
    moreFailed: computed(() => hasError() && mode() !== 'initial'),
    error: computed(() => res.error()),
    empty: computed(
      () =>
        request() !== undefined && !res.isLoading() && !hasError() && state().items.length === 0,
    ),
    loadMore() {
      const req = request();
      const cursor = state().nextCursor;
      if (!req || !cursor || res.isLoading()) return;
      request.set({ params: req.params, cursor, mode: 'more' });
    },
    refresh() {
      const req = request();
      if (!req) return;
      request.set({
        params: req.params,
        mode: req.mode === 'initial' && hasError() ? 'initial' : 'refresh',
      });
    },
    retry() {
      res.reload();
    },
    update(fn) {
      state.update((s) => ({ ...s, items: fn(s.items) }));
    },
  };
}

/**
 * Equality for request-parameter objects (`{ companyId, projectId }`), so a `computed` that
 * rebuilds the same parameters does not make a resource load again.
 */
export function sameParams<T extends Record<string, unknown> | undefined>(a: T, b: T): boolean {
  if (a === b) return true;
  if (!a || !b) return false;
  const keys = Object.keys(a);
  return keys.length === Object.keys(b).length && keys.every((k) => a[k] === b[k]);
}
