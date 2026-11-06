import { HttpTestingController } from '@angular/common/http/testing';
import { type ComponentFixture, TestBed } from '@angular/core/testing';
import type { Type } from '@angular/core';
import type { ExternalSystem } from '../../../../api/generated/models';
import {
  aWebhookDelivery,
  aWebhookMapped,
  aWebhookSystem,
  IDS,
} from '../../../../testing/fixtures';
import { fakeNotify, problem, provideTestHttp, settle } from '../../../../testing/test-helpers';
import {
  type DeliveriesSummary,
  deliveryStatus,
  WebhookDeliveries,
  weekLine,
} from './webhook-deliveries';
import { isPointer, mappingBody, WebhookMapping } from './webhook-mapping';
import { senderInstructions } from './webhook-secret';
import { secretDue, WebhookSetup } from './webhook-setup';

const API = `/api/companies/c1/systems/${IDS.hook}`;
const SIGNED = aWebhookSystem({
  webhook: {
    ...aWebhookSystem().webhook!,
    scheme: 'STANDARD',
    secretHeader: null,
    maxPerMinute: null,
    perMinute: 60,
  },
});

describe('the webhook’s words', () => {
  it.each`
    week                                          | line
    ${{ deliveries: 0, failed: 0, refused: 0 }}   | ${'No delivery in 7 days'}
    ${{ deliveries: 1, failed: 0, refused: 0 }}   | ${'1 delivery in 7 days'}
    ${{ deliveries: 312, failed: 2, refused: 0 }} | ${'312 deliveries in 7 days, 2 not mapped'}
    ${{ deliveries: 0, failed: 0, refused: 9 }}   | ${'No delivery in 7 days, 9 refused'}
  `('says the last 7 days as "$line"', ({ week, line }) => {
    expect(weekLine({ since: '2026-10-08T08:00:00Z', ...week })).toBe(line);
  });

  it.each`
    pointer            | valid
    ${''}              | ${true}
    ${'/issue/key'}    | ${true}
    ${'/a~1b/c~0d'}    | ${true}
    ${'/items/0/name'} | ${true}
    ${'issue/key'}     | ${false}
    ${'/bad~2escape'}  | ${false}
    ${'$.issue.key'}   | ${false}
  `('"$pointer" is a JSON Pointer: $valid', ({ pointer, valid }) => {
    expect(isPointer(pointer)).toBe(valid);
  });

  it('sends the whole mapping, a blank field as none', () => {
    expect(
      mappingBody({
        event: ' /action ',
        subjectKey: '/issue/key',
        subjectTitle: '',
        subjectUrl: '',
        actorId: '',
        actorName: '',
        text: '',
        deliveryId: '',
      }),
    ).toEqual({
      event: '/action',
      subjectKey: '/issue/key',
      subjectTitle: null,
      subjectUrl: null,
      actorId: null,
      actorName: null,
      text: null,
      deliveryId: null,
    });
  });

  it.each`
    status              | firings | label                   | tone
    ${'FIRED'}          | ${1}    | ${'Fired 1 trigger'}    | ${'success'}
    ${'FIRED'}          | ${2}    | ${'Fired 2 triggers'}   | ${'success'}
    ${'NO_TRIGGER'}     | ${0}    | ${'No trigger matched'} | ${'neutral'}
    ${'MAPPING_FAILED'} | ${0}    | ${'Mapping failed'}     | ${'destructive'}
  `('a $status delivery reads "$label"', ({ status, firings, label, tone }) => {
    expect(deliveryStatus({ status, firings })).toEqual({ label, tone });
  });

  it.each`
    scheme             | header           | says
    ${'STANDARD'}      | ${null}          | ${'webhook-signature'}
    ${'SECRET_HEADER'} | ${null}          | ${'the X-Tinfirm-Secret header'}
    ${'SECRET_HEADER'} | ${'X-Ops-Token'} | ${'the X-Ops-Token header'}
  `('tells the sender how to prove a $scheme delivery', ({ scheme, header, says }) => {
    expect(senderInstructions(scheme, header)).toContain(says);
  });

  it.each`
    age   | due
    ${10} | ${false}
    ${91} | ${true}
  `('a secret $age days old is due for a rotation: $due', ({ age, due }) => {
    const now = new Date('2026-10-12T00:00:00Z');
    const set = new Date(now.getTime() - age * 86_400_000).toISOString();
    expect(secretDue(set, now)).toBe(due);
    expect(secretDue(null, now)).toBe(false);
  });
});

