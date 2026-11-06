import { TestBed } from '@angular/core/testing';
import { toast } from '@spartan-ng/brain/sonner';
import { settle } from '../../../../testing/test-helpers';
import { HlmToaster } from './hlm-toaster';

describe('HlmToaster', () => {
  const original = window.matchMedia;
  beforeEach(() => {
    // brn-sonner-toaster follows the system's colour scheme through matchMedia, which jsdom has not.
    window.matchMedia = vi.fn((query: string) => ({
      matches: false,
      media: query,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      addListener: vi.fn(),
      removeListener: vi.fn(),
    })) as unknown as typeof window.matchMedia;
  });
  afterEach(() => {
    toast.dismiss();
    // The toaster listens to the media query until it is destroyed: destroy it first.
    TestBed.resetTestingModule();
    window.matchMedia = original;
  });

  it('shows each toast as an item of the toaster’s list (axe: a list holds only list items)', async () => {
    const fixture = TestBed.createComponent(HlmToaster);
    fixture.detectChanges();
    toast.success('Saved', { description: 'It applies at the next use.' });
    toast('Another');
    await settle(5);
    fixture.detectChanges();
    await settle();

    const list = (fixture.nativeElement as HTMLElement).querySelector('ol[data-sonner-toaster]');
    expect(list).not.toBeNull();
    expect(list!.getAttribute('role')).toBe('list');
    const children = Array.from(list!.children);
    expect(children.length).toBeGreaterThan(0);
    for (const child of children) {
      // brain renders the toast itself as the list's item (li[brnSonnerToast]), the live region
      // that announces it.
      expect(child.tagName.toLowerCase()).toBe('li');
      expect(child.hasAttribute('data-sonner-toast')).toBe(true);
      expect(child.getAttribute('aria-live')).toBe('polite');
    }
  });
});
