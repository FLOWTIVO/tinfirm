import { TestBed } from '@angular/core/testing';
import type { ConversationChat } from '../../api/generated/models';
import { IDS } from '../../testing/fixtures';
import { ChatPlace, type ChatPlaced, chatPlaceView } from './chat-place';

const SLACK: ConversationChat = {
  systemId: IDS.slack,
  systemName: 'Acme Slack',
  systemKind: 'SLACK',
  channelId: 'D04ABC123',
  threadId: '1800000000.000100',
  permalink: 'https://slack.com/app_redirect?team=T02XYZ789&channel=D04ABC123',
};

const dm = (chat: ConversationChat | null): ChatPlaced => ({
  place: 'CHAT_THREAD',
  origin: 'DIRECT_MESSAGE',
  chat,
});

describe('chatPlaceView', () => {
  it.each`
    case                       | conversation                                                                        | badge                                     | open
    ${'an app conversation'}   | ${{ place: 'TINFIRM_THREAD', origin: 'APP', chat: null }}                           | ${null}                                   | ${null}
    ${'a tracker issue'}       | ${{ place: 'TRACKER_ISSUE', origin: 'TRIGGER', chat: null }}                        | ${null}                                   | ${null}
    ${'a Slack DM'}            | ${dm(SLACK)}                                                                        | ${'Direct message in Slack · Acme Slack'} | ${SLACK.permalink}
    ${'a Teams DM'}            | ${dm({ ...SLACK, systemKind: 'TEAMS', systemName: 'Acme Teams', permalink: null })} | ${'Direct message in Teams · Acme Teams'} | ${null}
    ${'a removed system’s DM'} | ${dm({ systemId: IDS.slack, systemName: null, systemKind: null })}                  | ${'Direct message in a chat'}             | ${null}
    ${'a DM without its chat'} | ${dm(null)}                                                                         | ${'Direct message in a chat'}             | ${null}
    ${'a channel thread'}      | ${{ place: 'CHAT_THREAD', origin: 'TRIGGER', chat: SLACK }}                         | ${'Chat thread in Slack · Acme Slack'}    | ${SLACK.permalink}
  `('reads $case', ({ conversation, badge, open }) => {
    const view = chatPlaceView(conversation);
    expect(view?.badge ?? null).toBe(badge);
    expect(view?.openUrl ?? null).toBe(open);
  });

  it('tells the composer to write in the chat, and that cards are answered here', () => {
    expect(chatPlaceView(dm(SLACK))?.composer).toBe(
      'This conversation lives in your direct message with the Tinfirm bot: write to it in Slack. Questions, approvals and Connect cards can be answered here too.',
    );
  });
});

describe('ChatPlace', () => {
  function create(conversation: ChatPlaced) {
    const fixture = TestBed.createComponent(ChatPlace);
    fixture.componentRef.setInput('conversation', conversation);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it.each`
    case                     | conversation                                              | badge                                     | open
    ${'an app conversation'} | ${{ place: 'TINFIRM_THREAD', origin: 'APP', chat: null }} | ${null}                                   | ${null}
    ${'a DM, no permalink'}  | ${dm({ ...SLACK, permalink: null })}                      | ${'Direct message in Slack · Acme Slack'} | ${null}
    ${'a DM with its link'}  | ${dm(SLACK)}                                              | ${'Direct message in Slack · Acme Slack'} | ${'Open in Slack'}
  `('draws $case', ({ conversation, badge, open }) => {
    const el = create(conversation);
    expect(el.querySelector('[data-testid=chat-place]')?.textContent?.trim() ?? null).toBe(badge);
    const link = el.querySelector<HTMLAnchorElement>('[data-testid=chat-place-open]');
    expect(link?.textContent?.trim() ?? null).toBe(open);
    if (link) {
      expect(link.getAttribute('href')).toBe(SLACK.permalink);
      expect(link.getAttribute('rel')).toBe('noopener noreferrer');
    }
  });
});
