import { HttpErrorResponse } from '@angular/common/http';
import { HttpTestingController } from '@angular/common/http/testing';
import { type ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import type { ExternalSystem, ServiceIdentity } from '../../../../api/generated/models';
import { aNewSlackSystem, aSlackSystem, aTeamsSystem, AT, IDS } from '../../../../testing/fixtures';
import {
  fakeNotify,
  problem,
  provideTestHttp,
  settle,
  submitForm,
  typeInto,
} from '../../../../testing/test-helpers';
import { botProblemMessage, botTokenHint, ChatBot } from './chat-bot';

const API = '/api/companies/c1';

function anIdentity(
  id: string,
  name: string,
  status: 'ACTIVE' | 'SUSPENDED' = 'ACTIVE',
): ServiceIdentity {
  return { id, name, status, usableByMe: true, createdAt: AT.earlier, updatedAt: AT.earlier };
}

const SVC_BOT = anIdentity(IDS.svcSlackBot, 'svc-slack-bot');
const SVC_READER = anIdentity(IDS.svcReader, 'svc-reader');
const SVC_GONE = anIdentity('p-svc-gone', 'svc-gone', 'SUSPENDED');

describe('the bot’s words', () => {
  it.each`
    kind       | label             | placeholder
    ${'SLACK'} | ${'Bot token'}    | ${'xoxb-…'}
    ${'TEAMS'} | ${'App password'} | ${'A client secret of the app'}
  `('a $kind bot takes its $label', ({ kind, label, placeholder }) => {
    expect(botTokenHint(kind)).toMatchObject({ label, placeholder });
  });

  it.each`
    status | code                       | detail                                                   | says
    ${409} | ${'BOT_ACCOUNT_IN_USE'}    | ${undefined}                                             | ${'Another service account borrows this system’s bot. Only the same service account replaces its token: remove the bot first to move it.'}
    ${400} | ${'BOT_IDENTITY_MISMATCH'} | ${undefined}                                             | ${'This token belongs to another workspace or another bot than the system’s. Use the token of the app installed in this workspace.'}
    ${409} | ${'SYSTEM_PAUSED'}         | ${undefined}                                             | ${'An admin paused this system: no bot is set on it until it is resumed. Removing the bot still works.'}
    ${400} | ${'ACCOUNT_REFUSED'}       | ${'Slack: invalid_auth'}                                 | ${'The platform refused the token: Slack: invalid_auth.'}
    ${400} | ${'ACCOUNT_REFUSED'}       | ${'The platform refused the token: Slack: invalid_auth'} | ${'The platform refused the token: Slack: invalid_auth.'}
    ${400} | ${'ACCOUNT_REFUSED'}       | ${undefined}                                             | ${'The platform refused the token. Check that it is the installed app’s, and current.'}
    ${409} | ${'PRINCIPAL_SUSPENDED'}   | ${undefined}                                             | ${'This service account is suspended. Resume it, or choose another one.'}
    ${409} | ${'CHAT_WORKSPACE_TAKEN'}  | ${undefined}                                             | ${'Another system of this company already serves this workspace or tenant. One system per workspace.'}
  `('says $code in words', ({ status, code, detail, says }) => {
    const error = new HttpErrorResponse({ status, error: problem(status, code, detail) });
    expect(botProblemMessage(error)).toBe(says);
  });
});

describe('ChatBot', () => {
  let fixture: ComponentFixture<ChatBot>;
  let http: HttpTestingController;
  let notify: ReturnType<typeof fakeNotify>['notify'];
  let changed: number;

  const el = () => fixture.nativeElement as HTMLElement;
  const by = (testId: string) => el().querySelector<HTMLElement>(`[data-testid=${testId}]`);
  const overlay = () => document.querySelector('.cdk-overlay-container') as HTMLElement;
  const inOverlay = <T extends Element = HTMLElement>(selector: string) =>
    overlay().querySelector<T>(selector);
  const text = (node: Element | null | undefined) => node?.textContent?.replace(/\s+/g, ' ').trim();

  async function create(system: ExternalSystem, canManage = true) {
    const notifier = fakeNotify();
    notify = notifier.notify;
    TestBed.configureTestingModule({
      providers: [provideRouter([]), ...provideTestHttp(), notifier.provider],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ChatBot);
    fixture.componentRef.setInput('companyId', 'c1');
    fixture.componentRef.setInput('system', system);
    fixture.componentRef.setInput('canManage', canManage);
    changed = 0;
    fixture.componentInstance.changed.subscribe(() => changed++);
    await settle();
  }

  /** Answers the identities, and each one's accounts: svc-slack-bot holds the bot. */
  /** Answers the service accounts, which only a system without a bot reads, to pick from. */
  async function flushIdentities(identities = [SVC_BOT, SVC_READER, SVC_GONE]) {
    for (const req of http.match((r) => r.url === `${API}/service-identities`)) {
      req.flush({ items: identities, nextCursor: null });
    }
    await settle();
    fixture.detectChanges();
  }

  afterEach(() => {
    http.verify();
    overlay()?.replaceChildren();
  });

  it.each`
    case                  | system               | botSet   | identity                                  | workspace
    ${'Slack with a bot'} | ${aSlackSystem()}    | ${true}  | ${'U07BOT001'}                            | ${'Acme (T02XYZ789)'}
    ${'Teams with a bot'} | ${aTeamsSystem()}    | ${true}  | ${'0b9c1f5e-3d4a-4b8c-9e2f-6a7b8c9d0e1f'} | ${null}
    ${'Slack without'}    | ${aNewSlackSystem()} | ${false} | ${null}                                   | ${null}
  `('draws $case', async ({ system, botSet, identity, workspace }) => {
    await create(system);
    await flushIdentities();
    expect(by('chat-bot')?.getAttribute('data-bot-set')).toBe(String(botSet));
    expect(!!by('bot-missing')).toBe(!botSet);
    expect(!!by('set-bot')).toBe(!botSet);
    expect(!!by('replace-bot')).toBe(botSet);
    if (identity) expect(text(by('bot-identity'))).toContain(identity);
    if (workspace) expect(text(by('bot-workspace'))).toBe(`Workspace ${workspace}`);
    else expect(by('bot-workspace')).toBeNull();
  });

  it('names the service account that holds the bot, and reads no identity to do it', async () => {
    await create(aSlackSystem());
    http.expectNone((r) => r.url === `${API}/service-identities`);
    fixture.detectChanges();
    expect(text(by('bot-holder')?.querySelector('dd'))).toBe('svc-slack-bot');
    expect(by('bot-holder')?.querySelector('a')?.getAttribute('href')).toBe(
      `/c/c1/service-identities/${IDS.svcSlackBot}`,
    );
    // The bot is a credential of the system, by its name (ADR-0072).
    expect(text(by('bot-credential')?.querySelector('dd'))).toBe('Bot');
  });

  it('shows a member the bot without its controls', async () => {
    await create(aSlackSystem(), false);
    expect(by('replace-bot')).toBeNull();
    expect(by('remove-bot')).toBeNull();
    expect(text(by('bot-holder')?.querySelector('dd'))).toBe('svc-slack-bot');
  });

  it('sets a new bot with the chosen active service account and the token, and drops the token', async () => {
    await create(aNewSlackSystem());
    await flushIdentities();
    by('set-bot')!.click();
    await settle();
    fixture.detectChanges();
    const options = Array.from(
      inOverlay<HTMLSelectElement>('select#bot-principal')!.querySelectorAll('option'),
    ).map((o) => o.textContent?.trim());
    // A suspended service account borrows no bot.
    expect(options).toEqual(['Choose a service account', 'svc-slack-bot', 'svc-reader']);

    // Without an identity, nothing goes.
    typeInto(inOverlay('#bot-token'), 'xoxb-1-2');
    submitForm(inOverlay('form#bot-form'));
    await settle();
    fixture.detectChanges();
    expect(text(inOverlay('hlm-field-error'))).toBe(
      'Choose the service account that borrows the bot.',
    );

    const select = inOverlay<HTMLSelectElement>('select#bot-principal')!;
    select.value = IDS.svcSlackBot;
    select.dispatchEvent(new Event('change'));
    await settle();
    submitForm(inOverlay('form#bot-form'));
    await settle();
    const req = http.expectOne({ method: 'PUT', url: `${API}/systems/${IDS.slack}/bot` });
    expect(req.request.body).toEqual({ principalId: IDS.svcSlackBot, token: 'xoxb-1-2' });
    req.flush({
      systemId: IDS.slack,
      principalId: IDS.svcSlackBot,
      credentialId: IDS.slackBotAccount,
      identity: 'U07BOT001',
      teamId: 'T02XYZ789',
      teamName: 'Acme',
    });
    await settle();
    expect(changed).toBe(1);
    expect(notify.success).toHaveBeenCalledWith(
      'The bot of Acme Slack is set',
      'It answers in Acme.',
    );
    expect(fixture.componentInstance['model']().token).toBe('');
  });

  it('replaces the token with the same service account only', async () => {
    await create(aSlackSystem());
    await flushIdentities();
    by('replace-bot')!.click();
    await settle();
    fixture.detectChanges();
    expect(inOverlay('select#bot-principal')).toBeNull();
    expect(text(inOverlay('[data-testid=bot-principal-fixed]'))).toBe('svc-slack-bot');
    typeInto(inOverlay('#bot-token'), 'xoxb-new');
    submitForm(inOverlay('form#bot-form'));
    await settle();
    const req = http.expectOne({ method: 'PUT', url: `${API}/systems/${IDS.slack}/bot` });
    expect(req.request.body.principalId).toBe(IDS.svcSlackBot);
    req.flush(problem(400, 'BOT_IDENTITY_MISMATCH'), { status: 400, statusText: 'Bad Request' });
    await settle();
    fixture.detectChanges();
    expect(text(inOverlay('[data-testid=bot-form-error]'))).toContain(
      'This token belongs to another workspace or another bot',
    );
    expect(changed).toBe(0);
  });

  it('removes the bot after a confirmation', async () => {
    await create(aSlackSystem());
    await flushIdentities();
    by('remove-bot')!.click();
    await settle();
    fixture.detectChanges();
    expect(text(overlay())).toContain('checked and dropped, and never answered later');
    inOverlay<HTMLButtonElement>('[data-testid=confirm-remove-bot]')!.click();
    await settle();
    http
      .expectOne({ method: 'DELETE', url: `${API}/systems/${IDS.slack}/bot` })
      .flush(null, { status: 204, statusText: 'No Content' });
    await settle();
    expect(changed).toBe(1);
    expect(notify.success).toHaveBeenCalledWith('The bot of Acme Slack is removed');
  });

  it('cannot set a paused system’s bot', async () => {
    await create(aNewSlackSystem({ paused: true }));
    await flushIdentities();
    expect(by('set-bot')?.hasAttribute('disabled')).toBe(true);
  });
});
