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
import { TriggerSubscriptionsSetting } from './trigger-subscriptions-setting';

const URL = '/api/companies/c1/settings';
const LIMITS = { default: 30, max: 300 };

describe('TriggerSubscriptionsSetting', () => {
  let fixture: ComponentFixture<TriggerSubscriptionsSetting>;
  let http: HttpTestingController;
  let el: HTMLElement;
  let notify: ReturnType<typeof fakeNotify>['notify'];

  async function open(on: boolean, role: Role = 'ADMIN') {
    const notifier = fakeNotify();
    notify = notifier.notify;
    TestBed.configureTestingModule({
      imports: [TriggerSubscriptionsSetting],
      providers: [...provideTestHttp(), fakeCurrentCompany(role).provider, notifier.provider],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(TriggerSubscriptionsSetting);
    el = fixture.nativeElement;
    await settle();
    http
      .expectOne({ method: 'GET', url: URL })
      .flush({ triggerFiringsPerHour: LIMITS, triggerSubscriptions: on, version: 1 });
    await stable();
  }

  async function stable() {
    await settle();
    await fixture.whenStable();
  }

  const toggle = () => el.querySelector<HTMLButtonElement>('#trigger-subscriptions-switch')!;
  const state = () => words(el.querySelector('[data-testid=trigger-subscriptions-state]'));
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
    expect(toggle().getAttribute('aria-label')).toBe(
      'Triggers paid with their author’s subscription',
    );
    expect(state()).toBe(label);
  });

  /** The save reads the settings again first, so the limits and version it sends are the latest. */
  async function freshLimits(limits = LIMITS, version = 1) {
    await settle();
    http
      .expectOne({ method: 'GET', url: URL })
      .flush({ triggerFiringsPerHour: limits, triggerSubscriptions: false, version });
    await settle();
  }

  it('asks before it turns it on, says what the company takes on, and sends the latest limits', async () => {
    await open(false);
    toggle().click();
    await stable();
    http.expectNone({ method: 'PUT', url: URL });
    expect(overlay().textContent).toContain('Let triggers pay with their author’s subscription?');
    expect(overlay().textContent).toContain('the company’s call');

    button('Turn on')!.click();
    // Another admin lowered the limits meanwhile: the save sends those and their version, not
    // the ones first read.
    const latest = { default: 10, max: 50 };
    await freshLimits(latest, 2);
    const req = http.expectOne({ method: 'PUT', url: URL });
    expect(req.request.body).toEqual({
      triggerFiringsPerHour: latest,
      triggerSubscriptions: true,
      version: 2,
    });
    req.flush({ triggerFiringsPerHour: latest, triggerSubscriptions: true, version: 3 });
    await stable();
    expect(toggle().getAttribute('aria-checked')).toBe('true');
    expect(state()).toBe('On');
    expect(notify.success).toHaveBeenCalledWith(
      'Triggers may pay with their author’s subscription',
    );
  });

  it('turns it off at once', async () => {
    await open(true);
    toggle().click();
    await freshLimits();
    const req = http.expectOne({ method: 'PUT', url: URL });
    expect(req.request.body).toEqual({
      triggerFiringsPerHour: LIMITS,
      triggerSubscriptions: false,
      version: 1,
    });
    req.flush({ triggerFiringsPerHour: LIMITS, triggerSubscriptions: false, version: 2 });
    await stable();
    expect(toggle().getAttribute('aria-checked')).toBe('false');
    expect(notify.success).toHaveBeenCalledWith('Triggers pay with a budget');
  });

  it('reads the setting again when someone else saved first, and says so', async () => {
    await open(true);
    toggle().click();
    await freshLimits();
    http
      .expectOne({ method: 'PUT', url: URL })
      .flush(problem(409, 'SETTINGS_CHANGED'), { status: 409, statusText: 'Conflict' });
    await settle();
    // The other admin turned it off already: the switch shows that.
    http
      .expectOne({ method: 'GET', url: URL })
      .flush({ triggerFiringsPerHour: LIMITS, triggerSubscriptions: false, version: 2 });
    await stable();
    expect(toggle().getAttribute('aria-checked')).toBe('false');
    expect(words(el.querySelector('[data-testid=trigger-subscriptions-error]'))).toContain(
      'Someone changed these settings meanwhile.',
    );
    expect(notify.success).not.toHaveBeenCalled();
  });

  it('leaves it off when the question is cancelled', async () => {
    await open(false);
    toggle().click();
    await stable();
    button('Cancel')!.click();
    await stable();
    expect(toggle().getAttribute('aria-checked')).toBe('false');
    http.expectNone({ method: 'PUT', url: URL });
  });

  it('puts the switch back and says why when the server refuses', async () => {
    await open(true);
    toggle().click();
    await freshLimits();
    http
      .expectOne({ method: 'PUT', url: URL })
      .flush(problem(403), { status: 403, statusText: 'Forbidden' });
    await stable();
    expect(toggle().getAttribute('aria-checked')).toBe('true');
    expect(words(el.querySelector('[data-testid=trigger-subscriptions-error]'))).toBe(
      'Only owners and admins change this setting.',
    );
  });

  it('is read-only for a member', async () => {
    await open(false, 'MEMBER');
    expect(
      toggle().hasAttribute('disabled') || toggle().getAttribute('aria-disabled') === 'true',
    ).toBe(true);
  });
});
