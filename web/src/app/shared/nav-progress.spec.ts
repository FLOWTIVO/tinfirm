import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { NAV_PROGRESS_DELAY_MS, NavProgress } from './nav-progress';

@Component({ template: '' })
class Page {}

describe('NavProgress', () => {
  afterEach(() => vi.useRealTimers());

  it('shows the bar only while a slow navigation is pending', async () => {
    vi.useFakeTimers();
    let loaded!: (page: typeof Page) => void;
    TestBed.configureTestingModule({
      providers: [
        provideRouter([
          { path: 'fast', component: Page },
          { path: 'slow', loadComponent: () => new Promise<typeof Page>((r) => (loaded = r)) },
        ]),
      ],
    });
    const fixture = TestBed.createComponent(NavProgress);
    const router = TestBed.inject(Router);
    const bar = () => fixture.nativeElement.querySelector('[role="progressbar"]');

    await router.navigateByUrl('/fast');
    await vi.advanceTimersByTimeAsync(NAV_PROGRESS_DELAY_MS * 2);
    await fixture.whenStable();
    expect(bar()).toBeNull();

    const navigation = router.navigateByUrl('/slow');
    await vi.advanceTimersByTimeAsync(NAV_PROGRESS_DELAY_MS - 50);
    TestBed.tick();
    expect(bar()).toBeNull();

    await vi.advanceTimersByTimeAsync(100);
    TestBed.tick();
    expect(bar()).not.toBeNull();

    loaded(Page);
    await navigation;
    await vi.advanceTimersByTimeAsync(0);
    TestBed.tick();
    expect(bar()).toBeNull();
  });
});
