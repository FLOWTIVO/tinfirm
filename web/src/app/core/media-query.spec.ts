import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { mediaQuery, WIDE_SCREEN } from './media-query';

@Component({ template: '' })
class Host {
  readonly wide = mediaQuery(WIDE_SCREEN, true);
}

describe('mediaQuery', () => {
  const original = window.matchMedia;
  afterEach(() => {
    window.matchMedia = original;
  });

  it('follows the query while the component lives', () => {
    let listener: ((event: MediaQueryListEvent) => void) | null = null;
    const list = {
      matches: false,
      addEventListener: vi.fn(
        (_: string, l: (event: MediaQueryListEvent) => void) => (listener = l),
      ),
      removeEventListener: vi.fn(),
    };
    window.matchMedia = vi.fn().mockReturnValue(list) as unknown as typeof window.matchMedia;
    const fixture = TestBed.createComponent(Host);
    expect(window.matchMedia).toHaveBeenCalledWith('(min-width: 64rem)');
    expect(fixture.componentInstance.wide()).toBe(false);

    listener!({ matches: true } as MediaQueryListEvent);
    expect(fixture.componentInstance.wide()).toBe(true);

    fixture.destroy();
    expect(list.removeEventListener).toHaveBeenCalledWith('change', listener);
  });

  it('answers the fallback where the window cannot tell', () => {
    window.matchMedia = undefined as unknown as typeof window.matchMedia;
    expect(TestBed.createComponent(Host).componentInstance.wide()).toBe(true);
  });
});
