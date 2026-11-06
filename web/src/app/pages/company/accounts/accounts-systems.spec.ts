import { HttpTestingController } from '@angular/common/http/testing';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import type { MyAccount } from '../../../api/generated/models';
import { BrowserNavigation } from '../../../core/browser-navigation';
import {
  aGrantChoice,
  aMyAccount,
  anAccount,
  aChatAccount,
  aPersonalMcpServer,
  aSystemRef,
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
import { AccountsSystems } from './accounts-systems';

@Component({ selector: 'app-system-page', template: '<h1>System</h1>' })
class SystemPage {}

const ACCOUNTS = '/api/companies/c1/me/accounts';
const SETTINGS = '/api/companies/c1/system-settings';

const JIRA = aSystemRef({ id: IDS.jira, name: 'Jira', handle: 'jira', kind: 'JIRA_DC' });
const CRM = aSystemRef({ id: IDS.crm, name: 'My CRM', handle: 'crm', kind: 'MCP', personal: true });

/** Alice: her own GitHub account, Jira through Analysts, and her own MCP server to connect. */
const ALICE: MyAccount[] = [
  aMyAccount(),
  aMyAccount({
    system: JIRA,
    account: null,
    binding: 'GRANTED',
    grants: [aGrantChoice()],
    oauthAvailable: false,
  }),
  aMyAccount({ system: CRM, account: null, binding: 'CONNECT', oauthAvailable: false }),
];

describe('AccountsSystems', () => {
  let http: HttpTestingController;
  let harness: RouterTestingHarness;
  let notify: ReturnType<typeof fakeNotify>['notify'];
  const navigation = { assign: vi.fn() };

  async function open(
    items: MyAccount[],
    options: { url?: string; personalMcpServers?: boolean; role?: 'MEMBER' | 'ADMIN' } = {},
  ) {
    navigation.assign.mockReset();
    const notifier = fakeNotify();
    notify = notifier.notify;
    TestBed.configureTestingModule({
      providers: [
        provideRouter([
          { path: 'c/:companyId/accounts', component: AccountsSystems },
          { path: 'c/:companyId/systems/:systemId', component: SystemPage },
        ]),
        ...provideTestHttp(),
        fakeCurrentCompany(options.role ?? 'MEMBER').provider,
        notifier.provider,
        { provide: BrowserNavigation, useValue: navigation },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    harness = await RouterTestingHarness.create();
    await harness.navigateByUrl(options.url ?? '/c/c1/accounts');
    await settle();
    http.expectOne(ACCOUNTS).flush({ items });
    http.expectOne(SETTINGS).flush({ personalMcpServers: options.personalMcpServers ?? true });
    await settle();
    harness.detectChanges();
  }

  const el = () => harness.routeNativeElement as HTMLElement;
  const row = (systemId: string) => el().querySelector<HTMLElement>(`#system-${systemId}`)!;
  const inRow = (systemId: string, testId: string) =>
    row(systemId).querySelector<HTMLElement>(`[data-testid=${testId}]`);
  const overlay = () => document.querySelector('.cdk-overlay-container') as HTMLElement;
  const text = (node: Element | null | undefined) => node?.textContent?.replace(/\s+/g, ' ').trim();

  afterEach(() => {
    http.verify();
    overlay()?.replaceChildren();
  });

  it('draws each system by how a conversation would act there', async () => {
    await open(ALICE);

    const github = row(IDS.github);
    expect(github.getAttribute('data-binding')).toBe('OWN');
    expect(text(inRow(IDS.github, 'account-chip'))).toBe('Your own account');
    expect(text(inRow(IDS.github, 'account-says'))).toBe(
      'Agents act on GitHub as you: alice-novak.',
    );
    expect(text(inRow(IDS.github, 'account-meta'))).toContain('Connected by signing in');
    expect(text(inRow(IDS.github, 'account-sign-in'))).toBe('Sign in again');
    expect(inRow(IDS.github, 'account-disconnect')).not.toBeNull();

    expect(text(inRow(IDS.jira, 'account-chip'))).toBe('Via Analysts, read-only');
    // Without an app, a token is the only way, and connecting one's own is offered quietly.
    expect(inRow(IDS.jira, 'account-sign-in')).toBeNull();
    expect(text(inRow(IDS.jira, 'account-paste-token'))).toBe('Paste a token');
    expect(inRow(IDS.jira, 'account-disconnect')).toBeNull();

    // One's own MCP server sits in its own section, with its page.
    const personal = el().querySelector('[aria-labelledby=personal-systems-heading] ul')!;
    expect(personal.contains(row(IDS.crm))).toBe(true);
    expect(inRow(IDS.crm, 'account-system-page')?.getAttribute('href')).toBe(
      `/c/c1/systems/${IDS.crm}`,
    );
    // Nothing waits on Alice: no orange anywhere.
    expect(el().querySelector('.text-human, .border-human')).toBeNull();
  });

  it('puts the chats in their own section: a link, made from the chat, nothing to connect', async () => {
    await open([...ALICE, aChatAccount()]);
    http.expectOne('/api/companies/c1/me/chat').flush({ defaultAgentId: null });
    http.expectOne((r) => r.url === '/api/companies/c1/agents').flush({ items: [] });
    await settle();
    harness.detectChanges();
    // Not a company system's row with a token to paste.
    expect(el().querySelector(`#system-${IDS.slack}`)).toBeNull();
    const chat = el().querySelector<HTMLElement>(`#chat-${IDS.slack}`)!;
    expect(chat.getAttribute('data-linked')).toBe('true');
    expect(chat.querySelector('[data-testid=account-paste-token]')).toBeNull();
    expect(chat.querySelector('[data-testid=chat-unlink]')).not.toBeNull();
  });

  it('leaves out a webhook system: it takes deliveries, no one connects an account on it', async () => {
    const hook = aSystemRef({
      id: IDS.hook,
      name: 'Ops alerts',
      handle: 'ops_alerts',
      kind: 'WEBHOOK',
    });
    await open([...ALICE, aMyAccount({ system: hook, account: null, binding: 'CONNECT' })]);
    expect(el().querySelector(`#system-${IDS.hook}`)).toBeNull();
    expect(el().textContent).not.toContain('Ops alerts');
    expect(row(IDS.github)).not.toBeNull();
  });

  it('shows no Chats section without a chat system', async () => {
    await open(ALICE);
    expect(el().querySelector('app-my-chat-links')).toBeNull();
  });

  it('is orange for an account to sign in again, and neutral for an admin’s', async () => {
    await open([
      aMyAccount({
        account: anAccount({ status: 'RECONNECT', lastError: 'Bad credentials' }),
        binding: 'RECONNECT_YOURS',
      }),
      aMyAccount({
        system: JIRA,
        account: null,
        binding: 'RECONNECT_COMPANY',
        grants: [aGrantChoice()],
      }),
    ]);
    expect(inRow(IDS.github, 'account-chip')?.getAttribute('data-tone')).toBe('you');
    expect(inRow(IDS.github, 'account-chip')?.classList).toContain('text-human');
    expect(text(inRow(IDS.github, 'account-error'))).toBe('Bad credentials');
    expect(text(inRow(IDS.jira, 'account-chip'))).toBe('Waits for an admin');
    expect(inRow(IDS.jira, 'account-chip')?.classList).not.toContain('text-human');
  });

  it('starts a sign-in that comes back to Accounts', async () => {
    await open([aMyAccount({ account: null, binding: 'CONNECT' })]);
    vi.spyOn(TestBed.inject(Router), 'url', 'get').mockReturnValue('/c/c1/accounts');
    inRow(IDS.github, 'account-sign-in')!.click();
    await settle();
    const start = http.expectOne({ method: 'POST', url: `${ACCOUNTS}/${IDS.github}/oauth` });
    expect(start.request.body).toEqual({ returnTo: '/c/c1/accounts' });
    start.flush({ authorizationUrl: 'https://github.test/login/oauth/authorize?state=s' });
    await settle();
    expect(navigation.assign).toHaveBeenCalledWith(
      'https://github.test/login/oauth/authorize?state=s',
    );
  });

  it('connects a pasted token, after saying a refusal in the system’s words', async () => {
    await open(ALICE);
    inRow(IDS.jira, 'account-paste-token')!.click();
    await settle();
    expect(text(overlay().querySelector('h2'))).toBe('Paste a token for Jira');
    expect(text(overlay().querySelector('[data-testid=token-hint]'))).toContain(
      'personal access token of your own Jira account',
    );

    typeInto(overlay().querySelector('#account-token'), 'jira-wrong');
    submitForm(overlay().querySelector('#account-token-form'));
    await settle();
    const refused = http.expectOne({ method: 'PUT', url: `${ACCOUNTS}/${IDS.jira}/token` });
    expect(refused.request.body).toEqual({ token: 'jira-wrong' });
    refused.flush(
      problem(
        400,
        'ACCOUNT_REFUSED',
        'The system refused the token: Jira answered 401 when asked whose the token is',
      ),
      {
        status: 400,
        statusText: 'Bad Request',
      },
    );
    await settle();
    expect(text(overlay().querySelector('[data-testid=token-error]'))).toBe(
      'Jira answered 401 when asked whose the token is.',
    );
    expect(overlay().textContent).not.toContain('jira-wrong');

    typeInto(overlay().querySelector('#account-token'), 'jira-token-alice');
    submitForm(overlay().querySelector('#account-token-form'));
    await settle();
    http
      .expectOne({ method: 'PUT', url: `${ACCOUNTS}/${IDS.jira}/token` })
      .flush(anAccount({ id: 'acc-jira', systemId: IDS.jira, kind: 'TOKEN', login: 'alice' }));
    await settle();
    // The row says the account at once; the list read again says the rest.
    harness.detectChanges();
    expect(text(inRow(IDS.jira, 'account-chip'))).toBe('Your own account');
    http.expectOne(ACCOUNTS).flush({ items: ALICE });
    await settle();
    expect(notify.success).toHaveBeenCalledWith(
      'Jira is connected',
      'Agents act there as you: alice.',
    );
  });

  it('disconnects after asking, and acts through the grant again', async () => {
    const own = aMyAccount({
      system: JIRA,
      account: anAccount({ systemId: IDS.jira, kind: 'TOKEN' }),
      grants: [aGrantChoice()],
    });
    await open([own]);
    inRow(IDS.jira, 'account-disconnect')!.click();
    await settle();
    expect(text(overlay().querySelector('h2'))).toBe('Disconnect your Jira account?');
    expect(text(overlay())).toContain('New ones act through the grant of your group instead.');
    overlay().querySelector<HTMLElement>('[data-testid=confirm-disconnect]')!.click();
    await settle();
    http.expectOne({ method: 'DELETE', url: `${ACCOUNTS}/${IDS.jira}` }).flush(null, {
      status: 204,
      statusText: 'No Content',
    });
    await settle();
    // At once: the grant, before the list read again says so.
    harness.detectChanges();
    expect(text(inRow(IDS.jira, 'account-chip'))).toBe('Via Analysts, read-only');
    http.expectOne(ACCOUNTS).flush({
      items: [{ ...own, account: null, binding: 'GRANTED' }],
    });
    await settle();
    harness.detectChanges();
    expect(text(inRow(IDS.jira, 'account-chip'))).toBe('Via Analysts, read-only');
    expect(notify.success).toHaveBeenCalledWith('Jira is disconnected');
  });

  it('says once that a sign-in connected the account', async () => {
    await open(ALICE, { url: `/c/c1/accounts?account=connected&system=${IDS.github}` });
    const result = el().querySelector('[data-testid=sign-in-result]')!;
    expect(result.getAttribute('role')).toBe('status');
    expect(text(result)).toBe('GitHub is connected. Agents act there as you: alice-novak.');
    expect(TestBed.inject(Router).url).toBe('/c/c1/accounts');

    result.querySelector('button')!.click();
    harness.detectChanges();
    expect(el().querySelector('[data-testid=sign-in-result]')).toBeNull();
  });

  it.each`
    account               | words
    ${'denied'}           | ${'You declined at the system, so nothing was connected.'}
    ${'identity_in_use'}  | ${'already belongs to someone else'}
    ${'site_not_granted'} | ${'Jira site'}
  `('says why a sign-in did not connect: $account', async ({ account, words }) => {
    await open(ALICE, { url: `/c/c1/accounts?account=${account}` });
    const result = el().querySelector('[data-testid=sign-in-result]')!;
    expect(result.getAttribute('role')).toBe('alert');
    expect(text(result)).toContain(words);
  });

  it('says that personal MCP servers are switched off, and offers none', async () => {
    await open(
      [
        aMyAccount({
          system: CRM,
          account: anAccount({ systemId: IDS.crm, kind: 'TOKEN' }),
          binding: 'UNAVAILABLE',
          oauthAvailable: false,
        }),
      ],
      { personalMcpServers: false },
    );
    expect(el().querySelector('[data-testid=add-mcp-server]')).toBeNull();
    expect(text(el().querySelector('[data-testid=personal-off]'))).toContain(
      'Personal MCP servers are switched off in this company.',
    );
    expect(text(inRow(IDS.crm, 'account-chip'))).toBe('Switched off');
    expect(inRow(IDS.crm, 'account-paste-token')).toBeNull();
    expect(inRow(IDS.crm, 'account-disconnect')).not.toBeNull();
  });

  it('says what to do without any system, and points an admin to Systems', async () => {
    await open([], { role: 'ADMIN' });
    expect(text(el().querySelector('[data-testid=systems-empty]'))).toContain('No systems yet');
    expect(el().querySelector('[data-testid=systems-empty] a')?.getAttribute('href')).toBe(
      '/c/c1/systems',
    );
    expect(el().querySelector('[data-testid=personal-empty]')).not.toBeNull();
  });

  it('says why the list could not be read, and reads it again', async () => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([{ path: 'c/:companyId/accounts', component: AccountsSystems }]),
        ...provideTestHttp(),
        fakeCurrentCompany('MEMBER').provider,
        fakeNotify().provider,
      ],
    });
    http = TestBed.inject(HttpTestingController);
    harness = await RouterTestingHarness.create();
    await harness.navigateByUrl('/c/c1/accounts');
    await settle();
    http.expectOne(ACCOUNTS).flush(problem(500), { status: 500, statusText: 'Error' });
    http.expectOne(SETTINGS).flush({ personalMcpServers: true });
    await settle();
    harness.detectChanges();
    expect(text(el())).toContain("Couldn't load your accounts");
    el().querySelector<HTMLElement>('[role=alert] button')!.click();
    await settle();
    http.expectOne(ACCOUNTS).flush({ items: ALICE });
    await settle();
    harness.detectChanges();
    expect(row(IDS.github)).not.toBeNull();
  });

  it('registers one’s own MCP server and opens its page', async () => {
    await open(ALICE);
    el().querySelector<HTMLElement>('[data-testid=add-mcp-server]')!.click();
    await settle();
    expect(text(overlay().querySelector('h2'))).toBe('Add your MCP server');
    typeInto(overlay().querySelector('#mcp-server-name'), 'My notes');
    typeInto(overlay().querySelector('#mcp-server-url'), 'http://127.0.0.1:9000/mcp');
    submitForm(overlay().querySelector('#mcp-server-form'));
    await settle();
    const refused = http.expectOne({ method: 'POST', url: '/api/companies/c1/systems' });
    expect(refused.request.body).toEqual({
      name: 'My notes',
      kind: 'MCP',
      personal: true,
      baseUrl: 'http://127.0.0.1:9000/mcp',
    });
    refused.flush(
      problem(
        400,
        'INVALID_BASE_URL',
        'baseUrl is not usable: the host 127.0.0.1 resolves to a loopback address (127.0.0.1)',
      ),
      { status: 400, statusText: 'Bad Request' },
    );
    await settle();
    expect(text(overlay())).toContain(
      'Tinfirm may not call this address: the host 127.0.0.1 resolves to a loopback address (127.0.0.1).',
    );

    typeInto(overlay().querySelector('#mcp-server-url'), 'https://notes.example.com/mcp');
    typeInto(overlay().querySelector('#mcp-server-header'), 'X-Api-Key');
    submitForm(overlay().querySelector('#mcp-server-form'));
    await settle();
    const created = http.expectOne({ method: 'POST', url: '/api/companies/c1/systems' });
    expect(created.request.body).toEqual({
      name: 'My notes',
      kind: 'MCP',
      personal: true,
      baseUrl: 'https://notes.example.com/mcp',
      authHeader: 'X-Api-Key',
    });
    created.flush(aPersonalMcpServer({ id: 'sys-notes', name: 'My notes' }));
    await settle();
    expect(TestBed.inject(Router).url).toBe('/c/c1/systems/sys-notes');
    expect(notify.success).toHaveBeenCalledWith('My notes is registered', expect.any(String));
  });

  it('checks the server’s fields before asking', async () => {
    await open(ALICE);
    el().querySelector<HTMLElement>('[data-testid=add-mcp-server]')!.click();
    await settle();
    typeInto(overlay().querySelector('#mcp-server-url'), 'notes.example.com');
    typeInto(overlay().querySelector('#mcp-server-header'), 'Bad Header');
    submitForm(overlay().querySelector('#mcp-server-form'));
    await settle();
    expect(text(overlay())).toContain('Name the server.');
    expect(text(overlay())).toContain('Enter the address with its scheme');
    expect(text(overlay())).toContain('Letters, digits and dashes');
    http.expectNone({ method: 'POST', url: '/api/companies/c1/systems' });
  });
});
