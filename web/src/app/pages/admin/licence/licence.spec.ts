import { HttpTestingController } from '@angular/common/http/testing';
import { type ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import type { AdminLicence, Licence } from '../../../api/generated/models';
import {
  aCommunityLicence,
  aLicence,
  aLicenceKey,
  anAdminLicence,
} from '../../../testing/fixtures';
import {
  fakeLicence,
  fakeNotify,
  problem,
  provideTestHttp,
  settle,
  submitForm,
  typeInto,
} from '../../../testing/test-helpers';
import { words } from '../../../testing/words';
import { enteredMessage, LicencePage } from './licence';

const URL = '/api/admin/licence';
const KEY = 'TINFIRM1.bGljZW5jZS0wMDQy.c2lnbmF0dXJl'; // gitleaks:allow (test fixture)

describe('LicencePage', () => {
  let fixture: ComponentFixture<LicencePage>;
  let http: HttpTestingController;
  let el: HTMLElement;
  let notify: ReturnType<typeof fakeNotify>['notify'];
  let licence: ReturnType<typeof fakeLicence>;

  async function open(current: Licence = aLicence(), keys: AdminLicence = anAdminLicence()) {
    const fake = fakeNotify();
    notify = fake.notify;
    licence = fakeLicence(current);
    TestBed.configureTestingModule({
      imports: [LicencePage],
      providers: [provideRouter([]), ...provideTestHttp(), fake.provider, licence.provider],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(LicencePage);
    el = fixture.nativeElement;
    await settle();
    http.expectOne({ method: 'GET', url: URL }).flush(keys);
    await settle();
    fixture.detectChanges();
  }

  const text = (id: string) => words(el.querySelector(`[data-testid=${id}]`));
  const input = () => el.querySelector<HTMLTextAreaElement>('[data-testid=licence-key-input]')!;

  async function paste(value: string) {
    typeInto(input(), value);
    submitForm(el.querySelector('#enter-key-form'));
    await settle();
  }

  afterEach(() => http.verify());

  it('shows the edition, state, customer, end and seats, and reads the licence again', async () => {
    await open();

    expect(text('licence-edition')).toBe('Pro');
    expect(text('licence-state')).toBe('Active');
    expect(text('licence-customer')).toBe('Acme s.r.o.');
    expect(text('licence-ends')).toBe('31 March 2027');
    expect(text('licence-seats')).toContain('12 of 20 seats used');
    expect(el.querySelector('[data-testid=licence-grace]')).toBeNull();
    expect(licence.fake.reload).toHaveBeenCalled();
  });

  it('says the grace’s end and the notice while the licence is in grace', async () => {
    await open(aLicence({ state: 'GRACE', endsAt: '2026-10-10', graceEndsAt: '2026-11-09' }));

    expect(text('licence-grace')).toContain('9 November 2026');
    expect(el.querySelector('[data-testid=licence-banner]')?.getAttribute('data-state')).toBe(
      'GRACE',
    );
    // On the licence page itself the banner does not link to it.
    expect(el.querySelector('[data-testid=licence-banner] a')).toBeNull();
  });

  it('shows the key in force and the next one, never the key itself, and no remove', async () => {
    await open(
      aLicence(),
      anAdminLicence({
        next: aLicenceKey({ rev: 2, seats: 25, startsAt: '2027-04-01', endsAt: '2028-03-31' }),
      }),
    );

    expect(text('current-key')).toContain('Pro · Acme s.r.o. In force');
    expect(text('current-key')).toContain('LIC-2026-0042, revision 1');
    expect(text('current-key')).toContain('1 April 2026 to 31 March 2027');
    expect(text('next-key')).toContain('Next, from 1 April 2027');
    expect(text('next-key')).toContain('25');
    expect(el.textContent).not.toContain('bGljZW5jZS0wMDQy');
    expect(el.querySelector('button[aria-label^=Remove]')).toBeNull();
  });

  it('says Community without a key', async () => {
    await open(aCommunityLicence(), { current: null, next: null });

    expect(text('licence-edition')).toBe('Community');
    expect(text('licence-state')).toBe('No licence');
    expect(el.querySelector('[data-testid=no-key]')).not.toBeNull();
    expect(el.querySelector('[data-testid=current-key]')).toBeNull();
  });

  it('enters a pasted key without the spaces a mail added, and shows the keys it answers', async () => {
    await open(aCommunityLicence(), { current: null, next: null });

    await paste(`  ${KEY.slice(0, 20)}\n${KEY.slice(20)}  \n`);
    const req = http.expectOne({ method: 'PUT', url: URL });
    expect(req.request.body).toEqual({ key: KEY });
    req.flush(anAdminLicence());
    await settle();
    fixture.detectChanges();

    expect(notify.success).toHaveBeenCalledWith(
      'Key entered',
      'Pro for Acme s.r.o., 20 seats, until 31 March 2027.',
    );
    expect(licence.fake.reload).toHaveBeenCalledTimes(2);
    expect(text('current-key')).toContain('LIC-2026-0042');
    expect(input().value).toBe('');
  });

  it('replaces the key in force with the next revision, with more seats', async () => {
    await open();

    await paste(KEY);
    http
      .expectOne({ method: 'PUT', url: URL })
      .flush(anAdminLicence({ current: aLicenceKey({ rev: 2, seats: 30 }) }));
    await settle();
    fixture.detectChanges();

    expect(notify.success).toHaveBeenCalledWith(
      'Key replaced',
      'Pro for Acme s.r.o., 30 seats, until 31 March 2027.',
    );
    expect(text('current-key')).toContain('revision 2');
  });

  it('enters nothing until it knows the key in force', async () => {
    licence = fakeLicence(aLicence());
    TestBed.configureTestingModule({
      imports: [LicencePage],
      providers: [provideRouter([]), ...provideTestHttp(), fakeNotify().provider, licence.provider],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(LicencePage);
    el = fixture.nativeElement;
    await settle();
    fixture.detectChanges();
    expect(el.querySelector<HTMLButtonElement>('[data-testid=enter-key]')!.disabled).toBe(true);

    http.expectOne({ method: 'GET', url: URL }).flush(anAdminLicence());
    await settle();
    fixture.detectChanges();
    expect(el.querySelector<HTMLButtonElement>('[data-testid=enter-key]')!.disabled).toBe(false);
    expect(text('current-key')).toContain('by Ivan Instance');
  });

  it('asks for the whole key before sending anything', async () => {
    await open();

    await paste('   ');
    fixture.detectChanges();
    expect(text('enter-key-error')).toBe('Paste the licence key first.');

    await paste('Dear customer, here is your key');
    fixture.detectChanges();
    expect(text('enter-key-error')).toContain('A licence key starts with TINFIRM1.');
    http.expectNone({ method: 'PUT', url: URL });
  });

  it.each([
    [400, 'LICENCE_KEY_INVALID', undefined, 'This is not a valid licence key'],
    [409, 'LICENCE_KEY_EXPIRED', undefined, 'This key has already ended.'],
    [
      409,
      'LICENCE_KEY_SUPERSEDED',
      undefined,
      'This key is the one in force, or an older revision of its licence.',
    ],
    [
      409,
      'LICENCE_SEATS_TOO_FEW',
      { required: 14, licensed: 10 },
      '14 people hold a seat, and the key licenses 10 seats. Make some Members Viewers first',
    ],
  ])('says %s %s in plain words', async (status, code, details, sentence) => {
    await open();

    await paste(KEY);
    http
      .expectOne({ method: 'PUT', url: URL })
      .flush({ ...problem(status, code), details }, { status, statusText: 'Refused' });
    await settle();
    fixture.detectChanges();

    expect(text('enter-key-error')).toContain(sentence);
    // The key stays, to be corrected or sent again.
    expect(input().value).toBe(KEY);
  });
});

describe('what entering a key did', () => {
  const v1 = aLicenceKey();

  it('names a first key, a replaced key and a next key', () => {
    expect(enteredMessage(null, { current: v1, next: null })[0]).toBe('Key entered');
    expect(enteredMessage(v1, { current: aLicenceKey({ rev: 2 }), next: null })[0]).toBe(
      'Key replaced',
    );
    expect(
      enteredMessage(v1, {
        current: v1,
        next: aLicenceKey({ rev: 2, startsAt: '2027-04-01', endsAt: '2028-03-31' }),
      }),
    ).toEqual(['Next key entered', 'Pro for Acme s.r.o. takes over on 1 April 2027.']);
    expect(
      enteredMessage(null, { current: null, next: aLicenceKey({ startsAt: '2027-04-01' }) })[0],
    ).toBe('Next key entered');
  });
});
