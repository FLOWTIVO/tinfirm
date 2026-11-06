import { computed, DOCUMENT, effect, inject, Injectable, signal } from '@angular/core';

export type Theme = 'light' | 'dark';

export const THEME_STORAGE_KEY = 'tinfirm.theme';

/** The stored theme, or null when the user never chose one (or storage is unavailable). */
export function readStoredTheme(): Theme | null {
  try {
    const value = globalThis.localStorage?.getItem(THEME_STORAGE_KEY);
    return value === 'light' || value === 'dark' ? value : null;
  } catch {
    return null;
  }
}

function writeStoredTheme(theme: Theme): void {
  try {
    globalThis.localStorage?.setItem(THEME_STORAGE_KEY, theme);
  } catch {
    // Storage can be disabled (private mode, policies). The choice then lasts for this tab only.
  }
}

function systemPrefersDark(): boolean {
  try {
    return globalThis.matchMedia?.('(prefers-color-scheme: dark)').matches ?? false;
  } catch {
    return false;
  }
}

/** Sets the `dark` class on <html>. Called before bootstrap too, to avoid a flash of light theme. */
export function applyTheme(dark: boolean, doc: Document = document): void {
  const root = doc.documentElement;
  root.classList.toggle('dark', dark);
  root.style.colorScheme = dark ? 'dark' : 'light';
}

export function applyInitialTheme(): void {
  const stored = readStoredTheme();
  applyTheme(stored ? stored === 'dark' : systemPrefersDark());
}

/**
 * Light/dark theme. Follows the OS setting until the user picks one with `toggle()`; the pick is
 * kept in localStorage.
 */
@Injectable({ providedIn: 'root' })
export class ThemeService {
  private readonly document = inject(DOCUMENT);
  private readonly systemDark = signal(systemPrefersDark());
  private readonly preference = signal<Theme | null>(readStoredTheme());

  readonly theme = computed<Theme>(
    () => this.preference() ?? (this.systemDark() ? 'dark' : 'light'),
  );
  readonly isDark = computed(() => this.theme() === 'dark');

  constructor() {
    effect(() => applyTheme(this.isDark(), this.document));

    try {
      const query = globalThis.matchMedia?.('(prefers-color-scheme: dark)');
      query?.addEventListener('change', (event) => this.systemDark.set(event.matches));
    } catch {
      // matchMedia is missing in some test environments.
    }
  }

  toggle(): void {
    this.set(this.isDark() ? 'light' : 'dark');
  }

  set(theme: Theme): void {
    this.preference.set(theme);
    writeStoredTheme(theme);
  }
}
