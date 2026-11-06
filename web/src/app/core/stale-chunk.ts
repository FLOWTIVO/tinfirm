import { DOCUMENT, inject } from '@angular/core';
import type { NavigationError } from '@angular/router';

/** When this tab last reloaded for a missing chunk (sessionStorage), so it never reloads in a loop. */
export const STALE_CHUNK_RELOAD_KEY = 'tinfirm.staleChunkReloadAt';

const RELOAD_GUARD_MS = 30_000;

/** Chromium, Firefox and Safari word the failed import differently. */
const CHUNK_LOAD_ERROR =
  /dynamically imported module|Importing a module script failed|module script/i;

/**
 * True for the browser's error when a lazy chunk cannot be imported. After a deploy the chunks of the build
 * this tab runs are gone: the server answers 404 for them.
 */
export function isChunkLoadError(error: unknown): boolean {
  const message = error instanceof Error ? error.message : String(error ?? '');
  return CHUNK_LOAD_ERROR.test(message);
}

/**
 * Router navigation-error handler. A navigation that failed on a missing chunk loads its target as a full
 * page, which brings the current build. At most once per 30 s: when the server keeps failing, the error
 * stays visible instead of the page reloading over and over.
 */
export function reloadOnStaleChunk(error: NavigationError): void {
  if (!isChunkLoadError(error.error)) return;
  const now = Date.now();
  const last = Number(readReloadTime() ?? 0);
  if (now - last < RELOAD_GUARD_MS) return;
  // Without storage the guard cannot hold, so do not reload at all.
  if (!writeReloadTime(now)) return;
  inject(DOCUMENT).location.assign(error.url);
}

function readReloadTime(): string | null {
  try {
    return globalThis.sessionStorage?.getItem(STALE_CHUNK_RELOAD_KEY) ?? null;
  } catch {
    return null;
  }
}

function writeReloadTime(time: number): boolean {
  try {
    globalThis.sessionStorage.setItem(STALE_CHUNK_RELOAD_KEY, String(time));
    return true;
  } catch {
    return false;
  }
}
