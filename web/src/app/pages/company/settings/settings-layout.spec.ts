import { Component } from '@angular/core';
import { HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import type { Role, SecretMeta } from '../../../api/generated/models';
import { aLicence } from '../../../testing/fixtures';
import {
  fakeCurrentCompany,
  fakeLicence,
  provideTestHttp,
  settle,
} from '../../../testing/test-helpers';
import { words } from '../../../testing/words';
import { SettingsLayout } from './settings-layout';

@Component({ selector: 'app-section-stub', template: '<p>section</p>' })
class SectionStub {}

const MEMORY = '/api/companies/c1/memory-settings';
const secret = (id: string, managed: boolean) =>
  ({ id, name: `S_${id}`, version: 1, managed }) as SecretMeta;

describe('SettingsLayout', () => {
  let http: HttpTestingController;

  async function open(role: Role, url = '/c/c1/settings') {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([
          {
            path: 'c/:companyId/settings',
            component: SettingsLayout,
            children: ['', 'limits', 'memory', 'secrets', 'licence'].map((path) => ({
              path,
              component: SectionStub,
            })),
          },
        ]),
        ...provideTestHttp(),
        fakeCurrentCompany(role).provider,
        fakeLicence(aLicence()).provider,
      ],
    });
    http = TestBed.inject(HttpTestingController);
    const harness = await RouterTestingHarness.create(url);
    await settle();
    return harness;
  }

  const sections = (h: RouterTestingHarness) =>
    Array.from(
      h.routeNativeElement!.querySelectorAll<HTMLAnchorElement>(
        '[data-testid=settings-sections] a',
      ),
    );
  const detail = (h: RouterTestingHarness, id: string) =>
    words(
      h.routeNativeElement!.querySelector(`[data-section="${id}"] [data-testid=section-detail]`),
    );

  afterEach(() => http.verify());

  it('names each section with its state for an owner, and marks the one open', async () => {
    const h = await open('OWNER');
    http
      .expectOne((r) => r.url === '/api/companies/c1/secrets')
      .flush({ items: [secret('1', false), secret('2', true), secret('3', false)] });
    http
      .expectOne(MEMORY)
      .flush({ compactionModel: 'claude-haiku-4-5', compactionModelAllowed: true });
    await settle();
    h.detectChanges();

    expect(sections(h).map((a) => words(a.querySelector('.font-semibold')))).toEqual([
      'General',
      'Chats and webhooks',
      'Memory',
      'Secrets',
      'Sign-in',
      'Licence',
    ]);
    expect(sections(h).map((a) => a.getAttribute('href'))).toEqual([
      '/c/c1/settings',
      '/c/c1/settings/limits',
      '/c/c1/settings/memory',
      '/c/c1/settings/secrets',
      '/c/c1/settings/sign-in',
      '/c/c1/settings/licence',
    ]);
    expect(sections(h)[0].getAttribute('aria-current')).toBe('page');
    expect(detail(h, 'memory')).toBe('Tidied by claude-haiku-4-5');
    expect(detail(h, 'secrets')).toBe('3 stored · 1 managed');
    expect(detail(h, 'sign-in')).toBe('SCIM provisioning');
    expect(detail(h, 'licence')).toBe('Pro · 12 of 20 seats used');

    await h.navigateByUrl('/c/c1/settings/secrets');
    expect(sections(h).map((a) => a.getAttribute('aria-current'))).toEqual([
      null,
      null,
      null,
      'page',
      null,
      null,
    ]);
  });

  it('says when there is no secret yet', async () => {
    const h = await open('OWNER');
    http.expectOne((r) => r.url === '/api/companies/c1/secrets').flush({ items: [] });
    http.expectOne(MEMORY).flush({ compactionModelAllowed: false });
    await settle();
    h.detectChanges();

    expect(detail(h, 'memory')).toBe('Duplicates only');
    expect(detail(h, 'secrets')).toBe('None yet');
  });

  it('names the memory model an admin chose that is not allowed', async () => {
    const h = await open('ADMIN');
    http.expectOne((r) => r.url === '/api/companies/c1/secrets').flush({ items: [] });
    http.expectOne(MEMORY).flush({ compactionModel: 'gpt-5.4', compactionModelAllowed: false });
    await settle();
    h.detectChanges();

    expect(detail(h, 'memory')).toBe('Model not allowed: duplicates only');
  });
});
