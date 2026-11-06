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
import { OutlookAddInSetting } from './outlook-add-in-setting';

const MANIFEST = '/api/companies/c1/outlook/manifest';

describe('OutlookAddInSetting', () => {
  let http: HttpTestingController;
  let el: HTMLElement;
  let notify: ReturnType<typeof fakeNotify>['notify'];

  async function open(licence?: Licence) {
    const notifier = fakeNotify();
    notify = notifier.notify;
    TestBed.configureTestingModule({
      imports: [OutlookAddInSetting],
      providers: [
        ...provideTestHttp(),
        fakeCurrentCompany('ADMIN').provider,
        notifier.provider,
        fakeLicence(licence).provider,
      ],
    });
    http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(OutlookAddInSetting);
    el = fixture.nativeElement;
    await settle();
  }

  afterEach(() => {
    http.verify();
    vi.restoreAllMocks();
  });

  it('downloads the company’s manifest as an XML file', async () => {
    await open();
    const created = vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:manifest');
    const clicked = vi
      .spyOn(HTMLAnchorElement.prototype, 'click')
      .mockImplementation(() => undefined);
    el.querySelector<HTMLButtonElement>('[data-testid=outlook-manifest]')!.click();
    await settle();
    http.expectOne({ method: 'GET', url: MANIFEST }).flush({
      fileName: 'tinfirm-outlook-manifest.xml',
      addInId: '6b1c8f1e-3d0a-3c1b-9a52-2f0f6a1d7e10',
      taskPaneUrl: 'https://tinfirm.example/outlook/c1',
      manifest: '<OfficeApp/>',
    });
    await settle();
    const blob = created.mock.calls[0][0] as Blob;
    expect(blob.type).toBe('application/xml');
    expect(await blob.text()).toBe('<OfficeApp/>');
    expect(clicked).toHaveBeenCalled();
  });

  it('offers no manifest without a Pro licence, and says why (ADR-0077)', async () => {
    await open(aCommunityLicence());
    expect(el.querySelector('[data-testid=outlook-manifest]')).toBeNull();
    expect(el.querySelector('[data-testid=edition-badge-OUTLOOK_ADDIN]')).not.toBeNull();
    expect(el.querySelector('[data-testid=outlook-edition]')?.textContent).toContain(
      'The Outlook add-in needs a Pro licence.',
    );
  });

  it('says when it cannot download it', async () => {
    await open();
    el.querySelector<HTMLButtonElement>('[data-testid=outlook-manifest]')!.click();
    await settle();
    http.expectOne(MANIFEST).flush(problem(404), { status: 404, statusText: 'Not Found' });
    await settle();
    expect(notify.error).toHaveBeenCalledWith('Couldn’t download the manifest', expect.anything());
  });
});
