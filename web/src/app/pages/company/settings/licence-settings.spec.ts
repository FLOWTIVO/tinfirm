import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import type { Licence } from '../../../api/generated/models';
import { Session } from '../../../core/session';
import { aCommunityLicence, aLicence } from '../../../testing/fixtures';
import { currentUser, fakeLicence, provideTestHttp } from '../../../testing/test-helpers';
import { words } from '../../../testing/words';
import { LicenceSettings } from './licence-settings';

describe('LicenceSettings', () => {
  function render(licence: Licence, instanceAdmin = false): HTMLElement {
    TestBed.configureTestingModule({
      imports: [LicenceSettings],
      providers: [provideRouter([]), ...provideTestHttp(), fakeLicence(licence).provider],
    });
    TestBed.inject(Session).setUser(currentUser({ instanceAdmin }));
    const fixture = TestBed.createComponent(LicenceSettings);
    fixture.detectChanges();
    return fixture.nativeElement;
  }

  const text = (el: HTMLElement, id: string) => words(el.querySelector(`[data-testid=${id}]`));

  it('shows an Owner or Admin the edition, the seats and the end, read-only', () => {
    const el = render(aLicence());
    expect(text(el, 'licence-edition')).toBe('Pro');
    expect(text(el, 'licence-state')).toBe('Active');
    expect(text(el, 'licence-seats')).toBe('12 of 20 seats used');
    expect(text(el, 'licence-ends')).toBe('31 March 2027');
    expect(el.querySelector('input, textarea, button')).toBeNull();
    expect(el.textContent).toContain('An instance admin enters its key.');
    expect(el.querySelector('a[href="/admin/licence"]')).toBeNull();
  });

  it('leads an instance admin to the instance’s licence page', () => {
    const el = render(aLicence(), true);
    expect(el.querySelector('a[href="/admin/licence"]')).not.toBeNull();
  });

  it('says Community without an end', () => {
    const el = render(aCommunityLicence());
    expect(text(el, 'licence-edition')).toBe('Community');
    expect(text(el, 'licence-seats')).toBe('12 seats used');
    expect(el.querySelector('[data-testid=licence-ends]')).toBeNull();
  });
});
