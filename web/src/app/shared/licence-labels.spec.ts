import { aCommunityLicence, aLicence } from '../testing/fixtures';
import {
  cleanKey,
  daysUntil,
  editionRequiredMessage,
  licenceDay,
  licenceKeyMessage,
  licenceNotice,
  seatsExhaustedMessage,
  seatsFull,
  seatsLine,
} from './licence-labels';

describe('the licence’s words (ADR-0077)', () => {
  it('says a licence’s day the same everywhere', () => {
    expect(licenceDay('2027-03-31')).toBe('31 March 2027');
    expect(licenceDay('2027-03-31T00:00:00Z')).toBe('31 March 2027');
    expect(licenceDay(null)).toBe('');
  });

  it('counts the days left from the reader’s day', () => {
    const now = new Date(2026, 9, 20, 23, 30);
    expect(daysUntil('2026-10-20', now)).toBe(0);
    expect(daysUntil('2026-10-21', now)).toBe(1);
    expect(daysUntil('2026-11-19', now)).toBe(30);
    expect(daysUntil('2026-10-10', now)).toBe(-10);
  });

  it.each([
    [{ licensed: 20, used: 12 }, '12 of 20 seats used', false],
    [{ licensed: 20, used: 20 }, '20 of 20 seats used', true],
    [{ licensed: 5, used: 1 }, '1 of 5 seats used', false],
    [{ licensed: null, used: 1 }, '1 seat used', false],
    [{ licensed: null, used: 140 }, '140 seats used', false],
    // The server leaves a null out of its JSON.
    [{ used: 3 }, '3 seats used', false],
  ])('says the seats %o', (seats, line, full) => {
    expect(seatsLine(seats)).toBe(line);
    expect(seatsFull(seats)).toBe(full);
  });

  it('words SEATS_EXHAUSTED from its details, or without them', () => {
    expect(seatsExhaustedMessage(20, 20)).toBe(
      'All 20 seats of the licence are taken. Owners, Admins and Members each hold a seat; a Viewer holds none. Make someone a Viewer, or enter a key with more seats, first.',
    );
    expect(seatsExhaustedMessage(20, 22)).toContain(
      'All 20 seats of the licence are taken (22 in use).',
    );
    expect(seatsExhaustedMessage(undefined, undefined)).toMatch(
      /^Every seat of the licence is taken\./,
    );
  });

  it.each([
    ['LEGAL_HOLDS', 'ENTERPRISE', 'Legal holds need an Enterprise licence.'],
    ['OUTLOOK_ADDIN', undefined, 'The Outlook add-in needs a Pro licence.'],
    [
      'AUDIT_TOOLS',
      'PRO',
      'The audit’s filters, export and chain verification need a Pro licence.',
    ],
    [undefined, 'PRO', 'This feature needs a Pro licence.'],
    ['SOMETHING_NEW', undefined, 'This feature needs a paid licence.'],
    // An object's own names are no feature or edition.
    ['toString', 'constructor', 'This feature needs a paid licence.'],
  ])('words EDITION_REQUIRED for %s', (feature, edition, sentence) => {
    expect(editionRequiredMessage(feature, edition)).toBe(
      `${sentence} An instance admin enters a licence key under Licence.`,
    );
  });

  it('words every problem of a key', () => {
    for (const code of [
      'LICENCE_KEY_INVALID',
      'LICENCE_KEY_EXPIRED',
      'LICENCE_KEY_SUPERSEDED',
      'LICENCE_SEATS_TOO_FEW',
    ]) {
      expect(licenceKeyMessage(code, undefined), code).toBeTruthy();
    }
    expect(licenceKeyMessage('LICENCE_SEATS_TOO_FEW', { required: 14, licensed: 10 })).toBe(
      'This key licenses fewer seats than are in use. 14 people hold a seat, and the key licenses 10 seats. Make some Members Viewers first; the key in force works until its end meanwhile.',
    );
    expect(licenceKeyMessage('DUPLICATE_EMAIL', undefined)).toBeNull();
  });

  it('cleans a pasted key of the spaces and breaks a mail adds', () => {
    expect(cleanKey('  TINFIRM1.abc\n  def \r\n')).toBe('TINFIRM1.abcdef');
  });
});

describe('the notices of expiry (decision 7)', () => {
  const now = new Date(2026, 9, 20, 9);

  it('says nothing while the licence is active or absent', () => {
    expect(licenceNotice(aLicence(), now)).toBeNull();
    expect(licenceNotice(aCommunityLicence(), now)).toBeNull();
  });

  it('counts the days left before the end', () => {
    const notice = licenceNotice(aLicence({ state: 'EXPIRING', endsAt: '2026-11-03' }), now)!;
    expect(notice.title).toBe('The licence ends in 14 days');
    expect(notice.text).toContain('The Pro licence of Acme s.r.o. ends on 3 November 2026.');
    expect(licenceNotice(aLicence({ state: 'EXPIRING', endsAt: '2026-10-21' }), now)!.title).toBe(
      'The licence ends tomorrow',
    );
    expect(licenceNotice(aLicence({ state: 'EXPIRING', endsAt: '2026-10-20' }), now)!.title).toBe(
      'The licence ends today',
    );
  });

  it('says the day the grace ends', () => {
    const notice = licenceNotice(
      aLicence({ state: 'GRACE', endsAt: '2026-10-10', graceEndsAt: '2026-11-09' }),
      now,
    )!;
    expect(notice.title).toBe(
      'The licence has ended: everything keeps working until 9 November 2026',
    );
    expect(notice.text).toContain('The licence ended on 10 October 2026.');
  });

  it('says what stopped and what stays once it ran out', () => {
    const notice = licenceNotice(
      aCommunityLicence({
        state: 'EXPIRED',
        endsAt: '2026-08-31',
        graceEndsAt: '2026-09-30',
        auditExportUntil: '2026-12-29',
      }),
      now,
    )!;
    expect(notice.title).toBe('The licence has run out: the instance runs as Community');
    expect(notice.stopped).toContain('The Outlook add-in');
    expect(notice.kept).toContain('The audit’s export, until 29 December 2026');
    expect(notice.kept).toContain('Everyone’s role; no seat is checked');

    // Once its own 90 days are over, the export has stopped too.
    const later = licenceNotice(
      aCommunityLicence({ state: 'EXPIRED', endsAt: '2026-01-31', auditExportUntil: '2026-05-31' }),
      now,
    )!;
    expect(later.kept.join(' ')).not.toContain('export');
    expect(later.stopped).toContain('The audit’s export, since 31 May 2026');
  });
});
