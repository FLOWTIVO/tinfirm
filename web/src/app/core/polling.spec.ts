import { TestBed } from '@angular/core/testing';
import { pollWhileVisible } from './polling';

function becomes(state: 'visible' | 'hidden'): void {
  Object.defineProperty(document, 'visibilityState', { value: state, configurable: true });
  document.dispatchEvent(new Event('visibilitychange'));
}

describe('pollWhileVisible', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    Object.defineProperty(document, 'visibilityState', { value: 'visible', configurable: true });
  });

  afterEach(() => {
    vi.useRealTimers();
    Reflect.deleteProperty(document, 'visibilityState');
  });

  it('ticks on the interval, and at once with `shown` when the page becomes visible again', () => {
    const tick = vi.fn();
    TestBed.runInInjectionContext(() => pollWhileVisible(tick, 1000));

    vi.advanceTimersByTime(1000);
    expect(tick).toHaveBeenCalledTimes(1);
    expect(tick).toHaveBeenLastCalledWith();

    becomes('hidden');
    vi.advanceTimersByTime(3000);
    expect(tick).toHaveBeenCalledTimes(1);

    becomes('visible');
    expect(tick).toHaveBeenCalledTimes(2);
    expect(tick).toHaveBeenLastCalledWith(true);
    vi.advanceTimersByTime(1000);
    expect(tick).toHaveBeenCalledTimes(3);
    expect(tick).toHaveBeenLastCalledWith();
  });
});
