import { HttpTestingController } from '@angular/common/http/testing';
import { type ComponentFixture, TestBed } from '@angular/core/testing';
import type { ExternalSystem } from '../../../api/generated/models';
import {
  aJiraSystem,
  aPersonalMcpServer,
  aSlackSystem,
  aSystem,
  IDS,
} from '../../../testing/fixtures';
import {
  fakeNotify,
  problem,
  provideTestHttp,
  settle,
  submitForm,
  typeInto,
} from '../../../testing/test-helpers';
import { appRequest, SystemApp } from './system-app';

describe('appRequest', () => {
  it.each`
    model                                                          | body
    ${{ clientId: ' Iv1 ', clientSecret: 's3', scopes: ' repo ' }} | ${{ clientId: 'Iv1', clientSecret: 's3', scopes: 'repo' }}
    ${{ clientId: 'Iv1', clientSecret: '', scopes: '' }}           | ${{ clientId: 'Iv1' }}
    ${{ clientId: '', clientSecret: '', scopes: '' }}              | ${{}}
  `('sends $body', ({ model, body }) => {
    expect(appRequest(model)).toEqual(body);
  });

  it('confirms new endpoints only when asked to', () => {
    expect(appRequest({ clientId: '', clientSecret: '', scopes: '' }, true)).toEqual({
      confirmEndpointsChange: true,
    });
  });
});

