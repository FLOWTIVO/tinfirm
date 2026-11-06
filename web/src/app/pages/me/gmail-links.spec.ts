import { HttpTestingController } from '@angular/common/http/testing';
import { type ComponentFixture, TestBed } from '@angular/core/testing';
import type { GmailLink } from '../../api/generated/models';
import { fakeNotify, problem, provideTestHttp, settle } from '../../testing/test-helpers';
import { GmailLinks } from './gmail-links';

const LINK: GmailLink = {
  id: 'l1',
  companyId: 'c1',
  companyName: 'Acme',
  googleEmail: 'alice@gmail.test',
  linkedAt: '2026-10-19T08:00:00Z',
  lastUsedAt: '2026-10-20T09:00:00Z',
};

describe('GmailLinks', () => {
  let fixture: ComponentFixture<GmailLinks>;
  let http: HttpTestingController;
  let notify: ReturnType<typeof fakeNotify>['notify'];
  let el: HTMLElement;

  const text = (node: Element | null) => node?.textContent?.replace(/\s+/g, ' ').trim();

  async function create(links: GmailLink[]) {
    const fake = fakeNotify();
    notify = fake.notify;
    TestBed.configureTestingModule({
      imports: [GmailLinks],
      providers: [...provideTestHttp(), fake.provider],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(GmailLinks);
    el = fixture.nativeElement as HTMLElement;
    await settle();
    http.expectOne({ method: 'GET', url: '/api/me/gmail/links' }).flush({ items: links });
    await settle();
    fixture.detectChanges();
  }

  afterEach(() => http.verify());

  it('shows nothing while the person has linked no Google account', async () => {
    await create([]);
    expect(el.querySelector('[data-testid=gmail-links]')).toBeNull();
  });

  it('lists each linked account with its company', async () => {
    await create([LINK]);
    const row = el.querySelector('[data-testid=gmail-link-l1]');
    expect(text(row)).toContain('alice@gmail.test');
    expect(text(row)).toContain('In Acme');
  });

  it('disconnects an account after asking', async () => {
    await create([LINK]);
    el.querySelector<HTMLButtonElement>('[data-testid=gmail-link-l1] button')!.click();
    await settle();
    fixture.detectChanges();
    document.querySelector<HTMLButtonElement>('[data-testid=gmail-link-disconnect]')!.click();
    await settle();
    http.expectOne({ method: 'DELETE', url: '/api/companies/c1/gmail/links/l1' }).flush(null);
    await settle();
    fixture.detectChanges();
    expect(el.querySelector('[data-testid=gmail-link-l1]')).toBeNull();
    expect(notify.success).toHaveBeenCalledWith(
      'alice@gmail.test disconnected',
      'Gmail no longer acts as you in Acme.',
    );
  });

  it('keeps an account the server did not disconnect', async () => {
    await create([LINK]);
    el.querySelector<HTMLButtonElement>('[data-testid=gmail-link-l1] button')!.click();
    await settle();
    fixture.detectChanges();
    document.querySelector<HTMLButtonElement>('[data-testid=gmail-link-disconnect]')!.click();
    await settle();
    http
      .expectOne({ method: 'DELETE', url: '/api/companies/c1/gmail/links/l1' })
      .flush(problem(500), { status: 500, statusText: 'Error' });
    await settle();
    fixture.detectChanges();
    expect(el.querySelector('[data-testid=gmail-link-l1]')).not.toBeNull();
    expect(notify.success).not.toHaveBeenCalled();
  });
});
