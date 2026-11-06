import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { provideRouter } from '@angular/router';
import { HlmToaster } from '@spartan-ng/helm/sonner';
import { App } from './app';

describe('App', () => {
  const original = window.matchMedia;
  afterEach(() => {
    // The toaster listens to the media queries until it is destroyed: destroy it first.
    TestBed.resetTestingModule();
    window.matchMedia = original;
  });

  /** Where the toasts go on a screen that is wide or not. */
  function toastPosition(wide: boolean) {
    window.matchMedia = vi.fn((query: string) => ({
      matches: query === '(min-width: 64rem)' ? wide : false,
      media: query,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      addListener: vi.fn(),
      removeListener: vi.fn(),
    })) as unknown as typeof window.matchMedia;
    TestBed.configureTestingModule({ imports: [App], providers: [provideRouter([])] });
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    return fixture.debugElement.query(By.directive(HlmToaster)).componentInstance.position();
  }

  it('shows toasts at the top of a narrow screen, clear of the composer at its bottom', () => {
    expect(toastPosition(false)).toBe('top-center');
  });

  it('shows toasts at the bottom right of a wide screen', () => {
    expect(toastPosition(true)).toBe('bottom-right');
  });
});
