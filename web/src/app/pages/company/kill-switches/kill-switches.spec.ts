import { HttpTestingController } from '@angular/common/http/testing';
import { type ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import type { KillSwitch, KillSwitches, Role } from '../../../api/generated/models';
import {
  aPausedSystem,
  aRunsStop,
  aSuspendedMember,
  aSystem,
  IDS,
  noSwitches,
} from '../../../testing/fixtures';
import {
  fakeCurrentCompany,
  fakeNotify,
  problem,
  provideTestHttp,
  settle,
  typeInto,
} from '../../../testing/test-helpers';
import { words } from '../../../testing/words';
import { KillSwitchesPage } from './kill-switches';
import {
  nothingOn,
  pausableSystems,
  resumeTriggersLabel,
  switchGroups,
} from './kill-switches-view';

const SWITCHES = '/api/companies/c1/kill-switches';

describe('the kill switches, grouped', () => {
  it('groups the switches by what they hold', () => {
    const groups = switchGroups(
      noSwitches({
        switches: [
          aPausedSystem(),
          aSuspendedMember(),
          aSuspendedMember({ kind: 'SERVICE_IDENTITY_SUSPENDED', name: 'svc-reader' }),
          aSuspendedMember({ kind: 'AGENT_PAUSED', name: 'Analyst' }),
        ],
      }),
    );
    expect(groups.systems.map((s) => s.name)).toEqual(['GitHub']);
    expect(groups.principals.map((s) => s.name)).toEqual(['Dave', 'svc-reader']);
    expect(groups.others.map((s) => s.name)).toEqual(['Analyst']);
  });

  it.each<[string, KillSwitches, boolean]>([
    ['nothing', noSwitches(), true],
    ['a stop', noSwitches({ runsStop: aRunsStop() }), false],
    ['a paused system', noSwitches({ switches: [aPausedSystem()] }), false],
    ['one’s own suspension', noSwitches({ mySuspension: aSuspendedMember() }), false],
  ])('knows when nothing is on: %s', (_, switches, off) => {
    expect(nothingOn(switches)).toBe(off);
  });

  it('offers only the company’s systems not paused yet, by name', () => {
    const systems = pausableSystems([
      aSystem({ id: 's-jira', name: 'Jira' }),
      aSystem({ id: 's-mine', name: 'My notes', personal: true }),
      aSystem({ id: 's-gh', name: 'GitHub', paused: true }),
      aSystem({ id: 's-crm', name: 'CRM' }),
    ]);
    expect(systems.map((s) => s.name)).toEqual(['CRM', 'Jira']);
  });

  it.each<[number, string | null]>([
    [0, null],
    [1, 'Resume its trigger too'],
    [3, 'Resume its 3 triggers too'],
  ])('offers to resume %i triggers', (n, label) => {
    const triggers = Array.from({ length: n }, (_, i) => ({ id: `t${i}`, name: `T${i}` }));
    expect(resumeTriggersLabel(aPausedSystem({ pausedTriggers: triggers }))).toBe(label);
  });
});

describe('KillSwitchesPage', () => {
  let fixture: ComponentFixture<KillSwitchesPage>;
  let http: HttpTestingController;
  let el: HTMLElement;
  let notify: ReturnType<typeof fakeNotify>['notify'];

  async function open(role: Role | null, switches: KillSwitches, instanceAdmin = false) {
    const notifier = fakeNotify();
    notify = notifier.notify;
    TestBed.configureTestingModule({
      imports: [KillSwitchesPage],
      providers: [
        provideRouter([]),
        ...provideTestHttp(),
        fakeCurrentCompany(role, instanceAdmin).provider,
        notifier.provider,
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(KillSwitchesPage);
    el = fixture.nativeElement;
    await settle();
    for (const req of http.match(SWITCHES)) req.flush(switches);
    for (const req of http.match((r) => r.url === '/api/companies/c1/systems')) {
      req.flush({
        items: [
          aSystem({ id: IDS.jira, name: 'Jira' }),
          aSystem({ id: IDS.github, name: 'GitHub' }),
        ],
      });
    }
    await settle();
    await fixture.whenStable();
  }

  const q = (id: string) => el.querySelector<HTMLElement>(`[data-testid=${id}]`);
  const overlay = () => document.querySelector('.cdk-overlay-container') as HTMLElement;
  const inOverlay = (id: string) => overlay().querySelector<HTMLElement>(`[data-testid=${id}]`);

  afterEach(() => {
    http.verify();
    overlay()?.replaceChildren();
  });

  it.each<[string, KillSwitches, string[], string[]]>([
    [
      'nothing is on',
      noSwitches(),
      ['runs-going'],
      ['runs-stopped', 'my-suspension-card', 'stop-runs'],
    ],
    [
      'the runs are stopped',
      noSwitches({ runsStop: aRunsStop() }),
      ['runs-stopped'],
      ['lift-runs', 'runs-going'],
    ],
    [
      'the member is suspended',
      noSwitches({ mySuspension: aSuspendedMember() }),
      ['my-suspension-card', 'runs-going'],
      ['systems-switch'],
    ],
  ])('shows a member the stop and their own suspension only: %s', async (_, ks, shown, hidden) => {
    await open('MEMBER', ks);
    for (const id of shown) expect(q(id), id).not.toBeNull();
    for (const id of hidden) expect(q(id), id).toBeNull();
    expect(q('systems-switch')).toBeNull();
    expect(q('suspended-switch')).toBeNull();
  });

  it('says who stopped the runs, when and why', async () => {
    await open('MEMBER', noSwitches({ runsStop: aRunsStop() }));
    expect(words(q('runs-stopped'))).toContain('by Adam Admin: “A leaked key”.');
  });

  it('shows an admin every switch that is on, with who, why and its page', async () => {
    await open(
      'ADMIN',
      noSwitches({
        counts: [
          { kind: 'SYSTEM_PAUSED', count: 1 },
          { kind: 'MEMBER_SUSPENDED', count: 1 },
          { kind: 'AGENT_PAUSED', count: 1 },
        ],
        switches: [
          aPausedSystem(),
          aSuspendedMember(),
          aSuspendedMember({
            kind: 'AGENT_PAUSED',
            targetId: IDS.agent,
            name: 'Analyst',
            reason: null,
          }),
          aSuspendedMember({
            kind: 'SERVICE_IDENTITY_SUSPENDED',
            targetId: IDS.svcReader,
            name: 'svc-reader',
            cause: 'USER_DEACTIVATED',
          }),
        ],
      }),
    );
    expect(words(q('counts'))).toBe('1 paused system 1 suspended member 1 paused agent');
    const system = el.querySelector('[data-system=GitHub]');
    expect(words(system)).toContain('Paused');
    expect(words(system)).toContain('by Adam Admin: “The app was leaked”.');
    expect(words(system)).toContain('Its pause paused 1 trigger.');
    expect(system?.querySelector('a')?.getAttribute('href')).toBe(`/c/c1/systems/${IDS.github}`);
    expect(el.querySelector('[data-principal=Dave] a')?.getAttribute('href')).toBe('/c/c1/members');
    expect(el.querySelector('[data-principal=svc-reader] a')?.getAttribute('href')).toBe(
      `/c/c1/service-identities/${IDS.svcReader}`,
    );
    expect(words(el.querySelector('[data-principal=svc-reader]'))).toContain(
      'reactivating the user resumes them',
    );
    expect(words(q('other-switches'))).toContain('Analyst · paused agent');
  });

  it.each<[string, Partial<KillSwitch>, boolean]>([
    ['suspended by an admin', { userId: IDS.daveUser }, true],
    ['without a user named', { userId: null }, false],
    ['by the user’s deactivation', { userId: IDS.daveUser, cause: 'USER_DEACTIVATED' }, false],
  ])('offers to resume a member %s in place', async (_, change, offered) => {
    await open('ADMIN', noSwitches({ switches: [aSuspendedMember(change)] }));
    const resume = el.querySelector<HTMLButtonElement>('button[aria-label="Resume Dave"]');
    expect(!!resume).toBe(offered);
    if (!resume) return;
    resume.click();
    await settle();
    http
      .expectOne({ method: 'POST', url: `/api/companies/c1/members/${IDS.daveUser}/resume` })
      .flush({ userId: IDS.daveUser, principalId: IDS.dave, status: 'ACTIVE' });
    await settle();
    http.expectOne(SWITCHES).flush(noSwitches());
    await settle();
    await fixture.whenStable();
    expect(notify.success).toHaveBeenCalledWith(
      'Dave is resumed',
      'Their waiting turns go on; triggers they wrote stay paused until resumed.',
    );
    expect(q('suspended-switch')?.textContent).toContain('Nobody is suspended.');
  });

  it('stops the company’s runs with a reason', async () => {
    await open('OWNER', noSwitches());
    q('stop-runs')!.click();
    await settle();
    await fixture.whenStable();
    const confirm = inOverlay('stop-dialog-confirm') as HTMLButtonElement;
    expect(confirm.disabled).toBe(true);
    typeInto(overlay().querySelector('textarea'), ' A leaked key ');
    await settle();
    confirm.click();
    await settle();
    const req = http.expectOne({ method: 'POST', url: '/api/companies/c1/runs-stop' });
    expect(req.request.body).toEqual({ reason: 'A leaked key' });
    req.flush({ runsStop: aRunsStop(), runsCancelled: 2 });
    await settle();
    http.expectOne(SWITCHES).flush(noSwitches({ runsStop: aRunsStop() }));
    await settle();
    await fixture.whenStable();
    expect(notify.success).toHaveBeenCalledWith(
      'The company’s runs are stopped',
      '2 running turns cancelled.',
    );
    expect(q('runs-stopped')).not.toBeNull();
  });

  it('shows why the stop was refused', async () => {
    await open('ADMIN', noSwitches());
    q('stop-runs')!.click();
    await settle();
    await fixture.whenStable();
    typeInto(overlay().querySelector('textarea'), 'x');
    await settle();
    inOverlay('stop-dialog-confirm')!.click();
    await settle();
    http
      .expectOne('/api/companies/c1/runs-stop')
      .flush(problem(403), { status: 403, statusText: 'Forbidden' });
    await settle();
    await fixture.whenStable();
    expect(words(overlay())).toContain('Only owners and admins pull and undo these switches.');
  });

  it('lifts the stop and says what goes on again and what stays on', async () => {
    await open(
      'ADMIN',
      noSwitches({ runsStop: aRunsStop(), counts: [{ kind: 'SYSTEM_PAUSED', count: 1 }] }),
    );
    q('lift-runs')!.click();
    await settle();
    await fixture.whenStable();
    expect(words(inOverlay('lift-dialog'))).toContain('Still on: 1 paused system.');
    inOverlay('confirm-lift')!.click();
    await settle();
    http.expectOne({ method: 'DELETE', url: '/api/companies/c1/runs-stop' }).flush({
      triggersArmed: 3,
      heldRowsReleased: 1,
      stillOn: [{ kind: 'SYSTEM_PAUSED', count: 1 }],
    });
    await settle();
    http.expectOne(SWITCHES).flush(noSwitches({ counts: [{ kind: 'SYSTEM_PAUSED', count: 1 }] }));
    await settle();
    await fixture.whenStable();
    expect(words(q('lift-result'))).toBe(
      'The stop is lifted Waiting turns are dispatched. 3 triggers fire again from now on. 1 held write-back goes out. Still on: 1 paused system.',
    );
    expect(q('runs-going')).not.toBeNull();
  });

  it('pauses a system with a reason, cancelling its running turns when asked', async () => {
    await open('ADMIN', noSwitches());
    q('pause-system')!.click();
    await settle();
    await fixture.whenStable();
    const select = overlay().querySelector<HTMLSelectElement>('#pause-system-id')!;
    expect(Array.from(select.options).map((o) => o.text)).toEqual(['GitHub', 'Jira']);
    (inOverlay('pause-stop-running') as HTMLInputElement).click();
    typeInto(overlay().querySelector('textarea'), 'The app leaked');
    await settle();
    inOverlay('pause-dialog-confirm')!.click();
    await settle();
    const req = http.expectOne({
      method: 'POST',
      url: `/api/companies/c1/systems/${IDS.github}/pause`,
    });
    expect(req.request.body).toEqual({ reason: 'The app leaked', stopRunning: true });
    req.flush({
      systemId: IDS.github,
      pausedAt: '2026-10-12T09:00:00Z',
      reason: 'The app leaked',
      runsCancelled: 1,
      triggersPaused: 2,
    });
    await settle();
    http.expectOne(SWITCHES).flush(noSwitches({ switches: [aPausedSystem()] }));
    http.expectOne((r) => r.url === '/api/companies/c1/systems').flush({ items: [] });
    await settle();
    await fixture.whenStable();
    expect(notify.success).toHaveBeenCalledWith(
      'GitHub is paused',
      '1 turn cancelled, 2 triggers paused.',
    );
  });

  it.each<[string, boolean, boolean]>([
    ['with its triggers', true, true],
    ['without them', false, false],
  ])('resumes a system %s', async (_, keep, sent) => {
    await open('ADMIN', noSwitches({ switches: [aPausedSystem()] }));
    el.querySelector<HTMLButtonElement>('button[aria-label="Resume GitHub"]')!.click();
    await settle();
    await fixture.whenStable();
    const dialog = inOverlay('resume-dialog');
    expect(words(dialog)).toContain('Resume its trigger too');
    expect(words(dialog)).toContain('Review pull requests, by Alice');
    const box = inOverlay('resume-triggers') as HTMLInputElement;
    expect(box.checked).toBe(true);
    if (!keep) box.click();
    inOverlay('confirm-resume')!.click();
    await settle();
    const req = http.expectOne(`/api/companies/c1/systems/${IDS.github}/resume`);
    expect(req.request.body).toEqual({ resumeTriggers: sent });
    req.flush({
      systemId: IDS.github,
      triggersResumed: sent ? 1 : 0,
      triggersStillPaused: sent ? 0 : 1,
    });
    await settle();
    http.expectOne(SWITCHES).flush(noSwitches());
    http.expectOne((r) => r.url === '/api/companies/c1/systems').flush({ items: [] });
    await settle();
    await fixture.whenStable();
    expect(notify.success).toHaveBeenCalledWith(
      'GitHub is resumed',
      sent ? '1 trigger resumed.' : '1 trigger stays paused.',
    );
  });
});
