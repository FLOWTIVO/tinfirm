import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { HlmAvatarImports } from '../index';

@Component({
  imports: [HlmAvatarImports],
  template: `
    <hlm-avatar>
      <img hlmAvatarImage src="avatar.png" alt="Ada" />
      <span hlmAvatarFallback>AL</span>
    </hlm-avatar>
  `,
})
class Host {}

describe('HlmAvatar', () => {
  function render() {
    const fixture = TestBed.createComponent(Host);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    return {
      fixture,
      avatar: el.querySelector('hlm-avatar') as HTMLElement,
      image: el.querySelector('img') as HTMLImageElement,
      fallback: el.querySelector('span') as HTMLElement,
    };
  }

  it('shows the fallback while the image has not loaded', () => {
    const { avatar, image, fallback } = render();
    expect(avatar.getAttribute('data-slot')).toBe('avatar');
    expect(avatar.getAttribute('data-size')).toBe('default');
    expect(image.style.visibility).toBe('hidden');
    expect(fallback.style.visibility).toBe('');
  });

  it('shows the image once it has loaded', () => {
    const { fixture, image, fallback } = render();
    image.dispatchEvent(new Event('load'));
    fixture.detectChanges();
    expect(image.style.visibility).toBe('');
    expect(fallback.style.visibility).toBe('hidden');
  });

  it('keeps the fallback when the image fails', () => {
    const { fixture, image, fallback } = render();
    image.dispatchEvent(new Event('error'));
    fixture.detectChanges();
    expect(image.style.visibility).toBe('hidden');
    expect(fallback.style.visibility).toBe('');
  });
});
