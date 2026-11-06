import { HttpErrorResponse } from '@angular/common/http';
import { HttpTestingController } from '@angular/common/http/testing';
import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import type {
  ExternalSystem,
  MemberAccountCount,
  SystemCredential,
} from '../../../api/generated/models';
import { BrowserNavigation } from '../../../core/browser-navigation';
import {
  aCredentialGrant,
  aCredentialLending,
  aJiraSystem,
  aMemberAccountCount,
  aServiceIdentity,
  aSlackSystem,
  aSystem,
  aSystemCredential,
  aSystemCredentialList,
  AT,
  IDS,
} from '../../../testing/fixtures';
import {
  fakeCurrentCompany,
  fakeNotify,
  problem,
  provideTestHttp,
  settle,
  submitForm,
  typeInto,
} from '../../../testing/test-helpers';
import { words } from '../../../testing/words';
import { formatDay } from '../../../shared/time';
import {
  credentialProblem,
  credentialState,
  memberAccountsLine,
  triggersActing,
  triggersFollowing,
  triggersPausing,
} from './credential-labels';
import { borrowedElsewhere } from './credential-lend-dialog';
import { removalLines, SystemCredentials, withdrawalText } from './system-credentials';

const API = '/api/companies/c1';
const GITHUB = `${API}/systems/${IDS.github}`;

/** A second credential of GitHub: a sign-in the system refused, lent to nobody. */
const CI_BOT = aSystemCredential({
  id: 'cred-ci',
  name: 'CI bot',
  kind: 'OAUTH',
  status: 'RECONNECT',
  login: 'acme-ci',
  lastError: 'The grant expired',
  connectedByName: 'Adam Admin',
  lastUsedAt: null,
  grants: [],
  serviceAccounts: [],
});