describe('SystemApp', () => {
  let fixture: ComponentFixture<SystemApp>;
  let http: HttpTestingController;
  let changed: ExternalSystem[];

  async function create(system: ExternalSystem, canManage = true) {
    TestBed.configureTestingModule({
      imports: [SystemApp],
      providers: [...provideTestHttp(), fakeNotify().provider],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(SystemApp);
    fixture.componentRef.setInput('companyId', 'c1');
    fixture.componentRef.setInput('system', system);
    fixture.componentRef.setInput('canManage', canManage);
    changed = [];
    fixture.componentInstance.changed.subscribe((s) => changed.push(s));
    await settle();
    fixture.detectChanges();
  }

  const el = () => fixture.nativeElement as HTMLElement;
  const by = (testId: string) => el().querySelector<HTMLElement>(`[data-testid=${testId}]`);
  const overlay = () => document.querySelector('.cdk-overlay-container') as HTMLElement;
  const text = (node: Element | null | undefined) => node?.textContent?.replace(/\s+/g, ' ').trim();

  afterEach(() => {
    http.verify();
    overlay()?.replaceChildren();
  });

  it.each`
    case                    | system                                                                    | state                                                       | callback
    ${'with its app'}       | ${aSystem()}                                                              | ${'Accounts on GitHub connect by signing in with this app'} | ${'https://tinfirm.acme.test/api/oauth/callback'}
    ${'without an app yet'} | ${aJiraSystem({ oauthCallbackUrl: 'https://t.test/api/oauth/callback' })} | ${'No app yet: accounts on Jira connect by token only.'}    | ${'https://t.test/api/oauth/callback'}
  `(
    'says how accounts connect $case, with the callback URL to copy',
    async ({ system, state, callback }) => {
      await create(system);
      expect(text(by('app-state'))).toContain(state);
      expect(text(by('app-callback'))).toBe(callback);
      expect(!!by('app-client-id')).toBe(!!system.app);
      expect(!!by('set-app')).toBe(!system.app);
    },
  );

  it('offers no callback URL the server does not give', async () => {
    await create(aJiraSystem({ oauthCallbackUrl: null }));
    expect(by('app-callback')).toBeNull();
    expect(by('set-app')).not.toBeNull();
  });

  it('shows the app’s client, never its secret', async () => {
    await create(
      aSystem({
        app: {
          clientId: 'Iv1.acme',
          clientSecretSet: true,
          callbackUrl: 'https://t/cb',
          scopes: 'repo',
        },
      }),
    );
    expect(text(by('app-client-id'))).toContain('Iv1.acme');
    expect(text(by('app-client-secret'))).toContain('Stored');
    expect(text(by('app-scopes'))).toContain('repo');
  });

  it('shows someone who cannot manage it only whether there is an app', async () => {
    await create(aSystem({ app: null, oauthAvailable: true }), false);
    expect(text(by('app-state'))).toContain('connect by signing in');
    expect(by('app-callback')).toBeNull();
    expect(by('set-app')).toBeNull();
  });

  it('sets a tracker’s app, which needs a client ID', async () => {
    await create(aJiraSystem());
    by('set-app')!.click();
    await settle();
    submitForm(overlay().querySelector('#app-form'));
    await settle();
    expect(text(overlay())).toContain('Enter the client ID the system gave the app.');
    http.expectNone('/api/companies/c1/systems/sys-jira/app');

    typeInto(overlay().querySelector('#app-client-id'), 'jira-client');
    typeInto(overlay().querySelector('#app-client-secret'), 'jira-secret');
    submitForm(overlay().querySelector('#app-form'));
    await settle();
    const req = http.expectOne('/api/companies/c1/systems/sys-jira/app');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ clientId: 'jira-client', clientSecret: 'jira-secret' });
    const saved = aJiraSystem({
      oauthAvailable: true,
      app: { clientId: 'jira-client', clientSecretSet: true, callbackUrl: 'https://t/cb' },
    });
    req.flush(saved);
    await settle();
    expect(changed).toEqual([saved]);
  });

  it('says an MCP server without an app was not set up, and how to look again', async () => {
    await create(aPersonalMcpServer({ personal: false }));
    expect(text(by('app-how'))).toContain('None is set up yet');
    expect(text(by('app-how'))).toContain('set up the app with the client ID empty to look again');
  });

  it('sets an MCP server’s app without a client ID, and says why the server offers none', async () => {
    await create(aPersonalMcpServer({ personal: false }));
    by('set-app')!.click();
    await settle();
    submitForm(overlay().querySelector('#app-form'));
    await settle();
    const req = http.expectOne('/api/companies/c1/systems/sys-crm/app');
    expect(req.request.body).toEqual({});
    req.flush(
      problem(
        409,
        'OAUTH_NOT_AVAILABLE',
        'The server answers without a token, so it offers no OAuth',
      ),
      { status: 409, statusText: 'Conflict' },
    );
    await settle();
    expect(text(overlay().querySelector('[data-testid=app-form-error]'))).toBe(
      'The server answers without a token, so it offers no OAuth',
    );
    expect(changed).toEqual([]);
  });

  it('saves an MCP server’s new endpoints only once the person confirms what they remove', async () => {
    await create(aPersonalMcpServer({ personal: false }));
    by('set-app')!.click();
    await settle();
    submitForm(overlay().querySelector('#app-form'));
    await settle();
    const first = http.expectOne('/api/companies/c1/systems/sys-crm/app');
    expect(first.request.body).toEqual({});
    const detail =
      'CRM’s sign-in now names another token endpoint: the 2 accounts and credentials that signed in would send their tokens there. Save again to remove them and set the app, or check the server’s metadata first.';
    first.flush(problem(409, 'SYSTEM_APP_ENDPOINTS_CHANGED', detail), {
      status: 409,
      statusText: 'Conflict',
    });
    await settle();
    expect(text(overlay().querySelector('[data-testid=app-form-error]'))).toBe(detail);
    expect(text(overlay().querySelector('[data-testid=app-save]'))).toBe('Remove them and save');
    expect(changed).toEqual([]);

    submitForm(overlay().querySelector('#app-form'));
    await settle();
    const confirmed = http.expectOne('/api/companies/c1/systems/sys-crm/app');
    expect(confirmed.request.body).toEqual({ confirmEndpointsChange: true });
    const saved = aPersonalMcpServer({ personal: false, oauthAvailable: true });
    confirmed.flush(saved);
    await settle();
    expect(changed).toEqual([saved]);
  });

  it('sets Slack’s app with its secret, its scopes fixed by the manifest', async () => {
    await create(aSlackSystem({ oauthCallbackUrl: 'https://t.test/api/oauth/callback' }));
    expect(text(by('app-state'))).toContain(
      'No app yet: nobody signs in to Acme Slack, so agents cannot read its channels.',
    );
    expect(text(el())).toContain('already has this callback URL');
    by('set-app')!.click();
    await settle();
    expect(overlay().querySelector('#app-scopes')).toBeNull();
    typeInto(overlay().querySelector('#app-client-id'), '1234567890.0987654321');
    submitForm(overlay().querySelector('#app-form'));
    await settle();
    expect(text(overlay())).toContain('Enter the client secret from the Slack app’s');
    http.expectNone(`/api/companies/c1/systems/${IDS.slack}/app`);

    typeInto(overlay().querySelector('#app-client-secret'), 'slack-secret');
    submitForm(overlay().querySelector('#app-form'));
    await settle();
    const req = http.expectOne(`/api/companies/c1/systems/${IDS.slack}/app`);
    expect(req.request.body).toEqual({
      clientId: '1234567890.0987654321',
      clientSecret: 'slack-secret',
    });
    const saved = aSlackSystem({
      oauthAvailable: true,
      app: {
        clientId: '1234567890.0987654321',
        clientSecretSet: true,
        callbackUrl: 'https://t/cb',
      },
    });
    req.flush(saved);
    await settle();
    expect(changed).toEqual([saved]);
  });

  it('shows Slack’s fixed scopes, and keeps a stored secret for the same client', async () => {
    await create(
      aSlackSystem({
        oauthAvailable: true,
        app: {
          clientId: '1234567890.0987654321',
          clientSecretSet: true,
          callbackUrl: 'https://t/cb',
        },
      }),
    );
    expect(text(by('app-state'))).toContain(
      'People, and the company’s credentials, sign in to Acme Slack',
    );
    expect(text(by('app-scopes'))).toContain('channels:history groups:history');
    Array.from(el().querySelectorAll('button'))
      .find((b) => text(b) === 'Change')!
      .click();
    await settle();
    submitForm(overlay().querySelector('#app-form'));
    await settle();
    const req = http.expectOne(`/api/companies/c1/systems/${IDS.slack}/app`);
    expect(req.request.body).toEqual({ clientId: '1234567890.0987654321' });
    req.flush(aSlackSystem({ oauthAvailable: true }));
    await settle();
  });

  it('removes the app after a confirmation', async () => {
    await create(aSystem());
    by('remove-app')!.click();
    await settle();
    expect(text(overlay())).toContain('Nobody signs in with it any more');
    Array.from(overlay().querySelectorAll('button'))
      .find((b) => text(b) === 'Remove the app')!
      .click();
    await settle();
    http
      .expectOne(
        (r) => r.url === '/api/companies/c1/systems/sys-github/app' && r.method === 'DELETE',
      )
      .flush(null);
    await settle();
    expect(changed[0]?.app).toBeNull();
    expect(changed[0]?.oauthAvailable).toBe(false);
  });
});
