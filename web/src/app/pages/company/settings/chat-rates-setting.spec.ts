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
import { ChatRatesSetting, rateError } from './chat-rates-setting';

const CHAT = '/api/companies/c1/chat-settings';
const COMPANY = '/api/companies/c1/settings';
const MODEL = {
  perMinute: '20',
  perDay: '200',
  eventsPerMinute: '600',
  webhookDefault: '60',
  webhookMax: '600',
};
const CHAT_SAVED = {
  messagesPerUserPerMinute: 20,
  messagesPerUserPerDay: 200,
  eventsPerSystemPerMinute: 600,
  version: 3,
};
const COMPANY_SAVED = {
  triggerFiringsPerHour: { default: 10, max: 100 },
  webhookDeliveriesPerMinute: { default: 60, max: 600 },
  version: 5,
};

describe('rateError', () => {
  it.each`
    key                  | value    | error
    ${'perMinute'}       | ${'20'}  | ${null}
    ${'perMinute'}       | ${'601'} | ${'A whole number from 1 to 600.'}
    ${'perDay'}          | ${'0'}   | ${'A whole number from 1 to 10,000.'}
    ${'eventsPerMinute'} | ${'1.5'} | ${'A whole number from 1 to 6,000.'}
    ${'webhookDefault'}  | ${''}    | ${'A whole number from 1 to 6,000.'}
    ${'webhookDefault'}  | ${'700'} | ${'At most the maximum.'}
  `('$key $value: $error', ({ key, value, error }) => {
    expect(rateError({ ...MODEL, [key]: value }, key)).toBe(error);
  });
});

describe('ChatRatesSetting', () => {
  let fixture: ComponentFixture<ChatRatesSetting>;
  let http: HttpTestingController;
  let notify: ReturnType<typeof fakeNotify>['notify'];

  const el = () => fixture.nativeElement as HTMLElement;
  const field = (key: string) => el().querySelector<HTMLInputElement>(`#rate-${key}`)!;
  const save = () => el().querySelector<HTMLButtonElement>('[data-testid=chat-rates-save]');

  async function create(role: Role = 'ADMIN') {
    const notifier = fakeNotify();
    notify = notifier.notify;
    TestBed.configureTestingModule({
      providers: [...provideTestHttp(), fakeCurrentCompany(role).provider, notifier.provider],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ChatRatesSetting);
    fixture.detectChanges();
    await settle();
    http.expectOne(CHAT).flush(CHAT_SAVED);
    http.expectOne(COMPANY).flush(COMPANY_SAVED);
    await settle();
    fixture.detectChanges();
  }

  function type(key: string, value: string) {
    field(key).value = value;
    field(key).dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  afterEach(() => http.verify());

  it('shows the limits, and saves only what changed', async () => {
    await create();
    expect(field('perMinute').value).toBe('20');
    expect(field('webhookMax').value).toBe('600');
    expect(save()).toBeNull();
    type('perMinute', '30');
    await settle();
    fixture.detectChanges();
    expect(words(el().querySelector('[data-testid=unsaved-names]'))).toBe(
      'Messages a minute, per chat user',
    );
    expect(words(el().querySelector('[data-testid=rate-was]'))).toBe('was 20');
    save()!.click();
    await settle();
    const req = http.expectOne({ method: 'PUT', url: CHAT });
    expect(req.request.body).toEqual({
      messagesPerUserPerMinute: 30,
      messagesPerUserPerDay: 200,
      eventsPerSystemPerMinute: 600,
      version: 3,
    });
    req.flush({ ...req.request.body, version: 4 });
    await settle();
    http.expectNone({ method: 'PUT', url: COMPANY });
    expect(notify.success).toHaveBeenCalledWith('The limits are saved');

    // The next save carries the version the answer brought.
    type('perMinute', '40');
    await settle();
    fixture.detectChanges();
    save()!.click();
    await settle();
    const again = http.expectOne({ method: 'PUT', url: CHAT });
    expect(again.request.body.version).toBe(4);
    again.flush({ ...again.request.body, version: 5 });
    await settle();
  });

  it('saves the webhooks’ limits with the triggers’ as they are', async () => {
    await create();
    type('webhookDefault', '30');
    type('webhookMax', '300');
    save()!.click();
    await settle();
    const req = http.expectOne({ method: 'PUT', url: COMPANY });
    expect(req.request.body).toEqual({
      triggerFiringsPerHour: { default: 10, max: 100 },
      webhookDeliveriesPerMinute: { default: 30, max: 300 },
      version: 5,
    });
    req.flush({ ...req.request.body, version: 6 });
    await settle();
  });

  it('reads the limits again when someone else saved first, and says so', async () => {
    await create();
    type('perDay', '500');
    save()!.click();
    await settle();
    http
      .expectOne({ method: 'PUT', url: CHAT })
      .flush(problem(409, 'SETTINGS_CHANGED'), { status: 409, statusText: 'Conflict' });
    await settle();
    http.expectOne({ method: 'GET', url: CHAT }).flush({ ...CHAT_SAVED, version: 4 });
    http.expectOne({ method: 'GET', url: COMPANY }).flush(COMPANY_SAVED);
    await settle();
    fixture.detectChanges();
    expect(words(el().querySelector('[data-testid=chat-rates-error]'))).toContain(
      'Someone changed these settings meanwhile.',
    );
    expect(field('perDay').value).toBe('200');
    expect(notify.success).not.toHaveBeenCalled();
  });

  it('says a limit out of range and sends nothing', async () => {
    await create();
    type('webhookDefault', '700');
    await settle();
    fixture.detectChanges();
    expect(el().textContent).toContain('At most the maximum.');
    expect(words(el().querySelector('[data-testid=unsaved-names]'))).toBe(
      'Correct the marked limit first.',
    );
    expect(save()!.disabled).toBe(true);
    save()!.click();
    await settle();
    http.expectNone((r) => r.method === 'PUT');
  });

  it('discards the changes and shows the saved limits again', async () => {
    await create();
    type('perDay', '500');
    await settle();
    fixture.detectChanges();
    Array.from(el().querySelectorAll('button'))
      .find((b) => b.textContent?.trim() === 'Discard')!
      .click();
    await settle();
    fixture.detectChanges();
    expect(field('perDay').value).toBe('200');
    expect(save()).toBeNull();
    http.expectNone((r) => r.method === 'PUT');
  });

  it('shows a member the limits read-only', async () => {
    await create('MEMBER');
    expect(field('perMinute').readOnly).toBe(true);
    expect(save()).toBeNull();
  });
});
