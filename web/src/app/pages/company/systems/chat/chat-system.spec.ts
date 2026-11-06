import { HttpTestingController } from '@angular/common/http/testing';
import { type ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import type { ExternalSystem } from '../../../../api/generated/models';
import { aChatInboxItem, aSlackSystem, aTeamsSystem, IDS } from '../../../../testing/fixtures';
import { fakeNotify, provideTestHttp, settle } from '../../../../testing/test-helpers';
import { ChatSystem, silentItem } from './chat-system';

const SOCKET = aSlackSystem({
  chat: { ...aSlackSystem().chat!, transport: 'SOCKET_MODE' },
});

describe('silentItem', () => {
  it.each`
    case                       | items                                                                                   | found
    ${'this system’s'}         | ${[aChatInboxItem('CHAT_SUBSCRIPTION_SILENT')]}                                         | ${true}
    ${'another system’s'}      | ${[aChatInboxItem('CHAT_SUBSCRIPTION_SILENT', { chat: { systemId: 'x', text: 't' } })]} | ${false}
    ${'a link, not a silence'} | ${[aChatInboxItem('CHAT_LINKED')]}                                                      | ${false}
    ${'none'}                  | ${[]}                                                                                   | ${false}
  `('finds $case', ({ items, found }) => {
    expect(!!silentItem(items, IDS.slack)).toBe(found);
  });
});

describe('ChatSystem', () => {
  let fixture: ComponentFixture<ChatSystem>;
  let http: HttpTestingController;

  const el = () => fixture.nativeElement as HTMLElement;
  const by = (testId: string) => el().querySelector<HTMLElement>(`[data-testid=${testId}]`);
  const text = (node: Element | null | undefined) => node?.textContent?.replace(/\s+/g, ' ').trim();

  async function create(system: ExternalSystem, canManage = true) {
    TestBed.configureTestingModule({
      providers: [provideRouter([]), ...provideTestHttp(), fakeNotify().provider],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ChatSystem);
    fixture.componentRef.setInput('companyId', 'c1');
    fixture.componentRef.setInput('system', system);
    fixture.componentRef.setInput('canManage', canManage);
    await settle();
    fixture.detectChanges();
  }

  afterEach(() => http.verify());

  it.each`
    case                    | system            | readsInbox
    ${'Slack, Events API'}  | ${aSlackSystem()} | ${true}
    ${'Slack, Socket Mode'} | ${SOCKET}         | ${false}
    ${'Teams'}              | ${aTeamsSystem()} | ${false}
  `(
    'reads the admins’ inbox for a silent subscription: $case → $readsInbox',
    async ({ system, readsInbox }) => {
      await create(system);
      const reads = http.match('/api/companies/c1/inbox');
      expect(reads.length).toBe(readsInbox ? 1 : 0);
      reads.forEach((r) => r.flush({ items: [], counts: {} }));
      await settle();
      fixture.detectChanges();
      // The overview: the platform's app; the bot and the channels have tabs of their own.
      expect(by('chat-setup')).not.toBeNull();
      expect(by('chat-bot')).toBeNull();
      expect(by('chat-channels')).toBeNull();
      expect(by('chat-silent')).toBeNull();
    },
  );

  it('says in orange that Slack stopped sending events, with the server’s step', async () => {
    await create(aSlackSystem());
    http
      .expectOne('/api/companies/c1/inbox')
      .flush({ items: [aChatInboxItem('CHAT_SUBSCRIPTION_SILENT')], counts: {} });
    await settle();
    fixture.detectChanges();
    expect(by('chat-silent')?.getAttribute('role')).toBe('alert');
    expect(by('chat-silent')?.className).toContain('border-human');
    expect(text(by('chat-silent'))).toContain(
      'Re-enable them on the app’s Event Subscriptions page.',
    );
  });

  it('reads no inbox for a member', async () => {
    await create(aSlackSystem(), false);
    http.expectNone('/api/companies/c1/inbox');
  });
});
