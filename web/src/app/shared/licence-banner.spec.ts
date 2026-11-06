import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import type { Licence } from '../api/generated/models';
import { aCommunityLicence, aLicence } from '../testing/fixtures';
import { words } from '../testing/words';
import { LicenceBanner } from './licence-banner';

describe('LicenceBanner (ADR-0077 decision 7)', () => {
  function render(licence: Licence | null, instanceAdmin = false): HTMLElement {
    TestBed.configureTestingModule({ imports: [LicenceBanner], providers: [provideRouter([])] });
    const fixture = TestBed.createComponent(LicenceBanner);
    fixture.componentRef.setInput('licence', licence);
    fixture.componentRef.setInput('instanceAdmin', instanceAdmin);
    fixture.detectChanges();
    return fixture.nativeElement;
  }

  const banner = (el: HTMLElement) => el.querySelector('[data-testid=licence-banner]');

  it('says nothing while the licence is active, absent or not read yet', () => {
    expect(banner(render(aLicence()))).toBeNull();
    TestBed.resetTestingModule();
    expect(banner(render(aCommunityLicence()))).toBeNull();
    TestBed.resetTestingModule();
    expect(banner(render(null))).toBeNull();
  });

  it('counts the days left, and leads an instance admin to the licence', () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date(2026, 9, 20, 9));
    try {
      const el = render(aLicence({ state: 'EXPIRING', endsAt: '2026-11-03' }), true);
      expect(banner(el)?.getAttribute('data-state')).toBe('EXPIRING');
      expect(words(el.querySelector('[data-testid=licence-banner-title]'))).toBe(
        'The licence ends in 14 days',
      );
      expect(el.querySelector('a')?.getAttribute('href')).toBe('/admin/licence');
    } finally {
      vi.useRealTimers();
    }
  });

  it('says the day the grace ends, without a link for a company’s admin', () => {
    const el = render(
      aLicence({ state: 'GRACE', endsAt: '2026-10-10', graceEndsAt: '2026-11-09' }),
    );
    expect(words(el.querySelector('[data-testid=licence-banner-title]'))).toBe(
      'The licence has ended: everything keeps working until 9 November 2026',
    );
    expect(el.querySelector('a')).toBeNull();
  });

  it('says what stopped working and what stays once it ran out', () => {
    const el = render(
      aCommunityLicence({
        state: 'EXPIRED',
        endsAt: '2026-08-31',
        graceEndsAt: '2026-09-30',
        auditExportUntil: '2026-12-29',
      }),
    );
    expect(words(el.querySelector('[data-testid=licence-stopped]'))).toContain('The Outlook add-in');
    expect(words(el.querySelector('[data-testid=licence-kept]'))).toContain(
      'The audit’s export, until 29 December 2026',
    );
  });
});
