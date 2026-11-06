import { HttpTestingController } from '@angular/common/http/testing';
import { type ComponentFixture, TestBed } from '@angular/core/testing';
import type { Role } from '../../../api/generated/models';
import {
  fakeCurrentCompany,
  fakeNotify,
  problem,
  provideTestHttp,
  settle,
} from '../../../testing/test-helpers';
import { words } from '../../../testing/words';
import { PersonalMcpSetting } from './personal-mcp-setting';

const URL = '/api/companies/c1/system-settings';

describe('PersonalMcpSetting', () => {
  let fixture: ComponentFixture<PersonalMcpSetting>;
  let http: HttpTestingController;
  let el: HTMLElement;
  let notify: ReturnType<typeof fakeNotify>['notify'];

  async function open(on: boolean, role: Role = 'ADMIN') {
    const notifier = fakeNotify();
    notify = notifier.notify;
    TestBed.configureTestingModule({
      imports: [PersonalMcpSetting],
      providers: [...provideTestHttp(), fakeCurrentCompany(role).provider, notifier.provider],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(PersonalMcpSetting);
    el = fixture.nativeElement;
    await settle();
    http.expectOne({ method: 'GET', url: URL }).flush({ personalMcpServers: on });
    await stable();
  }

  async function stable() {
    await settle();
    await fixture.whenStable();
  }

  const toggle = () => el.querySelector<HTMLButtonElement>('#personal-mcp-servers')!;
  const state = () => words(el.querySelector('[data-testid=personal-mcp-state]'));
  const overlay = () => document.querySelector('.cdk-overlay-container') as HTMLElement;
  const button = (label: string) =>
    Array.from(document.querySelectorAll('button')).find((b) => b.textContent?.trim() === label);

  afterEach(() => {
    http.verify();
    overlay()?.replaceChildren();
  });

  it.each<[boolean, string, string]>([
    [true, 'true', 'On'],
    [false, 'false', 'Off'],
  ])('reads the switch when it is %s', async (on, checked, label) => {
    await open(on);
    expect(toggle().getAttribute('aria-checked')).toBe(checked);
    expect(toggle().getAttribute('aria-label')).toBe('Members’ own MCP servers');
    expect(state()).toBe(label);
  });

  it('turns it on at once', async () => {
    await open(false);
    toggle().click();
    await settle();
    const req = http.expectOne({ method: 'PUT', url: URL });
    expect(req.request.body).toEqual({ personalMcpServers: true });
    req.flush({ personalMcpServers: true });
    await stable();
    expect(toggle().getAttribute('aria-checked')).toBe('true');
    expect(notify.success).toHaveBeenCalledWith('Members’ own MCP servers are on');
  });

  it('asks before it turns it off, and says there what off does', async () => {
    await open(true);
    toggle().click();
    await stable();
    http.expectNone({ method: 'PUT', url: URL });
    expect(overlay().textContent).toContain('Turn off members’ own MCP servers?');
    expect(overlay().textContent).toContain('The servers members registered stay');

    button('Turn off')!.click();
    await settle();
    const req = http.expectOne({ method: 'PUT', url: URL });
    expect(req.request.body).toEqual({ personalMcpServers: false });
    req.flush({ personalMcpServers: false });
    await stable();
    expect(toggle().getAttribute('aria-checked')).toBe('false');
    expect(state()).toBe('Off');
    expect(notify.success).toHaveBeenCalledWith('Members’ own MCP servers are off');
  });

  it('leaves it on when the question is cancelled', async () => {
    await open(true);
    toggle().click();
    await stable();
    button('Cancel')!.click();
    await stable();
    expect(toggle().getAttribute('aria-checked')).toBe('true');
    http.expectNone({ method: 'PUT', url: URL });
  });

  it('puts the switch back and says why when the server refuses', async () => {
    await open(false);
    toggle().click();
    await settle();
    http
      .expectOne({ method: 'PUT', url: URL })
      .flush(problem(403), { status: 403, statusText: 'Forbidden' });
    await stable();
    expect(toggle().getAttribute('aria-checked')).toBe('false');
    expect(words(el.querySelector('[data-testid=personal-mcp-error]'))).toBe(
      'Only owners and admins change this setting.',
    );
  });
});
