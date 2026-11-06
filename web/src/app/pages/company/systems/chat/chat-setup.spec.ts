import { HttpTestingController } from '@angular/common/http/testing';
import { type ComponentFixture, TestBed } from '@angular/core/testing';
import type { ExternalSystem } from '../../../../api/generated/models';
import { aNewSlackSystem, aSlackSystem, aTeamsSystem, IDS } from '../../../../testing/fixtures';
import {
  fakeNotify,
  problem,
  provideTestHttp,
  settle,
  submitForm,
  typeInto,
} from '../../../../testing/test-helpers';
import { ChatSetup, manifestFile, platformUrls, secretRows } from './chat-setup';

const SOCKET = aSlackSystem({
  chat: { ...aSlackSystem().chat!, transport: 'SOCKET_MODE', appTokenSet: false },
});

describe('a chat system’s setup', () => {
  it.each`
    case                    | system            | urls
    ${'Slack, Events API'}  | ${aSlackSystem()} | ${['Events request URL', 'Interactivity request URL']}
    ${'Slack, Socket Mode'} | ${SOCKET}         | ${[]}
    ${'Teams'}              | ${aTeamsSystem()} | ${['Messaging endpoint']}
  `('$case sets the URLs $urls on the platform', ({ system, urls }) => {
    expect(platformUrls(system).map((u) => u.label)).toEqual(urls);
  });

  it.each`
    case             | system            | rows
    ${'Events API'}  | ${aSlackSystem()} | ${[['signingSecret', true, true], ['appToken', false, false]]}
    ${'Socket Mode'} | ${SOCKET}         | ${[['appToken', false, true], ['signingSecret', true, false]]}
  `('with $case, the secret it needs comes first', ({ system, rows }) => {
    expect(secretRows(system).map((r) => [r.name, r.set, r.needed])).toEqual(rows);
  });

  it('saves Slack’s manifest as JSON, and Teams’ package as a zip from its base64', async () => {
    const json = manifestFile({ kind: 'SLACK', fileName: 'tinfirm.json', manifest: { a: 1 } });
    expect(json.type).toBe('application/json');
    expect(JSON.parse(await json.text())).toEqual({ a: 1 });
    const zip = manifestFile({
      kind: 'TEAMS',
      fileName: 'tinfirm.zip',
      appPackage: btoa('PK\u0003\u0004'),
    });
    expect(zip.type).toBe('application/zip');
    expect(new Uint8Array(await zip.arrayBuffer())).toEqual(new Uint8Array([80, 75, 3, 4]));
  });
});

