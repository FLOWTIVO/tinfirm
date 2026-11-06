import { HttpTestingController } from '@angular/common/http/testing';
import { type ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import type { OAuthConsentRequest } from '../../api/generated/models';
import { BrowserNavigation } from '../../core/browser-navigation';
import { problem, provideTestHttp, settle } from '../../testing/test-helpers';
import { Authorize, defaultLevel, scopesUpTo } from './authorize';

const CLIENT_ID = 'https://claude.ai/oauth/claude-code-client-metadata';

const REQUEST: OAuthConsentRequest = {
  client: { clientId: CLIENT_ID, name: 'Claude Code', domain: 'claude.ai' },
  companyId: 'c1',
  companyName: 'Acme',
  // What Claude Code asks for after the 401: reading only.
  requested: ['tinfirm:read'],
  offered: [
    { scope: 'tinfirm:read', title: 'Read', description: 'Read the inbox.' },
    { scope: 'tinfirm:write', title: 'Write', description: 'Comment and start threads.' },
    { scope: 'tinfirm:decide', title: 'Decide', description: 'Decide decisions.' },
  ],
  ownerNotice: { companyId: 'c1', companyName: 'Acme', owners: ['Ann Owner', 'Bob Owner'] },
};

describe('consent levels', () => {
  it('preselects the requested level and never deciding', () => {
    expect(defaultLevel(['tinfirm:read'])).toBe('tinfirm:read');
    expect(defaultLevel(['tinfirm:write'])).toBe('tinfirm:write');
    expect(defaultLevel(['tinfirm:read', 'tinfirm:write', 'tinfirm:decide'])).toBe('tinfirm:write');
    expect(defaultLevel(['tinfirm:decide'])).toBe('tinfirm:write');
    expect(defaultLevel([])).toBe('tinfirm:read');
  });

  it('posts every scope up to the chosen level', () => {
    expect(scopesUpTo('tinfirm:read')).toEqual(['tinfirm:read']);
    expect(scopesUpTo('tinfirm:write')).toEqual(['tinfirm:read', 'tinfirm:write']);
    expect(scopesUpTo('tinfirm:decide')).toEqual([
      'tinfirm:read',
      'tinfirm:write',
      'tinfirm:decide',
    ]);
  });
});

describe('Authorize', () => {
  let fixture: ComponentFixture<Authorize>;
  let http: HttpTestingController;
  let el: HTMLElement;
  const navigation = { assign: vi.fn() };

  const text = (selector: string) =>
    el.querySelector(selector)?.textContent?.replace(/\s+/g, ' ').trim();
  const button = (label: string) =>
    Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.trim() === label)!;

  async function create(request: OAuthConsentRequest | null = REQUEST) {
    TestBed.configureTestingModule({
      imports: [Authorize],
      providers: [
        provideRouter([]),
        ...provideTestHttp(),
        { provide: BrowserNavigation, useValue: navigation },
        {
          provide: ActivatedRoute,
          useValue: {
            queryParamMap: of(
              convertToParamMap({
                client_id: CLIENT_ID,
                scope: 'tinfirm:read tinfirm:write tinfirm:decide',
                state: 'consent-state',
              }),
            ),
          },
        },
      ],
    });
    navigation.assign.mockClear();
    http = TestBed.inject(HttpTestingController);
    document.cookie = 'XSRF-TOKEN=test-xsrf-token; path=/';
    fixture = TestBed.createComponent(Authorize);
    el = fixture.nativeElement as HTMLElement;
    await settle();
    const req = http.expectOne((r) => r.method === 'GET' && r.url === '/api/me/oauth/consent');
    expect(req.request.params.get('clientId')).toBe(CLIENT_ID);
    expect(req.request.params.get('state')).toBe('consent-state');
    if (request) {
      req.flush(request);
    } else {
      req.flush(problem(404), { status: 404, statusText: 'Not Found' });
    }
    await settle();
    await fixture.whenStable();
  }

  afterEach(() => {
    http.verify();
    document.cookie = 'XSRF-TOKEN=; path=/; max-age=0';
  });

  const input = (scope: string) =>
    el.querySelector<HTMLInputElement>(`[data-testid="level-${scope}"] input`)!;

  it('names the app, the domain of its metadata document, the company and the levels', async () => {
    await create();
    expect(text('[data-testid=consent-title]')).toBe('Allow Claude Code to use Tinfirm as you?');
    expect(text('[data-testid=consent-domain]')).toBe('claude.ai');
    expect(text('[data-testid=consent-company]')).toBe('Acme');
    expect(text('[data-testid="level-tinfirm:write"]')).toContain('Read and write');
    expect(text('[data-testid="level-tinfirm:decide"]')).toContain('Read, write and decide');
  });

  it("shows the Owner's notice above the levels", async () => {
    await create();
    expect(text('[data-testid=owner-notice]')).toContain(
      'Conversations you start through Claude Code are personal: agents act with your own accounts and budget.',
    );
    expect(text('[data-testid=owner-notice]')).toContain(
      'The Owners of Acme (Ann Owner and Bob Owner) can read them in full; each read is recorded and shown to you.',
    );
    const notice = el.querySelector('[data-testid=owner-notice]')!;
    const levels = el.querySelector('fieldset')!;
    expect(notice.compareDocumentPosition(levels) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it('offers the three levels for a read request, with Read preselected', async () => {
    await create();
    expect(el.querySelectorAll('input[type=radio]')).toHaveLength(3);
    expect(text('[data-testid="level-tinfirm:read"]')).toContain('Read');
    expect(text('[data-testid="level-tinfirm:read"]')).toContain('Read the inbox.');
    expect(input('tinfirm:read').checked).toBe(true);
    expect(input('tinfirm:write').checked).toBe(false);
    expect(input('tinfirm:decide').checked).toBe(false);
    expect(el.querySelector('[data-testid=decide-note]')).toBeNull();
  });

  it('preselects write for a client that asks for it, and never deciding', async () => {
    await create({ ...REQUEST, requested: ['tinfirm:read', 'tinfirm:write', 'tinfirm:decide'] });
    expect(el.querySelectorAll('input[type=radio]')).toHaveLength(3);
    expect(input('tinfirm:write').checked).toBe(true);
    expect(input('tinfirm:decide').checked).toBe(false);
    expect(el.querySelector('[data-testid=decide-note]')).toBeNull();
  });

  it('lets the person widen a read request to write', async () => {
    await create();
    input('tinfirm:write').click();
    await settle();
    button('Allow').click();
    await settle();
    const post = http.expectOne({ method: 'POST', url: '/oauth2/authorize' });
    expect(post.request.body).toBe(
      'client_id=https://claude.ai/oauth/claude-code-client-metadata&state=consent-state' +
        '&scope=tinfirm:read&scope=tinfirm:write',
    );
    post.flush({ redirectUri: 'http://localhost:5000/callback?code=c&state=s&iss=x' });
    await settle();
  });

  it('posts reading only when the person keeps the preselected level', async () => {
    await create();
    button('Allow').click();
    await settle();
    const post = http.expectOne({ method: 'POST', url: '/oauth2/authorize' });
    expect(post.request.body).toBe(
      'client_id=https://claude.ai/oauth/claude-code-client-metadata&state=consent-state' +
        '&scope=tinfirm:read',
    );
    post.flush({ redirectUri: 'http://localhost:5000/callback?code=c&state=s&iss=x' });
    await settle();
  });

  it('posts the allowed scopes with the CSRF header and follows the answer to the app', async () => {
    await create();
    el.querySelector<HTMLInputElement>('[data-testid="level-tinfirm:decide"] input')!.click();
    await settle();
    expect(el.querySelector('[data-testid=decide-note]')).not.toBeNull();

    button('Allow').click();
    await settle();
    const post = http.expectOne({ method: 'POST', url: '/oauth2/authorize' });
    expect(post.request.headers.get('X-XSRF-TOKEN')).toBe('test-xsrf-token');
    expect(post.request.headers.get('Content-Type')).toBe('application/x-www-form-urlencoded');
    expect(post.request.headers.get('Accept')).toBe('application/json');
    expect(post.request.body).toBe(
      'client_id=https://claude.ai/oauth/claude-code-client-metadata&state=consent-state' +
        '&scope=tinfirm:read&scope=tinfirm:write&scope=tinfirm:decide',
    );
    post.flush({ redirectUri: 'http://localhost:5000/callback?code=c&state=s&iss=x' });
    await settle();
    expect(navigation.assign).toHaveBeenCalledWith(
      'http://localhost:5000/callback?code=c&state=s&iss=x',
    );
  });

  it('denies without any scope', async () => {
    await create();
    button('Deny').click();
    await settle();
    const post = http.expectOne({ method: 'POST', url: '/oauth2/authorize' });
    expect(post.request.body).toBe(
      'client_id=https://claude.ai/oauth/claude-code-client-metadata&state=consent-state',
    );
    post.flush({ redirectUri: 'http://localhost:5000/callback?error=access_denied' });
    await settle();
    expect(navigation.assign).toHaveBeenCalledWith(
      'http://localhost:5000/callback?error=access_denied',
    );
  });

  it('explains a request that is no longer valid', async () => {
    await create(null);
    expect(el.querySelector('[data-testid=consent-error]')).not.toBeNull();
    expect(el.querySelector('button')).toBeNull();
  });

  it('shows a failed answer and lets the person try again', async () => {
    await create();
    button('Allow').click();
    await settle();
    http
      .expectOne({ method: 'POST', url: '/oauth2/authorize' })
      .flush(problem(403), { status: 403, statusText: 'Forbidden' });
    await settle();
    expect(text('[data-testid=submit-error]')).toContain('The page expired');
    expect(navigation.assign).not.toHaveBeenCalled();
    expect(button('Allow').disabled).toBe(false);
  });

  it('marks a client built into Tinfirm', async () => {
    await create({
      ...REQUEST,
      client: { clientId: 'mcp-inspector', name: 'MCP Inspector', domain: null },
    });
    expect(text('[data-testid=consent-client]')).toBe('MCP Inspector (built into Tinfirm)');
    expect(
      el.querySelector<HTMLInputElement>('[data-testid="level-tinfirm:read"] input')!.checked,
    ).toBe(true);
  });
});
