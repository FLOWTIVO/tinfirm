import { TestBed } from '@angular/core/testing';
import { Logo } from './logo';

describe('Logo', () => {
  function render(inputs: { wordmark?: boolean; quiet?: boolean } = {}) {
    const fixture = TestBed.createComponent(Logo);
    for (const [name, value] of Object.entries(inputs)) fixture.componentRef.setInput(name, value);
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    return { el, find: (id: string) => el.querySelector(`[data-testid=${id}]`) };
  }

  it('draws the Ti mark alone by default, hidden from assistive technology', () => {
    const { el, find } = render();
    expect(find('logo-mark')).not.toBeNull();
    expect(find('logo-wordmark')).toBeNull();
    for (const svg of Array.from(el.querySelectorAll('svg'))) {
      expect(svg.getAttribute('aria-hidden')).toBe('true');
    }
  });

  it('adds the wordmark', () => {
    const { find } = render({ wordmark: true });
    expect(find('logo-wordmark')).not.toBeNull();
  });

  it('dots the i in human orange', () => {
    const { find } = render({ wordmark: true });
    expect(find('logo-dot')!.classList).toContain('fill-human');
    expect(find('logo-wordmark-dot')!.classList).toContain('fill-human');
  });

  it('shows no orange when quiet', () => {
    const { el, find } = render({ wordmark: true, quiet: true });
    expect(el.querySelector('.fill-human')).toBeNull();
    expect(find('logo-dot')!.classList).toContain('fill-primary');
    expect(find('logo-wordmark-dot')!.classList).toContain('fill-foreground');
  });
});
