import { HttpTestingController } from '@angular/common/http/testing';
import { type ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import type { Departure } from '../../../api/generated/models';
import { aDeparture, anAgent, aMember, IDS } from '../../../testing/fixtures';
import {
  fakeCurrentCompany,
  fakeNotify,
  problem,
  provideTestHttp,
  settle,
} from '../../../testing/test-helpers';
import { words } from '../../../testing/words';
import { DeparturePage } from './departure-page';

const BASE = '/api/companies/c1';
const DEPARTURE = `${BASE}/principals/${IDS.dave}/departure`;

describe('DeparturePage', () => {
  let fixture: ComponentFixture<DeparturePage>;
  let http: HttpTestingController;
  let el: HTMLElement;
  let notify: ReturnType<typeof fakeNotify>['notify'];

  async function open(departure: Departure | null) {
    const notifier = fakeNotify();
    notify = notifier.notify;
    TestBed.configureTestingModule({
      imports: [DeparturePage],
      providers: [
        provideRouter([]),
        ...provideTestHttp(),
        fakeCurrentCompany('ADMIN').provider,
        notifier.provider,
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(DeparturePage);
    fixture.componentRef.setInput('principalId', IDS.dave);
    el = fixture.nativeElement;
    await settle();
    const req = http.expectOne(DEPARTURE);
    if (departure) req.flush(departure);
    else req.flush(problem(404), { status: 404, statusText: 'Not Found' });
    http
      .expectOne((r) => r.url === `${BASE}/members`)
      .flush({
        items: [
          aMember({ userId: IDS.bobUser, principalId: IDS.bob, displayName: 'Bob' }),
          aMember({
            userId: IDS.carolUser,
            principalId: IDS.carol,
            displayName: 'Carol',
            suspended: true,
          }),
        ],
      });
    await settle();
    await fixture.whenStable();
  }

  const q = (id: string) => el.querySelector<HTMLElement>(`[data-testid=${id}]`);
  const overlay = () => document.querySelector('.cdk-overlay-container') as HTMLElement;

  afterEach(() => {
    http.verify();
    overlay()?.replaceChildren();
  });

  it('lists every item of the checklist, the open ones first', async () => {
    await open(aDeparture());
    expect(el.querySelector('h1')?.textContent).toBe('Dave left');
    expect(words(q('departure-open'))).toBe('2 items need someone before the checklist closes.');
    expect(words(q('departure-triggers'))).toContain('Triage new issues · 2 conversations waiting');
    expect(words(q('departure-groups'))).toContain('None.');
    // A credential they connected is stored again, or signed in again, on its system's page
    // (ADR-0072): its kind says which.
    expect(words(q('departure-credential'))).toBe('svc-reader on Jira · store a new token');
    expect(q('departure-accounts')!.querySelector('a')?.getAttribute('href')).toBe(
      `/c/c1/systems/${IDS.jira}#credential-acc-svc-reader-jira`,
    );
    expect(words(q('departure-changes'))).toContain('https://github.test/acme/shop/pull/7 open');
    expect(words(q('departure-conversations'))).toContain('4 conversations of theirs ended.');
    expect(q('departure-conversations')!.querySelector('a')?.getAttribute('href')).toBe(
      `/c/c1/retention?erase=${IDS.dave}&name=Dave`,
    );
    // A suspended member owns nothing new.
    const options = Array.from(
      el.querySelectorAll<HTMLOptionElement>('#owner-a-triager option'),
      (o) => o.text,
    );
    expect(options).toEqual(['Keep with the admins', 'Bob']);
  });

  it.each<[string, Partial<Departure>, string]>([
    ['ready to close', { open: false, triggers: [], connectedCredentials: [] }, 'departure-ready'],
    [
      'closed',
      { open: false, triggers: [], connectedCredentials: [], closedAt: '2026-10-12T10:00:00Z' },
      'departure-closed',
    ],
  ])('says when it is %s', async (_, change, testId) => {
    await open(aDeparture(change));
    expect(q(testId)).not.toBeNull();
    expect(q('close-departure') === null).toBe(testId === 'departure-closed');
  });

  it('says a sign-in they connected is signed in again', async () => {
    await open(
      aDeparture({
        connectedCredentials: [
          {
            credentialId: IDS.readerGithubAccount,
            systemId: IDS.github,
            systemName: 'GitHub',
            credentialName: 'Release bot',
            kind: 'OAUTH',
          },
        ],
      }),
    );
    expect(words(q('departure-credential'))).toBe('Release bot on GitHub · sign it in again');
  });

  it('says when no member who left has this checklist', async () => {
    await open(null);
    expect(words(el)).toContain('No member who left has this checklist.');
  });

  it('gives an agent a new owner, through the member’s principal', async () => {
    await open(aDeparture());
    const select = el.querySelector<HTMLSelectElement>('#owner-a-triager')!;
    select.value = IDS.bobUser;
    select.dispatchEvent(new Event('change'));
    await settle();
    el.querySelector<HTMLElement>('[data-agent=Triager] button')!.click();
    await settle();
    const req = http.expectOne({ method: 'PUT', url: `${BASE}/agents/a-triager/owner` });
    expect(req.request.body).toEqual({ principalId: IDS.bob });
    req.flush(anAgent({ id: 'a-triager', name: 'Triager' }));
    await settle();
    http.expectOne(DEPARTURE).flush(aDeparture({ agents: [] }));
    await settle();
    await fixture.whenStable();
    expect(notify.success).toHaveBeenCalledWith('Bob owns Triager now');
    expect(words(q('departure-agents'))).toContain('None.');
  });

  it('closes it, or with force once DEPARTURE_OPEN says items are open', async () => {
    await open(aDeparture());
    q('close-departure')!.click();
    await settle();
    const first = http.expectOne({ method: 'POST', url: `${DEPARTURE}/close` });
    expect(first.request.body).toEqual({ force: false });
    first.flush(problem(409, 'DEPARTURE_OPEN'), { status: 409, statusText: 'Conflict' });
    await settle();
    http.expectOne(DEPARTURE).flush(aDeparture());
    await settle();
    await fixture.whenStable();
    expect(words(overlay().querySelector('[data-testid=force-dialog]'))).toContain(
      '2 items still need someone',
    );

    overlay().querySelector<HTMLElement>('[data-testid=confirm-force]')!.click();
    await settle();
    const forced = http.expectOne({ method: 'POST', url: `${DEPARTURE}/close` });
    expect(forced.request.body).toEqual({ force: true });
    forced.flush(aDeparture({ closedAt: '2026-10-12T10:00:00Z' }));
    await settle();
    await fixture.whenStable();
    expect(q('departure-closed')).not.toBeNull();
    expect(notify.success).toHaveBeenCalledWith('The checklist is closed');
  });
});
