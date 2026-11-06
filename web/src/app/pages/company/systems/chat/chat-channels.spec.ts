import { HttpTestingController } from '@angular/common/http/testing';
import { type ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import type { ChatChannel, ExternalSystem } from '../../../../api/generated/models';
import { aChatChannel, aNewSlackSystem, aSlackSystem, IDS } from '../../../../testing/fixtures';
import { problem, provideTestHttp, settle } from '../../../../testing/test-helpers';
import { ChatChannels, channelState } from './chat-channels';

const CHANNELS = `/api/companies/c1/systems/${IDS.slack}/chat/channels`;

describe('channelState', () => {
  it.each`
    case                     | channel                                                               | kind         | note
    ${'with its trigger'}    | ${aChatChannel()}                                                     | ${'Public'}  | ${null}
    ${'private, no trigger'} | ${aChatChannel({ kind: 'PRIVATE', triggers: [], hasDefault: false })} | ${'Private'} | ${'No trigger yet: a mention gets a hint'}
    ${'archived'}            | ${aChatChannel({ archived: true, available: false })}                 | ${'Public'}  | ${'Archived: no trigger answers here'}
    ${'shared'}              | ${aChatChannel({ kind: 'SHARED', available: false })}                 | ${'Shared'}  | ${'Shared with another organisation: no trigger answers here'}
    ${'unavailable'}         | ${aChatChannel({ available: false })}                                 | ${'Public'}  | ${'Unavailable: no trigger answers here'}
  `('reads a channel $case', ({ channel, kind, note }) => {
    expect(channelState(channel)).toEqual({ kind, note });
  });
});

describe('ChatChannels', () => {
  let fixture: ComponentFixture<ChatChannels>;
  let http: HttpTestingController;

  const el = () => fixture.nativeElement as HTMLElement;
  const by = (testId: string) => el().querySelector<HTMLElement>(`[data-testid=${testId}]`);
  const text = (node: Element | null | undefined) => node?.textContent?.replace(/\s+/g, ' ').trim();

  async function create(system: ExternalSystem, items: ChatChannel[] | 'fail') {
    TestBed.configureTestingModule({ providers: [provideRouter([]), ...provideTestHttp()] });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ChatChannels);
    fixture.componentRef.setInput('companyId', 'c1');
    fixture.componentRef.setInput('system', system);
    fixture.detectChanges();
    await settle();
    if (!system.chat?.botSet) {
      // Without a bot nothing is asked: the server's minute stays for the bot's first list.
      http.expectNone((r) => r.url === CHANNELS);
      fixture.detectChanges();
      return;
    }
    const req = http.expectOne((r) => r.url === CHANNELS);
    if (items === 'fail') req.flush(problem(500), { status: 500, statusText: 'Error' });
    else req.flush({ items });
    await settle();
    fixture.detectChanges();
  }

  afterEach(() => http.verify());

  it('lists the channels with their triggers, the default marked, each a link', async () => {
    await create(aSlackSystem(), [
      aChatChannel(),
      aChatChannel({
        channelId: 'C02OPS',
        name: 'ops',
        kind: 'PRIVATE',
        triggers: [],
        hasDefault: false,
      }),
    ]);
    const rows = el().querySelectorAll('[data-testid=channel]');
    expect(rows).toHaveLength(2);
    const trigger = rows[0].querySelector<HTMLAnchorElement>('[data-testid=channel-trigger]')!;
    expect(text(trigger)).toBe('Support desk · default');
    expect(trigger.getAttribute('href')).toBe('/c/c1/triggers/t-support');
    expect(text(rows[1])).toContain('No trigger yet');
  });

  it('says a default trigger the reader does not see exists', async () => {
    await create(aSlackSystem(), [aChatChannel({ triggers: [] })]);
    expect(text(by('chat-channels'))).toContain("Has a default trigger you don't see");
  });

  it.each`
    case               | system               | says
    ${'with a bot'}    | ${aSlackSystem()}    | ${'The bot is in no channel yet.'}
    ${'without a bot'} | ${aNewSlackSystem()} | ${'The bot is in no channel: set the bot first.'}
  `('says why there are none, $case', async ({ system, says }) => {
    await create(system, []);
    expect(text(by('channels-empty'))).toContain(says);
  });

  it('asks again with the query once the typing pauses', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      await create(aSlackSystem(), [aChatChannel()]);
      const input = by('channel-query') as HTMLInputElement;
      input.value = 'sup';
      input.dispatchEvent(new Event('input'));
      vi.advanceTimersByTime(300);
      await settle();
      const req = http.expectOne((r) => r.url === CHANNELS);
      expect(req.request.params.get('query')).toBe('sup');
      req.flush({ items: [] });
      await settle();
      fixture.detectChanges();
      expect(text(by('channels-empty'))).toContain(`No channel of the bot's matches "sup".`);
    } finally {
      vi.useRealTimers();
    }
  });

  it('says it could not read them, with retry', async () => {
    await create(aSlackSystem(), 'fail');
    expect(text(el())).toContain("Couldn't read the channels");
  });
});
