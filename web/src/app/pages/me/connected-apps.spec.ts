import { HttpTestingController } from '@angular/common/http/testing';
import { type ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import type { OAuthGrant } from '../../api/generated/models';
import { fakeNotify, problem, provideTestHttp, settle } from '../../testing/test-helpers';
import { ConnectedApps, grantLevel } from './connected-apps';

const CLAUDE: OAuthGrant = {
  id: 'g1',
  client: {
    clientId: 'https://claude.ai/oauth/claude-code-client-metadata',
    name: 'Claude Code',
    domain: 'claude.ai',
  },
  companyId: 'c1',
  companyName: 'Acme',
  scopes: ['tinfirm:read', 'tinfirm:write'],
  grantedAt: '2026-10-07T08:00:00Z',
  lastUsedAt: '2026-10-07T09:00:00Z',
};
const INSPECTOR: OAuthGrant = {
  id: 'g2',
  client: { clientId: 'mcp-inspector', name: 'MCP Inspector' },
  companyId: 'c2',
  companyName: 'Globex',
  scopes: ['tinfirm:read'],
  grantedAt: '2026-10-06T08:00:00Z',
  lastUsedAt: null,
};

describe('grantLevel', () => {
  it('names the highest scope', () => {
    expect(grantLevel(CLAUDE)).toBe('Write');
    expect(grantLevel(INSPECTOR)).toBe('Read');
    expect(grantLevel({ ...CLAUDE, scopes: ['tinfirm:read', 'tinfirm:decide'] })).toBe('Decide');
  });
});

describe('ConnectedApps', () => {
  let fixture: ComponentFixture<ConnectedApps>;
  let http: HttpTestingController;
  let notify: ReturnType<typeof fakeNotify>['notify'];
  let el: HTMLElement;

  const row = (id: string) => el.querySelector(`[data-testid=grant-${id}]`) as HTMLElement;
  const text = (node: Element | null) => node?.textContent?.replace(/\s+/g, ' ').trim();

  async function create(grants: OAuthGrant[] = [CLAUDE, INSPECTOR]) {
    const fake = fakeNotify();
    notify = fake.notify;
    TestBed.configureTestingModule({
      imports: [ConnectedApps],
      providers: [provideRouter([]), ...provideTestHttp(), fake.provider],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ConnectedApps);
    el = fixture.nativeElement as HTMLElement;
    await settle();
    http.expectOne({ method: 'GET', url: '/api/me/oauth/grants' }).flush(grants);
    await settle();
    await fixture.whenStable();
  }

  afterEach(() => {
    http.verify();
    document.querySelector('.cdk-overlay-container')?.replaceChildren();
  });

  it('lists each app with its domain, company, level and last use', async () => {
    await create();
    expect(text(row('g1'))).toContain('Claude Code');
    expect(text(row('g1'))).toContain('claude.ai');
    expect(text(row('g1').querySelector('[data-testid=grant-level]'))).toBe('Write');
    expect(text(row('g1').querySelector('[data-testid=grant-meta]'))).toContain('In Acme');
    expect(text(row('g1').querySelector('[data-testid=grant-meta]'))).toContain('last used');
    expect(text(row('g2').querySelector('[data-testid=grant-meta]'))).toContain('never used');
  });

  it('shows an empty state', async () => {
    await create([]);
    expect(el.querySelector('[data-testid=no-apps]')).not.toBeNull();
  });

  it('disconnects an app after confirmation', async () => {
    await create();
    el.querySelector<HTMLButtonElement>(
      'button[aria-label="Disconnect Claude Code from Acme"]',
    )!.click();
    await settle();
    await fixture.whenStable();
    const overlay = document.querySelector('.cdk-overlay-container')!;
    expect(overlay.textContent).toContain('Disconnect Claude Code from Acme?');
    http.expectNone({ method: 'DELETE' });
    Array.from(overlay.querySelectorAll('button'))
      .find((b) => b.textContent?.trim() === 'Disconnect')!
      .click();
    await settle();
    http
      .expectOne({ method: 'DELETE', url: '/api/me/oauth/grants/g1' })
      .flush(null, { status: 204, statusText: 'No Content' });
    await settle();
    await fixture.whenStable();
    expect(row('g1')).toBeNull();
    expect(row('g2')).not.toBeNull();
    expect(notify.success).toHaveBeenCalledWith(
      'Claude Code disconnected',
      'It no longer works in Acme.',
    );
  });

  it('shows a failed load with a retry', async () => {
    TestBed.configureTestingModule({
      imports: [ConnectedApps],
      providers: [provideRouter([]), ...provideTestHttp(), fakeNotify().provider],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ConnectedApps);
    el = fixture.nativeElement as HTMLElement;
    await settle();
    http
      .expectOne('/api/me/oauth/grants')
      .flush(problem(500), { status: 500, statusText: 'Server Error' });
    await settle();
    await fixture.whenStable();
    expect(el.textContent).toContain("Couldn't load your connected apps");
  });
});