describe('credential words', () => {
  it.each`
    case                    | credential                                                    | label                  | tone
    ${'working'}            | ${aSystemCredential()}                                        | ${'Working'}           | ${'success'}
    ${'a refused sign-in'}  | ${CI_BOT}                                                     | ${'Sign in again'}     | ${'human'}
    ${'a refused token'}    | ${aSystemCredential({ status: 'RECONNECT' })}                 | ${'Store a new token'} | ${'human'}
    ${'broken'}             | ${aSystemCredential({ status: 'ERROR' })}                     | ${'Not working'}       | ${'destructive'}
    ${'disabled, whatever'} | ${aSystemCredential({ status: 'RECONNECT', enabled: false })} | ${'Disabled'}          | ${'neutral'}
  `('a credential $case reads “$label”', ({ credential, label, tone }) => {
    expect(credentialState(credential)).toEqual({ label, tone });
  });

  it('says what removing one does: its grants, its lendings with their triggers, then its secret', () => {
    expect(removalLines(aSystemCredential(), 'GitHub')).toEqual([
      'Analysts no longer acts with it: its grant goes first.',
      'svc-reader no longer borrows it: 2 triggers of svc-reader act with it and pause until their authors name another.',
      'Its token is deleted from the vault, and its sign-in revoked at GitHub where it offers that. Conversations that act with it stop at their next use of it.',
    ]);
    expect(removalLines(CI_BOT, 'GitHub')).toHaveLength(1);
    // Each service account's triggers by how many; one that none acts through pauses nothing.
    const lent = (name: string, triggers: number) =>
      aCredentialLending({ serviceIdentityId: `p-${name}`, name, triggers });
    expect(
      removalLines(
        aSystemCredential({
          grants: [],
          serviceAccounts: [lent('svc-reader', 1), lent('svc-ops', 0), lent('svc-dev', 3)],
        }),
        'GitHub',
      )[0],
    ).toBe(
      'svc-reader, svc-ops and svc-dev no longer borrow it: 1 trigger of svc-reader and 3 triggers of svc-dev act with it and pause until their authors name another.',
    );
    expect(
      removalLines(
        aSystemCredential({ grants: [], serviceAccounts: [lent('svc-ops', 0)] }),
        'GitHub',
      )[0],
    ).toBe('svc-ops no longer borrows it; no trigger acts with it.');
  });

  it('says what withdrawing one pauses, by how many triggers act with it', () => {
    expect(withdrawalText(aCredentialLending(), 'GitHub')).toBe(
      'svc-reader borrows no credential on GitHub then. 2 triggers of svc-reader act with it and pause until their authors name another.',
    );
    expect(withdrawalText(aCredentialLending({ triggers: 1 }), 'GitHub')).toBe(
      'svc-reader borrows no credential on GitHub then. 1 trigger of svc-reader acts with it and pauses until its author names another.',
    );
    expect(withdrawalText(aCredentialLending({ triggers: 0 }), 'GitHub')).toBe(
      'svc-reader borrows no credential on GitHub then. No trigger of svc-reader acts with it.',
    );
    expect(triggersPausing(aCredentialLending({ triggers: 0 }))).toBeNull();
    expect(triggersFollowing(aCredentialLending(), 'CI bot')).toBe(
      '2 triggers of svc-reader act with it and switch to CI bot; their authors are told',
    );
    expect(triggersFollowing(aCredentialLending({ triggers: 1 }), 'CI bot')).toBe(
      '1 trigger of svc-reader acts with it and switches to CI bot; its author is told',
    );
    expect(triggersFollowing(aCredentialLending({ triggers: 0 }), 'CI bot')).toBeNull();
    expect(triggersActing(0)).toBe('no trigger acts with it');
    expect(triggersActing(1)).toBe('1 trigger acts with it');
  });

  it.each`
    count                  | line
    ${{ ok: 0, notOk: 0 }} | ${'No member connects their own account on GitHub yet.'}
    ${{ ok: 1, notOk: 0 }} | ${'1 member connects their own account on GitHub.'}
    ${{ ok: 2, notOk: 1 }} | ${'3 members connect their own account on GitHub (1 needs signing in again).'}
    ${{ ok: 0, notOk: 2 }} | ${'2 members connect their own account on GitHub (2 need signing in again).'}
  `('counts the members’ own accounts: $line', ({ count, line }) => {
    expect(memberAccountsLine(count as MemberAccountCount, 'GitHub')).toBe(line);
  });

  it('finds what a service account borrows on the system now, other than this one', () => {
    const credentials = [aSystemCredential(), CI_BOT];
    expect(borrowedElsewhere(credentials, CI_BOT.id, IDS.svcReader)?.name).toBe('svc-reader');
    expect(borrowedElsewhere(credentials, IDS.readerGithubAccount, IDS.svcReader)).toBeNull();
    expect(borrowedElsewhere(credentials, CI_BOT.id, 'p-svc-ops')).toBeNull();
  });

  it('says a member’s user taken for a credential without naming them, and a refused token', () => {
    const inUse = (details: Record<string, unknown>) =>
      new HttpErrorResponse({
        status: 409,
        error: { ...problem(409, 'IDENTITY_IN_USE'), details },
      });
    expect(credentialProblem(inUse({ holder: 'MEMBER' }), 'GitHub')).toBe(
      'This GitHub user is a member’s own account. Use a user of the company’s own, such as a machine user.',
    );
    expect(
      credentialProblem(inUse({ holder: 'CREDENTIAL', credentialName: 'CI bot' }), 'GitHub'),
    ).toBe('This GitHub user is already the credential “CI bot”.');
    const refused = new HttpErrorResponse({
      status: 400,
      error: problem(400, 'ACCOUNT_REFUSED', 'GitHub answered 401'),
    });
    expect(credentialProblem(refused, 'GitHub')).toBe('GitHub answered 401.');
  });
});

/** The system the next host opens with, so that it reads only that one's credentials. */
let opening: ExternalSystem = aSystem();

