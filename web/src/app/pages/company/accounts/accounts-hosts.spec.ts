import { HttpTestingController } from '@angular/common/http/testing';
import { type ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import type { RunnerHost } from '../../../api/generated/models';
import { aPersonalRunnerHost, aRunnerHost, IDS } from '../../../testing/fixtures';
import {
  fakeCurrentCompany,
  fakeNotify,
  problem,
  provideTestHttp,
  settle,
  submitForm,
  typeInto,
} from '../../../testing/test-helpers';
import { AccountsHosts } from './accounts-hosts';

const HOSTS = '/api/companies/c1/runner-hosts';
const TOKEN = 'tfr_0123456789abcdefghijklmnopqrstuvwxyzABCDEFG';

describe('AccountsHosts', () => {
  let fixture: ComponentFixture<AccountsHosts>;
  let http: HttpTestingController;
  let el: HTMLElement;
  let notify: ReturnType<typeof fakeNotify>['notify'];

  async function open(items: RunnerHost[]) {
    const notifier = fakeNotify();
    notify = notifier.notify;
    TestBed.configureTestingModule({
      imports: [AccountsHosts],
      providers: [
        provideRouter([]),
        ...provideTestHttp(),
        fakeCurrentCompany('ADMIN').provider,
        notifier.provider,
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(AccountsHosts);
    el = fixture.nativeElement;
    await settle();
    http.expectOne((r) => r.url === HOSTS && r.method === 'GET').flush({ items });
    await settle();
    await fixture.whenStable();
  }

  const q = (testId: string, root: ParentNode = el) =>
    root.querySelector<HTMLElement>(`[data-testid=${testId}]`);
  const all = (testId: string) => Array.from(el.querySelectorAll(`[data-testid=${testId}]`));
  const overlay = () => document.querySelector('.cdk-overlay-container') as HTMLElement;
  const text = (node: Element | null | undefined) => node?.textContent?.replace(/\s+/g, ' ').trim();

  afterEach(() => {
    http.verify();
    overlay()?.replaceChildren();
  });

  it('lists only the reader’s own hosts, with their slots and subscriptions', async () => {
    // An admin's list holds the company's hosts too; they live on Runner hosts.
    await open([aRunnerHost(), aPersonalRunnerHost()]);
    const hosts = all('runner-host');
    expect(hosts).toHaveLength(1);
    const host = hosts[0];
    expect(host.id).toBe(`host-${IDS.personalHost}`);
    expect(text(q('host-state', host))).toBe('Online');
    expect(text(host.querySelector('h3'))).toBe('alice-macbook');
    expect(text(q('host-slots', host))).toBe('0 of 1 slot in use');
    expect(text(host)).toContain('v0.2.0 · darwin/arm64');
    const subscription = q('host-subscription', host)!;
    expect(subscription.getAttribute('data-status')).toBe('AVAILABLE');
    expect(text(subscription)).toBe('claude-max Claude subscription · Claude Code Available');
  });

  it('says a host is offline, draining or waiting to apply a change', async () => {
    await open([
      aPersonalRunnerHost({
        online: false,
        lastSeenAt: '2026-10-12T06:00:00Z',
        configPending: true,
        credentials: [
          {
            kind: 'CLAUDE_LOGIN',
            label: 'claude-max',
            runtimes: ['CLAUDE_CODE'],
            status: 'OFFLINE',
            reported: true,
          },
        ],
      }),
      aPersonalRunnerHost({
        id: 'h-2',
        name: 'alice-desktop',
        draining: true,
        activeRuns: 2,
        credentials: [],
        lastSeenAt: null,
      }),
    ]);
    const [offline, draining] = all('runner-host');
    expect(text(q('host-state', offline))).toMatch(/^Offline · last seen/);
    expect(text(q('host-pending', offline))).toBe(
      'alice-macbook applies the change when it connects.',
    );
    expect(text(q('host-subscription', offline))).toContain('Host offline');
    expect(q('host-draining', draining)).not.toBeNull();
    expect(text(q('host-slots', draining))).toBe(
      'Finishing 2 runs before it stops; it takes no new runs.',
    );
    expect(text(draining)).toContain('The host reports its subscriptions when it connects.');
  });

  it('says what a host of one’s own is for when there is none', async () => {
    await open([aRunnerHost()]);
    expect(text(q('hosts-empty'))).toContain('No runner host of yours yet');
    expect(all('runner-host')).toHaveLength(0);
  });

  it('registers a personal host and shows its token once', async () => {
    await open([]);
    q('open-register-host')!.click();
    await settle();
    expect(text(overlay().querySelector('h2'))).toBe('Register a runner host of yours');
    typeInto(overlay().querySelector('#runner-host-name'), 'alice-desktop');
    submitForm(overlay().querySelector('#register-runner-host-form'));
    await settle();
    const register = http.expectOne({ method: 'POST', url: HOSTS });
    expect(register.request.body).toEqual({ name: 'alice-desktop', scope: 'PERSONAL' });
    register.flush({
      runnerHost: aPersonalRunnerHost({
        id: 'h-new',
        name: 'alice-desktop',
        online: false,
        lastSeenAt: null,
        credentials: [],
      }),
      token: TOKEN,
      connectUrl: 'wss://tinfirm.acme.test/api/runner/v1/connect',
    });
    await settle();
    expect(text(overlay().querySelector('[data-testid=runner-token]'))).toBe(TOKEN);
    expect(text(overlay())).toContain(
      `tinfirm-runner-host login --server https://tinfirm.acme.test --token ${TOKEN}`,
    );
    expect(text(overlay())).toContain(
      'tinfirm-runner-host credential add claude --label claude-max',
    );
    // The list is read again with the new host.
    http
      .expectOne((r) => r.url === HOSTS && r.method === 'GET')
      .flush({
        items: [
          aPersonalRunnerHost({
            id: 'h-new',
            name: 'alice-desktop',
            online: false,
            credentials: [],
          }),
        ],
      });
    await settle();

    overlay().querySelector<HTMLElement>('[data-testid=runner-host-done]')!.click();
    await settle();
    expect(overlay().textContent).not.toContain(TOKEN);
    await fixture.whenStable();
    expect(text(el.querySelector('[data-testid=runner-host] h3'))).toBe('alice-desktop');
  });

  it('says a name another host has', async () => {
    await open([]);
    q('open-register-host')!.click();
    await settle();
    typeInto(overlay().querySelector('#runner-host-name'), 'alice-macbook');
    submitForm(overlay().querySelector('#register-runner-host-form'));
    await settle();
    http
      .expectOne({ method: 'POST', url: HOSTS })
      .flush(problem(409, 'DUPLICATE_RUNNER_HOST_NAME'), { status: 409, statusText: 'Conflict' });
    await settle();
    expect(text(overlay())).toContain(
      'A runner host with this name already exists in this company.',
    );
  });

  it('sets how many runs a host takes, and says a refusal', async () => {
    await open([aPersonalRunnerHost({ maxConcurrentRunsCeiling: 4 })]);
    const select = el.querySelector<HTMLSelectElement>(`#host-capacity-${IDS.personalHost}`)!;
    expect(select.value).toBe('');
    select.value = '2';
    select.dispatchEvent(new Event('change'));
    await settle();
    const update = http.expectOne({ method: 'PATCH', url: `${HOSTS}/${IDS.personalHost}` });
    expect(update.request.body).toEqual({ desiredMaxConcurrentRuns: 2 });
    update.flush(
      aPersonalRunnerHost({
        maxConcurrentRunsCeiling: 4,
        desiredMaxConcurrentRuns: 2,
        configPending: true,
      }),
    );
    await settle();
    await fixture.whenStable();
    expect(text(q('host-pending'))).toBe('Waiting for alice-macbook to apply the change.');

    select.value = '';
    select.dispatchEvent(new Event('change'));
    await settle();
    const clear = http.expectOne({ method: 'PATCH', url: `${HOSTS}/${IDS.personalHost}` });
    expect(clear.request.body).toEqual({ clearDesiredMaxConcurrentRuns: true });
    clear.flush(problem(400, 'VALIDATION_FAILED'), { status: 400, statusText: 'Bad Request' });
    await settle();
    await fixture.whenStable();
    expect(text(el.querySelector('[role=alert]'))).toBe('Choose a number from 1 to 16.');
  });

  it('removes a host after asking, and says why it cannot while it runs', async () => {
    await open([aPersonalRunnerHost()]);
    q('host-remove')!.click();
    await settle();
    expect(text(overlay().querySelector('h2'))).toBe('Remove alice-macbook?');
    overlay().querySelector<HTMLElement>('[data-testid=confirm-remove-host]')!.click();
    await settle();
    http
      .expectOne({ method: 'DELETE', url: `${HOSTS}/${IDS.personalHost}` })
      .flush(problem(409, 'RUNNER_HOST_HAS_ACTIVE_RUNS'), { status: 409, statusText: 'Conflict' });
    await settle();
    expect(text(overlay().querySelector('[data-testid=remove-host-error]'))).toContain(
      'Runs are queued for or running on this runner host.',
    );

    overlay().querySelector<HTMLElement>('[data-testid=confirm-remove-host]')!.click();
    await settle();
    http
      .expectOne({ method: 'DELETE', url: `${HOSTS}/${IDS.personalHost}` })
      .flush(null, { status: 204, statusText: 'No Content' });
    await settle();
    await fixture.whenStable();
    expect(all('runner-host')).toHaveLength(0);
    expect(notify.success).toHaveBeenCalledWith(
      'alice-macbook is removed',
      'Its token no longer works.',
    );
  });
});
