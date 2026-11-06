import { DOCUMENT } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { THEME_STORAGE_KEY, ThemeService } from './theme';

describe('ThemeService', () => {
  afterEach(() => {
    localStorage.clear();
    document.documentElement.classList.remove('dark');
    vi.restoreAllMocks();
  });

  it('applies the stored theme and persists toggles', async () => {
    localStorage.setItem(THEME_STORAGE_KEY, 'dark');
    const theme = TestBed.inject(ThemeService);
    TestBed.tick();
    const html = TestBed.inject(DOCUMENT).documentElement;
    expect(theme.isDark()).toBe(true);
    expect(html.classList.contains('dark')).toBe(true);

    theme.toggle();
    TestBed.tick();
    expect(html.classList.contains('dark')).toBe(false);
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBe('light');
  });

  it('keeps working when storage throws', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('denied');
    });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('denied');
    });
    const theme = TestBed.inject(ThemeService);
    expect(() => theme.toggle()).not.toThrow();
    expect(theme.theme()).toBe('dark');
  });
});