describe('the webhook’s page', () => {
  let http: HttpTestingController;
  let notify: ReturnType<typeof fakeNotify>['notify'];

  const overlay = () => document.querySelector('.cdk-overlay-container') as HTMLElement;
  const text = (node: Element | null | undefined) => node?.textContent?.replace(/\s+/g, ' ').trim();

  async function create<T>(type: Type<T>, system: ExternalSystem, canManage = true) {
    const notifier = fakeNotify();
    notify = notifier.notify;
    TestBed.configureTestingModule({ providers: [...provideTestHttp(), notifier.provider] });
    http = TestBed.inject(HttpTestingController);
    const fixture: ComponentFixture<T> = TestBed.createComponent(type);
    fixture.componentRef.setInput('companyId', 'c1');
    fixture.componentRef.setInput('system', system);
    if ('canManage' in (fixture.componentInstance as object)) {
      fixture.componentRef.setInput('canManage', canManage);
    }
    fixture.detectChanges();
    await settle();
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    return {
      fixture,
      el,
      by: (id: string) => el.querySelector<HTMLElement>(`[data-testid=${id}]`),
    };
  }

  afterEach(() => {
    http.verify();
    overlay()?.replaceChildren();
  });

  describe('WebhookSetup', () => {
    it.each`
      case                   | system              | weaker   | proof                                 | rate
      ${'a secret header'}   | ${aWebhookSystem()} | ${true}  | ${'Secret in the X-Ops-Token header'} | ${'120'}
      ${'signed deliveries'} | ${SIGNED}           | ${false} | ${'Signed (Standard Webhooks)'}       | ${"60 (the company's default)"}
    `('shows the URL and $case', async ({ system, weaker, proof, rate }) => {
      const { by } = await create(WebhookSetup, system);
      expect(text(by('webhook-url'))).toBe(`https://tinfirm.acme.test/api/hooks/${IDS.hook}`);
      expect(!!by('webhook-weaker')).toBe(weaker);
      expect(text(by('webhook-scheme-shown'))).toBe(`Proof ${proof}`);
      expect(text(by('webhook-rate'))).toBe(`Deliveries a minute ${rate}`);
    });

    it('says a secret older than 90 days is due for a rotation', async () => {
      const { by } = await create(
        WebhookSetup,
        aWebhookSystem({
          webhook: { ...aWebhookSystem().webhook!, secretSetAt: '2020-01-01T00:00:00Z' },
        }),
      );
      expect(text(by('webhook-secret-age'))).toContain('rotate it: older than 90 days');
    });

    it('rotates the secret after a confirmation, and shows the new one once', async () => {
      const { fixture, by } = await create(WebhookSetup, aWebhookSystem());
      let rotated = 0;
      fixture.componentInstance.rotatedSecret.subscribe(() => rotated++);
      by('rotate-secret')!.click();
      await settle();
      fixture.detectChanges();
      expect(text(overlay())).toContain('The current one still works for 24 hours');
      overlay().querySelector<HTMLButtonElement>('[data-testid=confirm-rotate-secret]')!.click();
      await settle();
      http
        .expectOne({ method: 'POST', url: `${API}/webhook/rotate` })
        .flush({ secret: 'whsec_bmV3LXNlY3JldA', previousUntil: '2026-10-13T08:00:00Z' }); // gitleaks:allow (test fixture)
      await settle();
      fixture.detectChanges();
      expect(rotated).toBe(1);
      expect(text(overlay().querySelector('[data-testid=webhook-secret]'))).toBe(
        'whsec_bmV3LXNlY3JldA',
      );
      expect(text(overlay().querySelector('[data-testid=webhook-secret-once]'))).toContain(
        'The previous secret still works until',
      );
      overlay().querySelector<HTMLButtonElement>('[data-testid=rotated-done]')!.click();
      await settle();
      fixture.detectChanges();
      expect(overlay().querySelector('[data-testid=webhook-secret]')).toBeNull();
    });

    it('stops the current secret at once when asked to', async () => {
      const { fixture, by } = await create(WebhookSetup, aWebhookSystem());
      by('rotate-secret')!.click();
      await settle();
      fixture.detectChanges();
      const revoke = overlay().querySelector<HTMLInputElement>('[data-testid=revoke-previous]')!;
      expect(revoke.checked).toBe(false);
      revoke.click();
      await settle();
      fixture.detectChanges();
      expect(text(overlay())).toContain('The current one stops now');
      overlay().querySelector<HTMLButtonElement>('[data-testid=confirm-rotate-secret]')!.click();
      await settle();
      const req = http.expectOne({ method: 'POST', url: `${API}/webhook/rotate` });
      expect(req.request.body).toEqual({ revokePrevious: true });
      req.flush({ secret: 'whsec_bmV3LXNlY3JldA', previousUntil: null }); // gitleaks:allow (test fixture)
      await settle();
      fixture.detectChanges();
      expect(text(overlay().querySelector('[data-testid=webhook-secret-once]'))).not.toContain(
        'The previous secret still works until',
      );
    });

    it('shows a member no rotation', async () => {
      const { by } = await create(WebhookSetup, aWebhookSystem(), false);
      expect(by('rotate-secret')).toBeNull();
      expect(by('change-webhook')).toBeNull();
    });
  });

  describe('WebhookMapping', () => {
    it('shows the saved mapping, and saves it whole', async () => {
      const { fixture, el, by } = await create(WebhookMapping, aWebhookSystem());
      const field = (key: string) => el.querySelector<HTMLInputElement>(`#mapping-${key}`)!;
      expect(field('subjectKey').value).toBe('/issue/key');
      expect(by('mapping-save')?.hasAttribute('disabled')).toBe(true);
      field('text').value = '/comment/body';
      field('text').dispatchEvent(new Event('input'));
      fixture.detectChanges();
      by('mapping-save')!.click();
      await settle();
      const req = http.expectOne({ method: 'PATCH', url: API });
      expect(req.request.body.webhook.mapping).toMatchObject({
        event: '/action',
        subjectKey: '/issue/key',
        text: '/comment/body',
        actorId: null,
      });
      req.flush(aWebhookSystem());
      await settle();
      expect(notify.success).toHaveBeenCalledWith('The mapping is saved');
    });

    it('refuses a pointer that is none before sending, and names the field the server refused', async () => {
      const { fixture, el, by } = await create(WebhookMapping, aWebhookSystem());
      const field = (key: string) => el.querySelector<HTMLInputElement>(`#mapping-${key}`)!;
      field('event').value = 'action';
      field('event').dispatchEvent(new Event('input'));
      fixture.detectChanges();
      expect(text(el.querySelector('hlm-field-error'))).toContain('A JSON Pointer');
      by('mapping-save')!.click();
      await settle();
      http.expectNone((r) => r.method === 'PATCH');

      field('event').value = '/action';
      field('event').dispatchEvent(new Event('input'));
      field('actorId').value = '/user/id';
      field('actorId').dispatchEvent(new Event('input'));
      fixture.detectChanges();
      by('mapping-save')!.click();
      await settle();
      http
        .expectOne({ method: 'PATCH', url: API })
        .flush(
          problem(400, 'MAPPING_INVALID', 'mapping.actorId is not a JSON Pointer (RFC 6901)'),
          {
            status: 400,
            statusText: 'Bad Request',
          },
        );
      await settle();
      fixture.detectChanges();
      expect(text(field('actorId').closest('[hlmField]'))).toContain('Not a JSON Pointer');
    });

    it.each`
      case                     | answer                                                       | shows
      ${'the fields it reads'} | ${aWebhookMapped()}                                          | ${'Ops triage: would fire'}
      ${'no trigger yet'}      | ${aWebhookMapped({ triggers: [] })}                          | ${'None on this system yet'}
      ${'a failure'}           | ${aWebhookMapped({ failure: 'deliveryId reads an object' })} | ${'The mapping cannot read this body: deliveryId reads an object'}
    `('tries a sample delivery: $case', async ({ answer, shows }) => {
      const { fixture, el } = await create(WebhookMapping, aWebhookSystem());
      const sample = el.querySelector<HTMLTextAreaElement>('#mapping-sample')!;
      sample.value = '{"action":"opened","issue":{"key":"OPS-7","title":"Disk full"}}';
      sample.dispatchEvent(new Event('input'));
      fixture.detectChanges();
      el.querySelector<HTMLButtonElement>('[data-testid=mapping-try-run]')!.click();
      await settle();
      const req = http.expectOne({ method: 'POST', url: `${API}/webhook/map` });
      expect(req.request.body).toEqual({
        action: 'opened',
        issue: { key: 'OPS-7', title: 'Disk full' },
      });
      req.flush(answer);
      await settle();
      fixture.detectChanges();
      expect(text(el.querySelector('[data-testid=mapping-try]'))).toContain(shows);
    });

    it.each`
      sample      | says
      ${'{oops'}  | ${'This is not JSON.'}
      ${'[1, 2]'} | ${'A delivery’s body is a JSON object'}
    `('refuses a sample that is no object: $sample', async ({ sample, says }) => {
      const { fixture, el } = await create(WebhookMapping, aWebhookSystem());
      const area = el.querySelector<HTMLTextAreaElement>('#mapping-sample')!;
      area.value = sample;
      area.dispatchEvent(new Event('input'));
      fixture.detectChanges();
      el.querySelector<HTMLButtonElement>('[data-testid=mapping-try-run]')!.click();
      await settle();
      fixture.detectChanges();
      expect(text(el.querySelector('[data-testid=mapping-try]'))).toContain(says);
      http.expectNone((r) => r.url.endsWith('/webhook/map'));
    });

    it('lets a member read the mapping, and try nothing', async () => {
      const { el, by } = await create(WebhookMapping, aWebhookSystem(), false);
      expect(el.querySelector<HTMLInputElement>('#mapping-event')!.readOnly).toBe(true);
      expect(by('mapping-save')).toBeNull();
      expect(by('mapping-try')).toBeNull();
    });
  });

  describe('WebhookDeliveries', () => {
    it('lists the deliveries newest first, and opens one’s body', async () => {
      const { fixture, el } = await create(WebhookDeliveries, aWebhookSystem());
      http
        .expectOne((r) => r.url === `${API}/webhook/deliveries`)
        .flush({
          items: [
            aWebhookDelivery(),
            aWebhookDelivery({
              id: 'd-2',
              status: 'MAPPING_FAILED',
              firings: 0,
              body: null,
              failure: 'subjectKey reads an array',
            }),
          ],
          nextCursor: null,
        });
      await settle();
      fixture.detectChanges();
      const rows = el.querySelectorAll<HTMLElement>('[data-testid=delivery]');
      expect(rows).toHaveLength(2);
      expect(text(rows[0])).toContain('opened OPS-7 Fired 1 trigger');
      rows[0].querySelector('button')!.click();
      fixture.detectChanges();
      expect(text(el.querySelector('[data-testid=delivery-body]'))).toContain('"Disk full"');
      rows[1].querySelector('button')!.click();
      fixture.detectChanges();
      expect(text(rows[1])).toContain('subjectKey reads an array');
      expect(text(rows[1])).toContain('Its body is gone after 24 hours.');
    });

    it('counts the last 7 days from the first page, for the tab and the health strip', async () => {
      const { fixture, el } = await create(WebhookDeliveries, aWebhookSystem());
      const summaries: DeliveriesSummary[] = [];
      fixture.componentInstance.summary.subscribe((summary) => summaries.push(summary));
      const week = { since: '2026-10-08T08:00:00Z', deliveries: 40, failed: 1, refused: 3 };
      http
        .expectOne((r) => r.url === `${API}/webhook/deliveries` && !r.params.has('cursor'))
        .flush({ items: [aWebhookDelivery()], nextCursor: 'page-2', lastSevenDays: week });
      await settle();
      fixture.detectChanges();
      expect(text(el.querySelector('[data-testid=deliveries-week]'))).toBe(
        '40 deliveries in 7 days, 1 not mapped, 3 refused',
      );
      expect(summaries.at(-1)).toMatchObject({ count: '40', lastSevenDays: week });

      // The next page counts nothing again: the first page's week stays.
      el.querySelector<HTMLButtonElement>('[data-testid=deliveries-more]')!.click();
      await settle();
      http
        .expectOne(
          (r) => r.url === `${API}/webhook/deliveries` && r.params.get('cursor') === 'page-2',
        )
        .flush({ items: [aWebhookDelivery({ id: 'd-2' })], nextCursor: null });
      await settle();
      fixture.detectChanges();
      expect(summaries.at(-1)).toMatchObject({ count: '40', lastSevenDays: week });
      expect(text(el.querySelector('[data-testid=deliveries-week]'))).toContain('40 deliveries');
    });

    it('says when none came yet', async () => {
      const { fixture, el } = await create(WebhookDeliveries, aWebhookSystem());
      http
        .expectOne((r) => r.url === `${API}/webhook/deliveries`)
        .flush({ items: [], nextCursor: null });
      await settle();
      fixture.detectChanges();
      expect(el.querySelector('[data-testid=deliveries-empty]')).not.toBeNull();
    });
  });
});
