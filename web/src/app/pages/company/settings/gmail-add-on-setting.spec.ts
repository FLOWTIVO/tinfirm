import { HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import type { Licence } from '../../../api/generated/models';
import { aCommunityLicence } from '../../../testing/fixtures';
import {
  fakeCurrentCompany,
  fakeLicence,
  fakeNotify,
  problem,
  provideTestHttp,
  settle,
} from '../../../testing/test-helpers';
import { GmailAddOnSetting } from './gmail-add-on-setting';

const DEPLOYMENT = '/api/companies/c1/gmail/deployment';

describe('GmailAddOnSetting', () => {
  let http: HttpTestingController;
  let el: HTMLElement;

  async function open(licence?: Licence) {
    const notifier = fakeNotify();
    TestBed.configureTestingModule({
      imports: [GmailAddOnSetting],
      providers: [
        ...provideTestHttp(),
        fakeCurrentCompany('ADMIN').provider,
        notifier.provider,
        fakeLicence(licence).provider,
      ],
    });
    http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(GmailAddOnSetting);
    el = fixture.nativeElement;
    await settle();
  }

  afterEach(() => {
    http.verify();
    vi.restoreAllMocks();
  });

  it('downloads the company’s deployment as a JSON file', async () => {
    await open();
    http.expectOne({ method: 'GET', url: DEPLOYMENT }).flush({
      fileName: 'tinfirm-gmail-deployment.json',
      deployment: '{"oauthScopes":[]}\n',
      configured: true,
    });
    await settle();
    expect(el.querySelector('[data-testid=gmail-not-configured]')).toBeNull();
    const created = vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:deployment');
    const clicked = vi
      .spyOn(HTMLAnchorElement.prototype, 'click')
      .mockImplementation(() => undefined);
    el.querySelector<HTMLButtonElement>('[data-testid=gmail-deployment]')!.click();
    await settle();
    const blob = created.mock.calls[0][0] as Blob;
    expect(blob.type).toBe('application/json');
    expect(await blob.text()).toBe('{"oauthScopes":[]}\n');
    expect(clicked).toHaveBeenCalled();
  });

  it('says when the operator has not configured the Google Cloud project', async () => {
    await open();
    http.expectOne(DEPLOYMENT).flush({
      fileName: 'tinfirm-gmail-deployment.json',
      deployment: '{}',
      configured: false,
    });
    await settle();
    expect(el.querySelector('[data-testid=gmail-not-configured]')!.textContent).toContain(
      'TINFIRM_GMAIL_SERVICE_ACCOUNT',
    );
  });

  it('says when it cannot load the file', async () => {
    await open();
    http.expectOne(DEPLOYMENT).flush(problem(404), { status: 404, statusText: 'Not Found' });
    await settle();
    expect(el.querySelector('[data-testid=gmail-error]')).not.toBeNull();
    expect(el.querySelector<HTMLButtonElement>('[data-testid=gmail-deployment]')!.disabled).toBe(
      true,
    );
  });

  it('says the add-on needs Pro under Community, and asks for no file', async () => {
    await open(aCommunityLicence());
    http.expectNone(DEPLOYMENT);
    expect(el.querySelector('[data-testid=gmail-edition]')!.textContent).toContain('Pro licence');
    expect(el.querySelector('[data-testid=edition-badge-GMAIL_ADDON]')).not.toBeNull();
    expect(el.querySelector('[data-testid=gmail-deployment]')).toBeNull();
  });
});