describe('ChatSetup', () => {
  let fixture: ComponentFixture<ChatSetup>;
  let http: HttpTestingController;
  let notify: ReturnType<typeof fakeNotify>['notify'];
  let saved: ExternalSystem[];
  let transportAsked: number;

  const el = () => fixture.nativeElement as HTMLElement;
  const by = (testId: string) => el().querySelector<HTMLElement>(`[data-testid=${testId}]`);
  const overlay = () => document.querySelector('.cdk-overlay-container') as HTMLElement;
  const inOverlay = (selector: string) => overlay().querySelector<HTMLElement>(selector);
  const text = (node: Element | null | undefined) => node?.textContent?.replace(/\s+/g, ' ').trim();

  async function create(system: ExternalSystem, canManage = true) {
    const notifier = fakeNotify();
    notify = notifier.notify;
    TestBed.configureTestingModule({ providers: [...provideTestHttp(), notifier.provider] });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ChatSetup);
    fixture.componentRef.setInput('companyId', 'c1');
    fixture.componentRef.setInput('system', system);
    fixture.componentRef.setInput('canManage', canManage);
    saved = [];
    transportAsked = 0;
    fixture.componentInstance.changed.subscribe((s) => saved.push(s));
    fixture.componentInstance.changeTransport.subscribe(() => transportAsked++);
    await settle();
    fixture.detectChanges();
  }

  afterEach(() => {
    http.verify();
    overlay()?.replaceChildren();
  });

  it.each`
    case                    | system            | transport        | urls | secrets  | download
    ${'Slack, Events API'}  | ${aSlackSystem()} | ${'Events API'}  | ${2} | ${true}  | ${'Download the manifest'}
    ${'Slack, Socket Mode'} | ${SOCKET}         | ${'Socket Mode'} | ${0} | ${true}  | ${'Download the manifest'}
    ${'Teams'}              | ${aTeamsSystem()} | ${null}          | ${1} | ${false} | ${'Download the app package'}
  `('draws $case', async ({ system, transport, urls, secrets, download }) => {
    await create(system);
    if (transport) expect(text(by('chat-transport'))).toContain(`Events arrive by ${transport}`);
    else expect(by('chat-transport')).toBeNull();
    expect(el().querySelectorAll('[data-testid^=chat-url-]')).toHaveLength(urls);
    expect(!!by('chat-secrets')).toBe(secrets);
    expect(text(by('download-manifest'))).toBe(download);
  });

  it('shows the URLs to copy with where each goes', async () => {
    await create(aSlackSystem());
    expect(text(by('chat-url-0'))).toBe(
      `https://tinfirm.acme.test/api/chat/slack/${IDS.slack}/events`,
    );
    expect(text(by('chat-urls'))).toContain(
      'Store the signing secret first: Slack checks it at once.',
    );
  });

  it('shows a member no URL, no download and no secret to store', async () => {
    await create(aSlackSystem(), false);
    expect(by('chat-urls')).toBeNull();
    expect(by('download-manifest')).toBeNull();
    expect(by('set-signingSecret')).toBeNull();
    expect(by('change-transport')).toBeNull();
  });

  it('asks the page to change the transport', async () => {
    await create(aSlackSystem());
    by('change-transport')!.click();
    expect(transportAsked).toBe(1);
  });

  it('stores the signing secret write-only, and says it is stored', async () => {
    await create(aNewSlackSystem());
    expect(by('secret-signingSecret')?.getAttribute('data-set')).toBe('false');
    by('set-signingSecret')!.click();
    await settle();
    fixture.detectChanges();
    typeInto(inOverlay('#chat-secret'), ' the-signing-secret ');
    submitForm(inOverlay('form#secret-form'));
    await settle();
    const req = http.expectOne({
      method: 'PUT',
      url: `/api/companies/c1/systems/${IDS.slack}/chat/secrets`,
    });
    expect(req.request.body).toEqual({ signingSecret: 'the-signing-secret' });
    const after = aNewSlackSystem({ chat: { ...aNewSlackSystem().chat!, signingSecretSet: true } });
    req.flush(after);
    await settle();
    expect(saved).toEqual([after]);
    expect(notify.success).toHaveBeenCalledWith('The signing secret is stored');
    expect(fixture.componentInstance['secretModel']().value).toBe('');
  });

  it('refuses an app-level token that is not xapp-, before sending it', async () => {
    await create(SOCKET);
    by('set-appToken')!.click();
    await settle();
    fixture.detectChanges();
    typeInto(inOverlay('#chat-secret'), 'xoxb-not-an-app-token');
    submitForm(inOverlay('form#secret-form'));
    await settle();
    fixture.detectChanges();
    expect(text(inOverlay('hlm-field-error'))).toBe('An app-level token starts with xapp-.');
    http.expectNone((r) => r.url.endsWith('/chat/secrets'));
  });

  it('says the server’s refusal of a secret', async () => {
    await create(aSlackSystem());
    by('set-signingSecret')!.click();
    await settle();
    typeInto(inOverlay('#chat-secret'), 'abc');
    submitForm(inOverlay('form#secret-form'));
    await settle();
    http
      .expectOne((r) => r.url.endsWith('/chat/secrets'))
      .flush(problem(400, 'SYSTEM_KIND_INVALID'), { status: 400, statusText: 'Bad Request' });
    await settle();
    fixture.detectChanges();
    expect(text(inOverlay('[data-testid=secret-error]'))).toBe(
      'Only a Slack or Teams system has a bot and chat secrets.',
    );
  });

  it('downloads the manifest', async () => {
    await create(aSlackSystem());
    const click = vi
      .spyOn(HTMLAnchorElement.prototype, 'click')
      .mockImplementation(() => undefined);
    URL.createObjectURL = vi.fn(() => 'blob:manifest');
    URL.revokeObjectURL = vi.fn();
    by('download-manifest')!.click();
    await settle();
    http.expectOne(`/api/companies/c1/systems/${IDS.slack}/chat/manifest`).flush({
      kind: 'SLACK',
      fileName: 'tinfirm-slack.json',
      manifest: { display_information: {} },
    });
    await settle();
    expect(click).toHaveBeenCalledTimes(1);
    expect(URL.createObjectURL).toHaveBeenCalled();
    click.mockRestore();
  });
});
