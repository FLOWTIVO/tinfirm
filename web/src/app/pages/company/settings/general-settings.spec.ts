import { HttpTestingController } from '@angular/common/http/testing';
import { type ComponentFixture, TestBed } from '@angular/core/testing';
import type { Company, Role } from '../../../api/generated/models';
import { Session } from '../../../core/session';
import {
  fakeCurrentCompany,
  fakeNotify,
  provideTestHttp,
  settle,
  typeInto,
} from '../../../testing/test-helpers';
import { words } from '../../../testing/words';
import { GeneralSettings } from './general-settings';

const COMPANY = {
  id: 'c1',
  name: 'Acme',
  createdAt: '2026-09-14T10:42:00Z',
} as Company;

describe('GeneralSettings', () => {
  let fixture: ComponentFixture<GeneralSettings>;
  let http: HttpTestingController;
  let el: HTMLElement;
  let notify: ReturnType<typeof fakeNotify>['notify'];
  let current: ReturnType<typeof fakeCurrentCompany>;
  const refresh = vi.fn(() => Promise.resolve(null));

  async function open(role: Role = 'ADMIN') {
    current = fakeCurrentCompany(role);
    Object.assign(current.fake.company, { hasValue: () => true, value: () => COMPANY });
    const notifier = fakeNotify();
    notify = notifier.notify;
    TestBed.configureTestingModule({
      imports: [GeneralSettings],
      providers: [
        ...provideTestHttp(),
        current.provider,
        notifier.provider,
        { provide: Session, useValue: { refresh } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(GeneralSettings);
    el = fixture.nativeElement;
    await settle();
    // Members' own MCP servers are a setting of this section too.
    http.expectOne('/api/companies/c1/system-settings').flush({ personalMcpServers: true });
    // And whether a trigger may pay with its author's subscription (ADR-0085).
    http.expectOne('/api/companies/c1/settings').flush({
      triggerFiringsPerHour: { default: 30, max: 300 },
      triggerSubscriptions: false,
      version: 1,
    });
    // And the Gmail add-on's deployment file (ADR-0083).
    http.expectOne('/api/companies/c1/gmail/deployment').flush({
      fileName: 'tinfirm-gmail-deployment.json',
      deployment: '{}',
      configured: true,
    });
    await stable();
  }

  async function stable() {
    await settle();
    await fixture.whenStable();
  }

  const name = () => el.querySelector<HTMLInputElement>('#company-name')!;
  const bar = () => el.querySelector('app-unsaved-changes-bar');
  const button = (label: string) =>
    Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.trim() === label);

  afterEach(() => http.verify());

  it('shows the name, the ID and when the company was made, and nothing to save', async () => {
    await open();
    expect(name().value).toBe('Acme');
    expect(words(el.querySelector('[data-testid=company-id]'))).toBe('c1');
    expect(el.textContent).toContain('2026');
    expect(bar()).toBeNull();
  });

  it('renames the company from the unsaved-changes bar', async () => {
    await open();
    typeInto(name(), 'Acme Labs');
    await stable();
    expect(words(el.querySelector('[data-testid=unsaved-count]'))).toBe('1 unsaved change');
    expect(words(el.querySelector('[data-testid=unsaved-names]'))).toBe('Name');

    el.querySelector<HTMLButtonElement>('[data-testid=save-changes]')!.click();
    await settle();
    const req = http.expectOne({ method: 'PATCH', url: '/api/companies/c1' });
    expect(req.request.body).toEqual({ name: 'Acme Labs' });
    req.flush({ ...COMPANY, name: 'Acme Labs' });
    await stable();

    expect(current.fake.company.set).toHaveBeenCalledWith({ ...COMPANY, name: 'Acme Labs' });
    expect(notify.success).toHaveBeenCalledWith('Company renamed', "It's now called Acme Labs.");
    expect(refresh).toHaveBeenCalled();
  });

  it('puts the name back on Discard', async () => {
    await open();
    typeInto(name(), 'Acme Labs');
    await stable();
    button('Discard')!.click();
    await stable();
    expect(name().value).toBe('Acme');
    expect(bar()).toBeNull();
  });

  it('refuses an empty name before it asks the server', async () => {
    await open();
    typeInto(name(), '');
    await stable();
    el.querySelector<HTMLButtonElement>('[data-testid=save-changes]')!.click();
    await stable();
    expect(el.textContent).toContain('Enter a name.');
    http.expectNone({ method: 'PATCH', url: '/api/companies/c1' });
  });
});
