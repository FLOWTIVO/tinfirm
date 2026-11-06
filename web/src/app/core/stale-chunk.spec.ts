import { DOCUMENT, Injector, runInInjectionContext } from '@angular/core';
import { NavigationError } from '@angular/router';
import { isChunkLoadError, reloadOnStaleChunk, STALE_CHUNK_RELOAD_KEY } from './stale-chunk';

describe('reloadOnStaleChunk', () => {
  const chunkError = new TypeError(
    'Failed to fetch dynamically imported module: https://tinfirm.test/chunk-GONE0001.js',
  );

  function handle(error: unknown): ReturnType<typeof vi.fn> {
    const assign = vi.fn();
    const injector = Injector.create({
      providers: [{ provide: DOCUMENT, useValue: { location: { assign } } }],
    });
    runInInjectionContext(injector, () =>
      reloadOnStaleChunk(new NavigationError(1, '/c/acme/tasks', error)),
    );
    return assign;
  }

  afterEach(() => {
    sessionStorage.clear();
    vi.restoreAllMocks();
  });

  it('recognises the chunk errors of each browser', () => {
    expect(isChunkLoadError(chunkError)).toBe(true);
    expect(isChunkLoadError(new TypeError('error loading dynamically imported module'))).toBe(true);
    expect(isChunkLoadError(new TypeError('Importing a module script failed.'))).toBe(true);
    expect(isChunkLoadError(new Error('Http failure response: 500'))).toBe(false);
    expect(isChunkLoadError(undefined)).toBe(false);
  });

  it('loads the target as a full page once, then not again for 30 s', () => {
    expect(handle(chunkError)).toHaveBeenCalledWith('/c/acme/tasks');
    expect(handle(chunkError)).not.toHaveBeenCalled();

    sessionStorage.setItem(STALE_CHUNK_RELOAD_KEY, String(Date.now() - 31_000));
    expect(handle(chunkError)).toHaveBeenCalledOnce();
  });

  it('leaves other navigation errors alone', () => {
    expect(handle(new Error('guard failed'))).not.toHaveBeenCalled();
  });

  it('does not reload when storage cannot keep the guard', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('denied');
    });
    expect(handle(chunkError)).not.toHaveBeenCalled();
  });
});
