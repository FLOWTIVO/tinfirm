import { HttpTestingController } from '@angular/common/http/testing';
import { type ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import type { Licence, Role, RunnerHost } from '../../../api/generated/models';
import { browserTimeZone } from '../../../shared/cron';
import { poolNow } from '../../../shared/runner-hosts/pool-view';
import {
  aCommunityLicence,
  aPersonalRunnerHost,
  aPoolRunnerHost,
  aRunnerHost,
  AT,
  IDS,
} from '../../../testing/fixtures';
import {
  fakeCurrentCompany,
  fakeLicence,
  fakeNotify,
  problem,
  provideTestHttp,
  settle,
  submitForm,
  typeInto,
} from '../../../testing/test-helpers';
import { words } from '../../../testing/words';
import { RunnerHosts } from './runner-hosts';

const HOSTS = '/api/companies/c1/runner-hosts';
const TOKEN = 'tfr_0123456789abcdefghijklmnopqrstuvwxyzABCDEFG';

describe('RunnerHosts', () => {
  let fixture: ComponentFixture<RunnerHosts>;
  let http: HttpTestingController;
  let el: HTMLElement;
  let notify: ReturnType<typeof fakeNotify>['notify'];

  async function open(
    items: RunnerHost[],
    role: Role | null = 'ADMIN',
    instanceAdmin = false,
    licence: Licence | null = null,
  ) {
    const notifier = fakeNotify();
    notify = notifier.notify;
    TestBed.configureTestingModule({
      imports: [RunnerHosts],
      providers: [
        provideRouter([]),
        ...provideTestHttp(),
        fakeCurrentCompany(role, instanceAdmin).provider,
        fakeLicence(licence).provider,
        notifier.provider,
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(RunnerHosts);
    el = fixture.nativeElement;
    await settle();
    http.expectOne((r) => r.url === HOSTS && r.method === 'GET').flush({ items });
    await settle();
    await fixture.whenStable();
  }

  const q = (testId: string, root: ParentNode = el) =>
    root.querySelector<HTMLElement>(`[data-testid=${testId}]`);
  const hosts = () => Array.from(el.querySelectorAll<HTMLElement>('[data-testid=runner-host]'));
  const host = (name: string) => el.querySelector<HTMLElement>(`[data-host="${name}"]`)!;
  const overlay = () => document.querySelector('.cdk-overlay-container') as HTMLElement;

  afterEach(() => {
    http.verify();
    overlay()?.replaceChildren();
  });

  it('lists the company’s hosts only, and points to the reader’s own on Accounts', async () => {
    await open([aRunnerHost(), aPersonalRunnerHost()]);
    expect(hosts()).toHaveLength(1);
    expect(hosts()[0].id).toBe(`host-${IDS.companyHost}`);
    expect(words(q('host-state', host('office')))).toBe('Online');
    expect(words(q('host-slots', host('office')))).toBe('1 of 2 slots in use');
    expect(words(q('own-hosts'))).toBe(
      'Your own runner hosts, which run only your turns, are under Accounts › Runner hosts.',
    );
    expect(q('none-takes-turns')).toBeNull();
  });

  it('leaves the pointer out for an instance admin who is no member', async () => {
    await open([aRunnerHost()], null, true);
    expect(q('own-hosts')).toBeNull();
    expect(q('host-remove')).not.toBeNull();
  });

  it.each<[string, Partial<RunnerHost>, string, boolean]>([
    ['online', {}, 'Online', true],
    ['offline', { online: false, lastSeenAt: AT.earlier }, 'Offline · last seen', false],
    [
      'never connected',
      { online: false, lastSeenAt: null, version: null },
      'Offline · never connected',
      false,
    ],
    // Its runner version decides nothing, low or high (ADR-0109).
    ['online with runner 0.0.1', { version: '0.0.1' }, 'Online', true],
    ['online with runner 9.9.9', { version: '9.9.9' }, 'Online', true],
  ])('reads a host that is %s', async (_, overrides, state, takes) => {
    await open([aRunnerHost(overrides)]);
    expect(words(q('host-state', host('office')))).toContain(state);
    expect(q('none-takes-turns') === null).toBe(takes);
  });

  it.each<[string, Partial<RunnerHost>]>([
    ['offline', { online: false, lastSeenAt: AT.earlier }],
    ['draining', { draining: true }],
  ])('warns when no host takes turns: the only one is %s', async (_, overrides) => {
    await open([aRunnerHost(overrides)]);
    expect(words(q('none-takes-turns'))).toBe(
      'No company host takes turns now Turns paid by a budget wait until a company host is online and not draining.',
    );
  });

  it('says what a company host is for when there is none', async () => {
    await open([aPersonalRunnerHost()]);
    expect(words(q('hosts-empty'))).toContain('No company runner host yet');
    expect(hosts()).toHaveLength(0);
  });

  it('registers a company host and shows its token once, with the commands of a company host', async () => {
    await open([]);
    q('open-register-host')!.click();
    await settle();
    await fixture.whenStable();
    expect(words(document.querySelector('hlm-dialog-content'))).toContain(
      'Register a company runner host',
    );
    typeInto(document.querySelector('#runner-host-name'), 'build-02');
    submitForm(document.querySelector('#register-runner-host-form'));
    await settle();
    const req = http.expectOne({ method: 'POST', url: HOSTS });
    expect(req.request.body).toEqual({ name: 'build-02', scope: 'COMPANY' });
    req.flush(
      {
        runnerHost: aRunnerHost({ id: 'h-new', name: 'build-02', online: false, lastSeenAt: null }),
        token: TOKEN,
        connectUrl: 'wss://tinfirm.acme.test/api/runner/v1/connect',
      },
      { status: 201, statusText: 'Created' },
    );
    await settle();
    http
      .expectOne((r) => r.url === HOSTS && r.method === 'GET')
      .flush({
        items: [aRunnerHost({ id: 'h-new', name: 'build-02', online: false, lastSeenAt: null })],
      });
    await settle();
    await fixture.whenStable();
    expect(document.querySelector('[data-testid=runner-token]')?.textContent).toBe(TOKEN);
    const commands = Array.from(document.querySelectorAll('ol code')).map((c) => c.textContent);
    expect(commands[0]).toBe(
      `tinfirm-runner-host login --server https://tinfirm.acme.test --token ${TOKEN}`,
    );
    // A company host holds no subscription.
    expect(commands.some((c) => c?.includes('credential add'))).toBe(false);
    expect(notify.success).toHaveBeenCalledWith(
      'build-02 registered',
      'It shows online once it connects.',
    );
    expect(words(host('build-02'))).toContain('Offline · never connected');
  });

  it('sets the runs at a time, and says the host has not confirmed it yet', async () => {
    await open([aRunnerHost({ online: false, lastSeenAt: AT.earlier })]);
    const select = el.querySelector<HTMLSelectElement>(`#host-capacity-${IDS.companyHost}`)!;
    select.value = '3';
    select.dispatchEvent(new Event('change'));
    await settle();
    const req = http.expectOne({ method: 'PATCH', url: `${HOSTS}/${IDS.companyHost}` });
    expect(req.request.body).toEqual({ desiredMaxConcurrentRuns: 3 });
    req.flush(
      aRunnerHost({
        online: false,
        lastSeenAt: AT.earlier,
        desiredMaxConcurrentRuns: 3,
        configPending: true,
      }),
    );
    await settle();
    await fixture.whenStable();
    expect(words(q('host-pending', host('office')))).toBe(
      'office applies the change when it connects.',
    );
  });

  it.each<[string, number, string | undefined, string]>([
    [
      'a host that runs turns',
      409,
      'RUNNER_HOST_HAS_ACTIVE_RUNS',
      'Runs are queued for or running on this runner host. Cancel them or wait for them to finish, then try again.',
    ],
    ['without the right', 403, undefined, 'Only owners and admins remove company runner hosts.'],
  ])('refuses to remove %s, in words', async (_, status, code, message) => {
    await open([aRunnerHost()]);
    q('host-remove', host('office'))!.click();
    await settle();
    await fixture.whenStable();
    (document.querySelector('[data-testid=confirm-remove-host]') as HTMLButtonElement).click();
    await settle();
    http
      .expectOne({ method: 'DELETE', url: `${HOSTS}/${IDS.companyHost}` })
      .flush(problem(status, code), { status, statusText: 'Refused' });
    await settle();
    await fixture.whenStable();
    expect(words(document.querySelector('[data-testid=remove-host-error]'))).toBe(message);
    expect(hosts()).toHaveLength(1);
  });

  it('removes a host', async () => {
    await open([aRunnerHost()]);
    q('host-remove', host('office'))!.click();
    await settle();
    await fixture.whenStable();
    (document.querySelector('[data-testid=confirm-remove-host]') as HTMLButtonElement).click();
    await settle();
    http
      .expectOne({ method: 'DELETE', url: `${HOSTS}/${IDS.companyHost}` })
      .flush(null, { status: 204, statusText: 'No Content' });
    await settle();
    await fixture.whenStable();
    expect(hosts()).toHaveLength(0);
    expect(notify.success).toHaveBeenCalledWith('office removed', 'Its token no longer works.');
  });

  describe('ready sandboxes (ADR-0099)', () => {
    const dialog = () => document.querySelector<HTMLElement>('hlm-dialog-content');
    const inDialog = (testId: string) =>
      document.querySelector<HTMLElement>(`hlm-dialog-content [data-testid=${testId}]`);

    async function openSchedule(name = 'droplets') {
      q('pool-edit', host(name))!.click();
      await settle();
      await fixture.whenStable();
    }

    async function save() {
      submitForm(document.querySelector('#pool-schedule-form'));
      await settle();
      await fixture.whenStable();
    }

    it('shows the count now and next, the week, and the host’s report', async () => {
      const pooled = aPoolRunnerHost();
      await open([pooled, aRunnerHost({ id: 'h-docker', name: 'docker-01' })]);
      const section = q('host-pool', host('droplets'))!;
      expect(words(section)).toContain('Ready sandboxes');
      expect(words(q('pool-now', section))).toBe(poolNow(pooled.pool!, browserTimeZone()));
      expect(words(q('pool-status', section))).toMatch(/^0 ready · 1 starting · 2 busy · reported/);
      expect(words(q('pool-served', section))).toBe(
        'Last 24 hours: 7 runs took a ready sandbox, 2 started cold. Cold starts take up to 1 min 35 s (9 in 10).',
      );
      expect(q('pool-week', section)).not.toBeNull();
      expect(q('pool-ceiling', section)).toBeNull();
      expect(words(q('pool-edit', section))).toBe('Edit schedule');
      // A host with Docker keeps no ready sandboxes.
      expect(q('host-pool', host('docker-01'))).toBeNull();
    });

    it('says the schedule needs Pro when the licence does not hold elastic runners', async () => {
      await open([aPoolRunnerHost()], 'ADMIN', false, aCommunityLicence());
      const section = q('host-pool', host('droplets'))!;
      expect(q('pool-edit', section)).toBeNull();
      expect(words(q('edition-badge-ELASTIC_RUNNERS', section))).toContain('Pro');
      expect(q('pool-now', section)).not.toBeNull();
    });

    it('notes a count above the operator’s limit', async () => {
      const pooled = aPoolRunnerHost();
      await open([
        aPoolRunnerHost({
          pool: {
            ...pooled.pool!,
            settings: { ...pooled.pool!.settings!, readyDefault: 6 },
          },
        }),
      ]);
      expect(words(q('pool-ceiling', host('droplets')))).toBe(
        'Applies up to 4 on this host, its operator’s limit (--pool-max-ready).',
      );
    });

    it('sets up a schedule from the working days preset', async () => {
      const pooled = aPoolRunnerHost();
      const fresh = { ...pooled.pool!, settings: null, next: null, readyTarget: 0, status: null };
      await open([aPoolRunnerHost({ pool: fresh })]);
      expect(words(q('pool-now', host('droplets')))).toBe(
        'No sandbox waits ready: each run starts its own.',
      );
      expect(words(q('pool-edit', host('droplets')))).toBe('Set up');
      await openSchedule();
      expect(words(dialog())).toContain('Ready sandboxes on droplets');
      expect(document.querySelectorAll('[data-testid=pool-window]')).toHaveLength(1);
      expect(inDialog('pool-clear')).toBeNull();
      expect(document.querySelector<HTMLInputElement>('#pool-zone')!.value).toBe(browserTimeZone());

      typeInto(document.querySelector('#pool-zone'), 'Europe/Prague');
      typeInto(document.querySelector('#pool-default'), '1');
      // Saturday too, for a night window that ends on Sunday.
      inDialog('pool-add-window')!.click();
      await settle();
      const night = document.querySelectorAll<HTMLElement>('[data-testid=pool-window]')[1];
      night.querySelector<HTMLButtonElement>('[data-day=SAT]')!.click();
      typeInto(night.querySelector('#pool-from-1'), '22:00');
      typeInto(night.querySelector('#pool-to-1'), '06:00');
      await settle();
      await fixture.whenStable();
      expect(words(inDialog('pool-overnight'))).toBe('Ends at 06:00 the next day.');
      await save();

      const req = http.expectOne({ method: 'PATCH', url: `${HOSTS}/${IDS.companyHost}` });
      expect(req.request.body).toEqual({
        pool: {
          zone: 'Europe/Prague',
          readyDefault: 1,
          windows: [
            { days: ['MON', 'TUE', 'WED', 'THU', 'FRI'], from: '08:00', to: '18:00', ready: 2 },
            { days: ['SAT'], from: '22:00', to: '06:00', ready: 1 },
          ],
        },
      });
      req.flush(aPoolRunnerHost());
      await settle();
      await fixture.whenStable();
      expect(notify.success).toHaveBeenCalledWith(
        'Schedule of droplets saved',
        'The host follows it within a minute.',
      );
      expect(words(q('pool-edit', host('droplets')))).toBe('Edit schedule');
    });

    it('checks the schedule before it sends it, and shows the server’s reason', async () => {
      await open([aPoolRunnerHost({ desiredMaxConcurrentRuns: 3 })]);
      await openSchedule();
      typeInto(document.querySelector('#pool-default'), '4');
      inDialog('pool-window-remove')!.click();
      await settle();
      expect(words(inDialog('pool-no-windows'))).toBe(
        'No window: the count above applies at all times.',
      );
      await save();
      // Three slots set in Tinfirm: four cannot wait.
      expect(words(dialog())).toContain('Choose a number from 0 to 3, the host’s slots.');
      http.expectNone({ method: 'PATCH' });

      typeInto(document.querySelector('#pool-default'), '2');
      await save();
      http
        .expectOne({ method: 'PATCH', url: `${HOSTS}/${IDS.companyHost}` })
        .flush(
          problem(
            400,
            'VALIDATION_FAILED',
            'The time zone Europe/Prague is unknown; give an IANA time zone',
          ),
          { status: 400, statusText: 'Bad Request' },
        );
      await settle();
      await fixture.whenStable();
      expect(words(inDialog('pool-error'))).toBe(
        'The time zone Europe/Prague is unknown; give an IANA time zone',
      );
    });

    it('turns ready sandboxes off, and says when the host keeps none', async () => {
      await open([aPoolRunnerHost()]);
      await openSchedule();
      inDialog('pool-clear')!.click();
      await settle();
      const req = http.expectOne({ method: 'PATCH', url: `${HOSTS}/${IDS.companyHost}` });
      expect(req.request.body).toEqual({ clearPool: true });
      req.flush(problem(409, 'RUNNER_HOST_POOL_UNSUPPORTED'), {
        status: 409,
        statusText: 'Conflict',
      });
      await settle();
      await fixture.whenStable();
      expect(words(inDialog('pool-error'))).toBe(
        'This runner host keeps no ready sandboxes: only a company host with a VM per run does.',
      );

      inDialog('pool-clear')!.click();
      await settle();
      const pooled = aPoolRunnerHost();
      http
        .expectOne({ method: 'PATCH', url: `${HOSTS}/${IDS.companyHost}` })
        .flush(aPoolRunnerHost({ pool: { ...pooled.pool!, settings: null, next: null } }));
      await settle();
      await fixture.whenStable();
      expect(notify.success).toHaveBeenCalledWith(
        'No sandbox waits on droplets',
        'The host follows it within a minute.',
      );
    });
  });
});