@Component({
  selector: 'app-host',
  imports: [SystemCredentials],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<app-system-credentials
    companyId="c1"
    [system]="system()"
    (credentials)="read.set($event)"
  />`,
})
class Host {
  readonly system = signal<ExternalSystem>(opening);
  readonly read = signal<readonly SystemCredential[] | null>(null);
}

describe('SystemCredentials', () => {
  let http: HttpTestingController;
  let harness: RouterTestingHarness;
  let host: Host;
  let notify: ReturnType<typeof fakeNotify>['notify'];
  const navigation = { assign: vi.fn() };

  async function open(
    credentials: SystemCredential[] = [aSystemCredential(), CI_BOT],
    system: ExternalSystem = aSystem(),
    query = '',
    memberAccounts: MemberAccountCount = aMemberAccountCount(),
  ) {
    navigation.assign.mockReset();
    opening = system;
    const notifier = fakeNotify();
    notify = notifier.notify;
    TestBed.configureTestingModule({
      providers: [
        provideRouter([{ path: 'c/:companyId/systems/:systemId', component: Host }]),
        ...provideTestHttp(),
        fakeCurrentCompany('ADMIN').provider,
        notifier.provider,
        { provide: BrowserNavigation, useValue: navigation },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    harness = await RouterTestingHarness.create();
    host = await harness.navigateByUrl(`/c/c1/systems/${system.id}${query}`, Host);
    harness.detectChanges();
    await settle();
    http
      .expectOne(`${API}/systems/${system.id}/credentials`)
      .flush(aSystemCredentialList(credentials, memberAccounts));
    await settle();
    harness.detectChanges();
  }

  /** Answers the credentials read again after a change. */
  async function reread(credentials: SystemCredential[], system: ExternalSystem = aSystem()) {
    await settle();
    http
      .expectOne(`${API}/systems/${system.id}/credentials`)
      .flush(aSystemCredentialList(credentials));
    await settle();
    harness.detectChanges();
  }

  const el = () => harness.routeNativeElement as HTMLElement;
  const row = (id: string) => el().querySelector<HTMLElement>(`[data-credential="${id}"]`)!;
  const inRow = (id: string, testId: string) =>
    row(id).querySelector<HTMLElement>(`[data-testid=${testId}]`);
  const overlay = () => document.querySelector('.cdk-overlay-container') as HTMLElement;
  const inOverlay = <T extends HTMLElement = HTMLElement>(selector: string) =>
    overlay()?.querySelector<T>(selector) ?? null;
  const pick = (selector: string, value: string) => {
    const select = inOverlay<HTMLSelectElement>(selector)!;
    select.value = value;
    select.dispatchEvent(new Event('change'));
  };

  afterEach(() => {
    http.verify();
    overlay()?.replaceChildren();
  });

  it('lists each credential: its state, who it is, the groups and service accounts it is lent to', async () => {
    await open();
    expect(words(el().querySelector('h2'))).toBe('Credentials 2');
    const reader = IDS.readerGithubAccount;
    expect(words(inRow(reader, 'credential-name'))).toBe('svc-reader');
    expect(words(inRow(reader, 'credential-state'))).toBe('Working');
    expect(words(inRow(reader, 'credential-login'))).toBe('svc-reader');
    expect(words(row(reader))).toContain('by Olga Owner');
    // Its grant to Analysts, read-only, as pills; svc-reader borrows it with writes.
    const grant = inRow(reader, 'credential-grant')!;
    expect(grant.getAttribute('data-group')).toBe('Analysts');
    expect(grant.querySelector<HTMLInputElement>('input[value=READ]')!.checked).toBe(true);
    const lending = inRow(reader, 'credential-lending')!;
    expect(lending.querySelector('a')?.getAttribute('href')).toBe(
      `/c/c1/service-identities/${IDS.svcReader}`,
    );
    expect(lending.querySelector<HTMLInputElement>('input[value=WRITE]')!.checked).toBe(true);
    // Since when each is lent, and how many of the service account's triggers act with it.
    const since = formatDay(AT.earlier);
    expect(words(grant.querySelector('[data-testid=grant-since]'))).toBe(`Lent since ${since}`);
    expect(words(lending.querySelector('[data-testid=lending-since]'))).toBe(
      `Lent since ${since} · 2 triggers act with it`,
    );

    // A refused sign-in is the admin's to act on: orange, with its fix at hand.
    expect(inRow(CI_BOT.id, 'credential-state')?.getAttribute('data-tone')).toBe('human');
    expect(words(inRow(CI_BOT.id, 'credential-error'))).toBe('The grant expired');
    expect(words(inRow(CI_BOT.id, 'credential-fix'))).toBe('Sign in again');
    expect(words(inRow(CI_BOT.id, 'credential-groups'))).toContain('Lent to no group yet');
    expect(words(inRow(CI_BOT.id, 'credential-service-accounts'))).toContain(
      'Borrowed by no service account',
    );
    // The page learns them, for the tools' and the test's choice.
    expect(host.read()?.map((c) => c.id)).toEqual([reader, CI_BOT.id]);
    // The members' own accounts here, counted, with the way to whose they are.
    expect(words(el().querySelector('[data-testid=credentials-member-count]'))).toBe(
      '3 members connect their own account on GitHub (1 needs signing in again).',
    );
    expect(el().querySelector('[data-testid=credentials-members] a')?.getAttribute('href')).toBe(
      '/c/c1/members',
    );
  });

  it('names the triggers anyone may drive that act with a credential through its lendings (D2)', async () => {
    await open([
      aSystemCredential({
        openTriggers: [
          {
            id: 't-mentioned',
            name: 'Mentioned',
            source: 'TRACKER',
            serviceIdentityId: IDS.svcReader,
            serviceIdentityName: 'svc-reader',
          },
        ],
      }),
      CI_BOT,
    ]);
    const open$ = inRow(IDS.readerGithubAccount, 'credential-open-triggers')!;
    expect(words(open$)).toBe(
      'A trigger anyone may drive acts with it Mentioned: anyone who may write to its issues drives it, through svc-reader',
    );
    expect(open$.querySelector('a')?.getAttribute('href')).toBe('/c/c1/triggers/t-mentioned');
    expect(inRow(CI_BOT.id, 'credential-open-triggers')).toBeNull();
  });

  it('says no member connects their own account yet, with nothing to check on Members', async () => {
    await open([aSystemCredential()], aSystem(), '', aMemberAccountCount({ ok: 0, notOk: 0 }));
    expect(words(el().querySelector('[data-testid=credentials-member-count]'))).toBe(
      'No member connects their own account on GitHub yet.',
    );
    expect(el().querySelector('[data-testid=credentials-members] a')).toBeNull();
  });

  it('brings the credential a link names into view, once read', async () => {
    await open(undefined, aSystem(), `#credential-${CI_BOT.id}`);
    await settle();
    harness.detectChanges();
    expect(document.activeElement).toBe(row(CI_BOT.id));
  });

  it('says there is none yet, and offers to add one', async () => {
    await open([]);
    expect(words(el().querySelector('[data-testid=credentials-empty]'))).toContain(
      'No credential yet. Add one, then lend it',
    );
    expect(el().querySelector('[data-testid=add-credential]')).not.toBeNull();
  });

  it('adds a credential by token: a taken name on its field, a refusal in the system’s words', async () => {
    await open([aSystemCredential()], aJiraSystem());
    el().querySelector<HTMLButtonElement>('[data-testid=add-credential]')!.click();
    await settle();
    // Jira Cloud has no app here: a token, or a service account's client credentials; a token first.
    expect(inOverlay<HTMLInputElement>('#credential-method-token')!.checked).toBe(true);
    expect(inOverlay('#credential-method-client')).not.toBeNull();
    expect(inOverlay('#credential-method-sign-in')).toBeNull();
    expect(words(inOverlay('[data-testid=credential-dialog]'))).toContain(
      'The Atlassian account’s email and an API token',
    );

    // Nothing goes without a name and a token.
    submitForm(inOverlay('#credential-form'));
    await settle();
    expect(words(inOverlay('[data-testid=credential-name-error]'))).toBe('Name the credential.');
    expect(words(inOverlay('[data-testid=credential-token-error]'))).toBe('Paste the token.');

    typeInto(inOverlay('#credential-name'), 'svc-reader');
    typeInto(inOverlay('#credential-token'), 'jira-token');
    submitForm(inOverlay('#credential-form'));
    await settle();
    const taken = http.expectOne(`${API}/systems/${IDS.jira}/credentials`);
    expect(taken.request.method).toBe('POST');
    expect(taken.request.body).toEqual({ name: 'svc-reader', token: 'jira-token' });
    taken.flush(problem(409, 'DUPLICATE_CREDENTIAL_NAME'), { status: 409, statusText: 'Conflict' });
    await settle();
    expect(words(inOverlay('[data-testid=credential-name-error]'))).toContain(
      'The system has a credential of this name already.',
    );

    typeInto(inOverlay('#credential-name'), 'Triage bot');
    submitForm(inOverlay('#credential-form'));
    await settle();
    http
      .expectOne((r) => r.url === `${API}/systems/${IDS.jira}/credentials` && r.method === 'POST')
      .flush(
        problem(
          400,
          'ACCOUNT_REFUSED',
          'The system refused the token: Jira answered 401 when asked whose the token is',
        ),
        { status: 400, statusText: 'Bad Request' },
      );
    await settle();
    expect(words(inOverlay('[data-testid=credential-error]'))).toBe(
      'Jira answered 401 when asked whose the token is.',
    );
    expect(words(overlay())).not.toContain('jira-token');

    submitForm(inOverlay('#credential-form'));
    await settle();
    const added = aSystemCredential({
      id: 'cred-triage',
      systemId: IDS.jira,
      name: 'Triage bot',
      login: 'triage',
      grants: [],
      serviceAccounts: [],
    });
    http
      .expectOne((r) => r.url === `${API}/systems/${IDS.jira}/credentials` && r.method === 'POST')
      .flush(added);
    await reread([aSystemCredential(), added], aJiraSystem());
    expect(inOverlay('[data-testid=credential-dialog]')).toBeNull();
    expect(row('cred-triage')).not.toBeNull();
    expect(notify.success).toHaveBeenCalledWith(
      'Triage bot is added to Jira',
      'As triage. Lend it to a group or a service account next.',
    );
  });

  it('adds a Jira Cloud service account by its client credentials, never showing the secret', async () => {
    await open([], aJiraSystem());
    el().querySelector<HTMLButtonElement>('[data-testid=add-credential]')!.click();
    await settle();
    inOverlay<HTMLInputElement>('#credential-method-client')!.click();
    await settle();
    expect(inOverlay('#credential-token')).toBeNull();
    expect(words(inOverlay('[data-testid=credential-dialog]'))).toContain(
      'An Atlassian service account’s OAuth 2.0 credential',
    );

    // Nothing goes without a name, a client ID and a secret.
    submitForm(inOverlay('#credential-form'));
    await settle();
    expect(words(inOverlay('[data-testid=credential-client-id-error]'))).toBe(
      'Paste the client ID.',
    );
    expect(words(inOverlay('[data-testid=credential-client-secret-error]'))).toBe(
      'Paste the client secret.',
    );

    typeInto(inOverlay('#credential-name'), 'Service account');
    typeInto(inOverlay('#credential-client-id'), ' test-client ');
    typeInto(inOverlay('#credential-client-secret'), 'test-client-secret');
    submitForm(inOverlay('#credential-form'));
    await settle();
    const refused = http.expectOne(`${API}/systems/${IDS.jira}/credentials/client`);
    expect(refused.request.method).toBe('POST');
    expect(refused.request.body).toEqual({
      name: 'Service account',
      clientId: 'test-client',
      clientSecret: 'test-client-secret',
    });
    refused.flush(
      problem(
        400,
        'ACCOUNT_REFUSED',
        'The system refused the client credentials: The token endpoint answered 401 (access_denied)',
      ),
      { status: 400, statusText: 'Bad Request' },
    );
    await settle();
    expect(words(inOverlay('[data-testid=credential-error]'))).toBe(
      'The token endpoint answered 401 (access_denied).',
    );
    expect(words(overlay())).not.toContain('test-client-secret');

    submitForm(inOverlay('#credential-form'));
    await settle();
    const added = aSystemCredential({
      id: 'cred-service',
      systemId: IDS.jira,
      name: 'Service account',
      kind: 'CLIENT',
      login: 'Tinfirm service',
      grants: [],
      serviceAccounts: [],
    });
    http.expectOne(`${API}/systems/${IDS.jira}/credentials/client`).flush(added);
    await reread([added], aJiraSystem());
    expect(inOverlay('[data-testid=credential-dialog]')).toBeNull();
    expect(words(row('cred-service'))).toContain('Client credentials');
  });

  it('stores new client credentials for a refused service account, keeping what it is lent to', async () => {
    const refused = aSystemCredential({
      id: 'cred-service',
      systemId: IDS.jira,
      name: 'Service account',
      kind: 'CLIENT',
      status: 'RECONNECT',
      grants: [],
      serviceAccounts: [],
    });
    expect(credentialState(refused)).toEqual({
      label: 'Store new client credentials',
      tone: 'human',
    });
    await open([refused], aJiraSystem());
    expect(words(inRow(refused.id, 'credential-fix'))).toContain('Store new client credentials');
    inRow(refused.id, 'credential-fix')!.click();
    await settle();
    expect(words(inOverlay('h2'))).toBe('Store new client credentials for Service account');
    expect(inOverlay('#credential-name')).toBeNull();
    typeInto(inOverlay('#credential-client-id'), 'test-client');
    typeInto(inOverlay('#credential-client-secret'), 'test-client-secret-2');
    submitForm(inOverlay('#credential-form'));
    await settle();
    const stored = http.expectOne(`${API}/systems/${IDS.jira}/credentials/${refused.id}/client`);
    expect(stored.request.method).toBe('PUT');
    expect(stored.request.body).toEqual({
      clientId: 'test-client',
      clientSecret: 'test-client-secret-2',
    });
    stored.flush({ ...refused, status: 'OK' });
    await reread([{ ...refused, status: 'OK' }], aJiraSystem());
    expect(inOverlay('[data-testid=credential-dialog]')).toBeNull();
  });

  it('signs in with the system’s app for a new credential, coming back to this page', async () => {
    await open([]);
    el().querySelector<HTMLButtonElement>('[data-testid=add-credential]')!.click();
    await settle();
    inOverlay<HTMLInputElement>('#credential-method-sign-in')!.click();
    await settle();
    expect(inOverlay('#credential-token')).toBeNull();
    typeInto(inOverlay('#credential-name'), 'Release bot');
    submitForm(inOverlay('#credential-form'));
    await settle();
    const start = http.expectOne(`${GITHUB}/credentials/oauth`);
    expect(start.request.body).toEqual({
      name: 'Release bot',
      returnTo: `/c/c1/systems/${IDS.github}`,
    });
    start.flush({ authorizationUrl: 'https://github.test/login/oauth/authorize?state=s' });
    await settle();
    expect(navigation.assign).toHaveBeenCalledWith(
      'https://github.test/login/oauth/authorize?state=s',
    );
  });

  it('signs a refused credential in again, keeping what it is lent to', async () => {
    await open();
    inRow(CI_BOT.id, 'credential-fix')!.click();
    await settle();
    expect(words(inOverlay('h2'))).toBe('Sign CI bot in again');
    expect(inOverlay('#credential-name')).toBeNull();
    submitForm(inOverlay('#credential-form'));
    await settle();
    const start = http.expectOne(`${GITHUB}/credentials/${CI_BOT.id}/oauth`);
    expect(start.request.body).toEqual({ returnTo: `/c/c1/systems/${IDS.github}` });
    start.flush({ authorizationUrl: 'https://github.test/authorize' });
    await settle();
    expect(navigation.assign).toHaveBeenCalledWith('https://github.test/authorize');
  });

  it('says what a sign-in came back with, once', async () => {
    await open([aSystemCredential()], aSystem(), '?account=name_taken');
    expect(words(el().querySelector('[data-testid=credential-sign-in-result]'))).toBe(
      'Another credential of the system took this name in the meantime, so nothing was added. Add it again under another name.',
    );
    expect(TestBed.inject(Router).url).toBe(`/c/c1/systems/${IDS.github}`);
  });

  it('says a sign-in connected a credential, which its read shows', async () => {
    await open([CI_BOT], aSystem(), `?account=connected&system=${IDS.github}`);
    expect(words(el().querySelector('[data-testid=credential-sign-in-result]'))).toBe(
      'The credential is connected. Lend it to a group or a service account.',
    );
    expect(row(CI_BOT.id)).not.toBeNull();
  });

  it('lends a credential to a group, offering only the groups it is not lent to', async () => {
    await open();
    inRow(IDS.readerGithubAccount, 'lend-group')!.click();
    await settle();
    http
      .expectOne((r) => r.url === `${API}/groups`)
      .flush({
        items: [
          { id: IDS.analysts, name: 'Analysts', memberCount: 2 },
          { id: 'g-ops', name: 'Ops', memberCount: 1 },
        ],
      });
    await settle();
    expect(words(inOverlay('h2'))).toBe('Lend svc-reader to a group');
    expect(
      Array.from(inOverlay('#lend-group')!.querySelectorAll('option')).map((o) => words(o)),
    ).toEqual(['Pick a group', 'Ops']);
    submitForm(inOverlay('#lend-form'));
    await settle();
    expect(words(overlay())).toContain('Pick the group to lend it to.');

    pick('#lend-group', 'g-ops');
    await settle();
    submitForm(inOverlay('#lend-form'));
    await settle();
    const req = http.expectOne((r) => r.url === `${API}/grants` && r.method === 'POST');
    expect(req.request.body).toEqual({
      credentialId: IDS.readerGithubAccount,
      groupId: 'g-ops',
      access: 'READ',
    });
    req.flush({
      id: 'grant-ops',
      credentialId: IDS.readerGithubAccount,
      systemId: IDS.github,
      systemName: 'GitHub',
      credentialName: 'svc-reader',
      groupId: 'g-ops',
      groupName: 'Ops',
      access: 'READ',
      createdAt: '2026-10-17T08:00:00Z',
      updatedAt: '2026-10-17T08:00:00Z',
    });
    await reread([
      aSystemCredential({
        grants: [aCredentialGrant(), aCredentialGrant({ id: 'grant-ops', groupName: 'Ops' })],
      }),
      CI_BOT,
    ]);
    expect(notify.success).toHaveBeenCalledWith('svc-reader is lent to Ops', 'Read-only');
    expect(
      row(IDS.readerGithubAccount).querySelectorAll('[data-testid=credential-grant]'),
    ).toHaveLength(2);
  });

  it('lends a credential to a service account, saying what it replaces there', async () => {
    await open();
    inRow(CI_BOT.id, 'lend-service-account')!.click();
    await settle();
    http
      .expectOne((r) => r.url === `${API}/service-identities`)
      .flush({
        items: [
          aServiceIdentity(),
          aServiceIdentity({ id: 'p-svc-ops', name: 'svc-ops', status: 'SUSPENDED' }),
        ],
      });
    await settle();
    expect(words(inOverlay('h2'))).toBe('Lend CI bot to a service account');
    expect(
      Array.from(inOverlay('#lend-service-account')!.querySelectorAll('option')).map((o) =>
        words(o),
      ),
    ).toEqual(['Pick a service account', 'svc-reader', 'svc-ops (suspended)']);
    // A service account's own credential was always WRITE: the default.
    expect(inOverlay<HTMLInputElement>('#lend-access-WRITE')!.checked).toBe(true);

    pick('#lend-service-account', IDS.svcReader);
    await settle();
    // It borrows svc-reader on GitHub now, for 2 triggers: one per system, so this one replaces
    // it and they switch to it.
    expect(words(inOverlay('[data-testid=lend-replaces]'))).toBe(
      'svc-reader borrows svc-reader on GitHub now. Lending it CI bot replaces svc-reader: 2 triggers of svc-reader act with it and switch to CI bot; their authors are told.',
    );
    pick('#lend-service-account', 'p-svc-ops');
    await settle();
    expect(inOverlay('[data-testid=lend-replaces]')).toBeNull();

    pick('#lend-service-account', IDS.svcReader);
    await settle();
    submitForm(inOverlay('#lend-form'));
    await settle();
    const req = http.expectOne(
      `${GITHUB}/credentials/${CI_BOT.id}/service-accounts/${IDS.svcReader}`,
    );
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ access: 'WRITE' });
    req.flush({ ...CI_BOT, serviceAccounts: [aCredentialLending()] });
    await reread([
      aSystemCredential({ serviceAccounts: [] }),
      { ...CI_BOT, serviceAccounts: [aCredentialLending()] },
    ]);
    expect(notify.success).toHaveBeenCalledWith('svc-reader borrows CI bot now', 'Read and write');
  });

  it('says a replaced credential that no trigger acts with pauses nothing', async () => {
    await open([
      aSystemCredential({ serviceAccounts: [aCredentialLending({ triggers: 0 })] }),
      CI_BOT,
    ]);
    inRow(CI_BOT.id, 'lend-service-account')!.click();
    await settle();
    http
      .expectOne((r) => r.url === `${API}/service-identities`)
      .flush({ items: [aServiceIdentity()] });
    await settle();
    pick('#lend-service-account', IDS.svcReader);
    await settle();
    expect(words(inOverlay('[data-testid=lend-replaces]'))).toBe(
      'svc-reader borrows svc-reader on GitHub now. Lending it CI bot replaces svc-reader; no trigger acts with it.',
    );
  });

  it('changes a grant’s access in place, and withdraws a lending after saying what pauses', async () => {
    await open();
    inRow(IDS.readerGithubAccount, 'credential-grant')!
      .querySelector<HTMLInputElement>('input[value=WRITE]')!
      .click();
    await settle();
    const change = http.expectOne(`${API}/grants/${IDS.analystsGrant}`);
    expect(change.request.method).toBe('PATCH');
    expect(change.request.body).toEqual({ access: 'WRITE' });
    change.flush({});
    await reread([aSystemCredential({ grants: [aCredentialGrant({ access: 'WRITE' })] }), CI_BOT]);
    expect(notify.success).toHaveBeenCalledWith(
      'Analysts now has read and write access to svc-reader',
      'It applies at the credential’s next use.',
    );

    row(IDS.readerGithubAccount)
      .querySelector<HTMLButtonElement>('button[aria-label="Withdraw svc-reader from svc-reader"]')!
      .click();
    await settle();
    expect(words(inOverlay('[data-testid=withdraw-effects]'))).toBe(
      'svc-reader borrows no credential on GitHub then. 2 triggers of svc-reader act with it and pause until their authors name another.',
    );
    inOverlay<HTMLButtonElement>('[data-testid=confirm-withdraw]')!.click();
    await settle();
    http
      .expectOne(
        (r) =>
          r.url ===
            `${GITHUB}/credentials/${IDS.readerGithubAccount}/service-accounts/${IDS.svcReader}` &&
          r.method === 'DELETE',
      )
      .flush(null);
    await reread([aSystemCredential({ serviceAccounts: [] }), CI_BOT]);
    expect(notify.success).toHaveBeenCalledWith('svc-reader is withdrawn from svc-reader');
  });

  it('disables and removes a credential from its menu', async () => {
    await open();
    inRow(IDS.readerGithubAccount, 'credential-menu')!.click();
    await settle();
    inOverlay('[data-testid=credential-toggle]')!.click();
    await settle();
    const disable = http.expectOne(`${GITHUB}/credentials/${IDS.readerGithubAccount}`);
    expect(disable.request.method).toBe('PATCH');
    expect(disable.request.body).toEqual({ enabled: false });
    disable.flush(aSystemCredential({ enabled: false }));
    await reread([aSystemCredential({ enabled: false }), CI_BOT]);
    expect(words(inRow(IDS.readerGithubAccount, 'credential-state'))).toBe('Disabled');

    inRow(IDS.readerGithubAccount, 'credential-menu')!.click();
    await settle();
    inOverlay('[data-testid=credential-remove]')!.click();
    await settle();
    expect(words(inOverlay('[data-testid=remove-credential-effects]'))).toContain(
      'Analysts no longer acts with it: its grant goes first.',
    );
    inOverlay<HTMLButtonElement>('[data-testid=confirm-remove-credential]')!.click();
    await settle();
    http
      .expectOne(
        (r) =>
          r.url === `${GITHUB}/credentials/${IDS.readerGithubAccount}` && r.method === 'DELETE',
      )
      .flush(null);
    await reread([CI_BOT]);
    expect(row(IDS.readerGithubAccount)).toBeNull();
    expect(notify.success).toHaveBeenCalledWith('svc-reader is removed');
  });

  it('shows a chat system’s bot, marked, with nothing to change here', async () => {
    const slack = aSlackSystem();
    const bot = aSystemCredential({
      id: IDS.slackBotAccount,
      systemId: slack.id,
      name: 'Bot',
      login: 'U07BOT001',
      bot: true,
      grants: [],
      serviceAccounts: [
        aCredentialLending({ serviceIdentityId: IDS.svcSlackBot, name: 'svc-slack-bot' }),
      ],
    });
    await open([bot], slack);
    expect(words(inRow(bot.id, 'credential-bot'))).toBe('Chat bot');
    expect(inRow(bot.id, 'credential-menu')).toBeNull();
    expect(inRow(bot.id, 'credential-fix')).toBeNull();
    expect(inRow(bot.id, 'lend-group')).toBeNull();
    expect(inRow(bot.id, 'lend-service-account')).toBeNull();
    expect(words(inRow(bot.id, 'credential-bot-note'))).toContain('Borrowed by svc-slack-bot.');
    expect(inRow(bot.id, 'credential-bot-link')?.getAttribute('href')).toBe(
      `/c/c1/systems/${slack.id}?tab=bot`,
    );
  });
});
