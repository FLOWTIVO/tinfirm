import { HttpTestingController } from '@angular/common/http/testing';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import type { ServiceIdentity } from '../../../api/generated/models';
import { aServiceIdentity } from '../../../testing/fixtures';
import {
  fakeCurrentCompany,
  fakeNotify,
  problem,
  provideTestHttp,
  settle,
  submitForm,
  typeInto,
} from '../../../testing/test-helpers';
import { ServiceIdentities } from './service-identities';

@Component({ selector: 'app-identity-stub', template: '<h1>Identity</h1>' })
class IdentityStub {}

const API = '/api/companies/c1';

describe('ServiceIdentities', () => {
  let http: HttpTestingController;
  let harness: RouterTestingHarness;
  let notify: ReturnType<typeof fakeNotify>['notify'];

  async function open(url: string) {
    const notifier = fakeNotify();
    notify = notifier.notify;
    TestBed.configureTestingModule({
      providers: [
        provideRouter(
          [
            { path: 'c/:companyId/service-identities', component: ServiceIdentities },
            { path: 'c/:companyId/service-identities/:principalId', component: IdentityStub },
          ],
          withComponentInputBinding(),
        ),
        ...provideTestHttp(),
        fakeCurrentCompany('ADMIN').provider,
        notifier.provider,
      ],
    });
    http = TestBed.inject(HttpTestingController);
    harness = await RouterTestingHarness.create();
    await harness.navigateByUrl(url);
    await settle();
  }

  async function flushIdentities(items: ServiceIdentity[]) {
    http.expectOne((r) => r.url === `${API}/service-identities`).flush({ items });
    await settle();
    harness.detectChanges();
  }

  const el = () => harness.routeNativeElement as HTMLElement;
  const overlay = () => document.querySelector('.cdk-overlay-container') as HTMLElement;
  const text = (node: Element | null | undefined) => node?.textContent?.replace(/\s+/g, ' ').trim();

  afterEach(() => {
    http.verify();
    overlay()?.replaceChildren();
  });

  it.each`
    identity                                                                    | status         | users
    ${aServiceIdentity()}                                                       | ${'Active'}    | ${'Used by Analysts'}
    ${aServiceIdentity({ status: 'SUSPENDED', userGroups: [], name: 'svc-x' })} | ${'Suspended'} | ${'Used by Only the Owner'}
  `('lists $identity.name, $status, $users', async ({ identity, status, users }) => {
    await open('/c/c1/service-identities');
    await flushIdentities([identity]);
    const row = el().querySelector(`[data-testid="identity-row-${identity.name}"]`)!;
    expect(row.getAttribute('href')).toBe(`/c/c1/service-identities/${identity.id}`);
    expect(text(row.querySelector('[data-testid=identity-status]'))).toBe(status);
    expect(text(row)).toContain(users);
    expect(el().querySelector('.text-human')).toBeNull();
  });

  it('explains service accounts when there is none', async () => {
    await open('/c/c1/service-identities');
    await flushIdentities([]);
    expect(text(el().querySelector('[data-testid=identities-empty]'))).toContain(
      'No service accounts yet',
    );
  });

  it('creates one, with a duplicate name on its field, and opens its page', async () => {
    await open('/c/c1/service-identities');
    await flushIdentities([]);
    el().querySelector<HTMLButtonElement>('[data-testid=new-identity]')!.click();
    await settle();
    typeInto(overlay().querySelector('#identity-name'), 'svc-reader');
    submitForm(overlay().querySelector('#identity-form'));
    await settle();
    http
      .expectOne((r) => r.url === `${API}/service-identities` && r.method === 'POST')
      .flush(problem(409, 'DUPLICATE_SERVICE_IDENTITY_NAME'), {
        status: 409,
        statusText: 'Conflict',
      });
    await settle();
    expect(text(overlay().querySelector('#identity-name')!.closest('[hlmField]'))).toContain(
      'A service account with this name already exists.',
    );

    typeInto(overlay().querySelector('#identity-name'), 'svc-support');
    typeInto(overlay().querySelector('#identity-description'), 'Answers support tickets');
    submitForm(overlay().querySelector('#identity-form'));
    await settle();
    const req = http.expectOne((r) => r.url === `${API}/service-identities` && r.method === 'POST');
    expect(req.request.body).toEqual({
      name: 'svc-support',
      description: 'Answers support tickets',
    });
    req.flush(aServiceIdentity({ id: 'p-svc-support', name: 'svc-support' }));
    await settle();
    expect(TestBed.inject(Router).url).toBe('/c/c1/service-identities/p-svc-support');
    expect(notify.success).toHaveBeenCalledWith(
      'svc-support is created',
      'Lend it a credential on the page of each system it acts on.',
    );
  });

  it('opens the list, which says where lending is, for a link to a grants tab', async () => {
    await open('/c/c1/service-identities?tab=grants');
    await flushIdentities([aServiceIdentity()]);
    http.expectNone((r) => r.url === `${API}/grants`);
    expect(el().querySelector('[data-testid=identity-row-svc-reader]')).not.toBeNull();
    expect(el().querySelector('app-segmented')).toBeNull();
    expect(text(el().querySelector('h1'))).toBe('Service accounts');
    expect(text(el())).toContain('Credentials are lent on each system’s page.');
  });
});
