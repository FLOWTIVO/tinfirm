import { HttpTestingController } from '@angular/common/http/testing';
import { type ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import type { Trigger } from '../../../api/generated/models';
import {
  aChannelTrigger,
  aModelAccess,
  anAgent,
  anAgentSettings,
  aWebhookTrigger,
  IDS,
  someMemoryNotes,
} from '../../../testing/fixtures';
import { serve } from '../../../testing/serve';
import { fakeCurrentCompany, fakeNotify, provideTestHttp } from '../../../testing/test-helpers';
import { words } from '../../../testing/words';
import {
  createRequest,
  defaultSettingsDraft,
  draftErrors,
  draftOf,
  emptyDraft,
  type TriggerDraft,
  updateRequest,
} from './trigger-draft';
import { triggerFailure } from './trigger-errors';
import { sourceKinds, sourceText } from './trigger-labels';
import { TriggerPage } from './trigger-page';
import { TriggerChannelsField } from './trigger-channels-field';
import { HttpErrorResponse } from '@angular/common/http';

/** A draft of a source of package 5b, ready but for what a case leaves out. */
function aDraft(overrides: Partial<TriggerDraft>): TriggerDraft {
  return {
    ...emptyDraft('UTC'),
    name: 'Support answers',
    agentId: IDS.agent,
    agentVersion: 4,
    instruction: 'Answer.',
    payerPrincipalId: IDS.svcReader,
    settings: defaultSettingsDraft(),
    ...overrides,
  };
}

describe('channel and webhook triggers (package 5b)', () => {
  it.each([
    ['CHANNEL', ['SLACK', 'TEAMS']],
    ['WEBHOOK', ['WEBHOOK']],
    ['SCHEDULE', []],
  ] as const)('offers the systems a %s belongs to', (source, kinds) => {
    expect(sourceKinds(source)).toEqual(kinds);
  });

  it.each([
    ['CHANNEL', 'Slack', 'Slack channels'],
    ['WEBHOOK', 'CRM hooks', 'CRM hooks webhook'],
    ['TRACKER', 'Jira', 'Jira'],
    ['SCHEDULE', null, 'Schedule'],
  ] as const)('names a %s source', (source, name, text) => {
    expect(sourceText(source, name)).toBe(text);
  });

  it('sends a channel trigger: its channels, one mention event, no account on its chat', () => {
    const body = createRequest(
      aDraft({
        source: 'CHANNEL',
        systemId: 'sys-slack',
        channels: [
          { channelId: 'C1', name: 'support', default: true },
          { channelId: 'C2', name: 'sales', default: false },
        ],
        results: { comment: true, review: true, onSuccess: [{ kind: 'CLOSE' }], onFailure: [] },
      }),
    );
    expect(body).toMatchObject({
      source: 'CHANNEL',
      systemId: 'sys-slack',
      events: [{ event: 'MENTIONED', value: null }],
      channels: [
        { channelId: 'C1', default: true },
        { channelId: 'C2', default: false },
      ],
      webhookConditions: [],
      results: { comment: false, review: true, onSuccess: [], onFailure: [] },
    });
    // No row is required on the chat: its bot reads and answers there.
    expect(
      draftErrors(
        aDraft({
          source: 'CHANNEL',
          systemId: 'sys-slack',
          channels: [{ channelId: 'C1', name: null, default: false }],
        }),
      ),
    ).toEqual({});
  });

  it('sends a webhook trigger: its mapped events, or any delivery, and its conditions', () => {
    const body = createRequest(
      aDraft({
        source: 'WEBHOOK',
        systemId: 'sys-hooks',
        webhookEvents: 'issue.created, issue.updated',
        webhookConditions: [
          { pointer: '/issue/priority', op: 'IN', values: 'High, Highest' },
          { pointer: '/issue/assignee', op: 'PRESENT', values: 'ignored' },
        ],
      }),
    );
    expect(body.events).toEqual([
      { event: 'DELIVERED', value: 'issue.created' },
      { event: 'DELIVERED', value: 'issue.updated' },
    ]);
    expect(body.webhookConditions).toEqual([
      { pointer: '/issue/priority', op: 'IN', values: ['High', 'Highest'] },
      { pointer: '/issue/assignee', op: 'PRESENT', values: [] },
    ]);
    expect(createRequest(aDraft({ source: 'WEBHOOK', systemId: 'sys-hooks' })).events).toEqual([
      { event: 'DELIVERED', value: null },
    ]);
  });

  it.each<[string, Partial<TriggerDraft>, string]>([
    [
      'a chat without channels',
      { source: 'CHANNEL', systemId: 'sys-slack' },
      'Pick at least one channel.',
    ],
    ['no chat', { source: 'CHANNEL' }, 'Pick the chat whose channels it listens in.'],
    ['no webhook', { source: 'WEBHOOK' }, 'Pick the webhook that starts it.'],
    [
      'a condition that is no pointer',
      {
        source: 'WEBHOOK',
        systemId: 'h',
        webhookConditions: [{ pointer: 'issue', op: 'PRESENT', values: '' }],
      },
      'Each condition reads a JSON Pointer',
    ],
    [
      'EQUALS with two values',
      {
        source: 'WEBHOOK',
        systemId: 'h',
        webhookConditions: [{ pointer: '/a', op: 'EQUALS', values: 'x, y' }],
      },
      'Each condition reads a JSON Pointer',
    ],
  ])('says before the preview: %s', (_case, overrides, says) => {
    expect(draftErrors(aDraft(overrides)).when).toContain(says);
  });

  it('reads stored channel and webhook triggers back, and sends their parts on a change', () => {
    const channel = draftOf(aChannelTrigger(), anAgentSettings());
    expect(channel.channels).toEqual([
      { channelId: 'C1', name: 'support', default: true },
      { channelId: 'C2', name: 'sales', default: false },
    ]);
    expect(updateRequest(channel).channels).toHaveLength(2);
    expect(updateRequest(channel).events).toBeNull();
    const webhook = draftOf(aWebhookTrigger(), anAgentSettings());
    expect(webhook.webhookEvents).toBe('issue.created, issue.updated');
    expect(webhook.webhookConditions).toEqual([
      { pointer: '/issue/priority', op: 'IN', values: 'High, Highest' },
    ]);
    expect(updateRequest(webhook).webhookConditions).toHaveLength(1);
  });

  it.each([
    'CHANNEL_UNAVAILABLE',
    'CHANNEL_NOT_JOINED',
    'CHANNEL_TAKEN',
    'BOT_NOT_SET',
    'MAPPING_INVALID',
  ])('places %s on the rule', (code) => {
    const error = new HttpErrorResponse({ status: 400, error: { status: 400, code, title: 'x' } });
    expect(triggerFailure(error).at).toBe('when');
  });

  describe('the trigger page', () => {
    let fixture: ComponentFixture<TriggerPage>;
    let http: HttpTestingController;

    async function open(trigger: Trigger) {
      TestBed.configureTestingModule({
        imports: [TriggerPage],
        providers: [
          provideRouter([]),
          ...provideTestHttp(),
          fakeCurrentCompany('MEMBER').provider,
          fakeNotify().provider,
        ],
      });
      http = TestBed.inject(HttpTestingController);
      fixture = TestBed.createComponent(TriggerPage);
      fixture.componentRef.setInput('triggerId', trigger.id);
      await serve(http, {
        [`GET /api/companies/c1/triggers/${trigger.id}`]: trigger,
        [`GET /api/companies/c1/triggers/${trigger.id}/firings`]: { items: [] },
        [`GET /api/companies/c1/triggers/${trigger.id}/memory`]: someMemoryNotes({
          inEffect: false,
          notes: [],
        }),
        [`GET /api/companies/c1/agents/${trigger.agentId}`]: anAgent(),
        [`GET /api/companies/c1/me/model-access`]: aModelAccess(),
      });
      return fixture.nativeElement as HTMLElement;
    }

    afterEach(() => http.verify());

    it('shows a channel trigger’s channels, and no run now: a mention fires it', async () => {
      const el = await open(aChannelTrigger());
      expect(words(el.querySelector('[data-testid=trigger-meta]'))).toContain('Slack channels');
      expect(words(el.querySelector('[data-channel=C1]'))).toBe('#support · its default');
      expect(words(el.querySelector('[data-channel=C2]'))).toContain('the bot cannot use it now');
      expect(el.querySelector('[data-testid=run-now]')).toBeNull();
      expect(words(el.querySelector('[data-testid=trigger-results]'))).toContain(
        'Each answer is posted in its thread by the chat’s bot.',
      );
    });

    it('shows a webhook trigger’s events and conditions, and no run now', async () => {
      const el = await open(aWebhookTrigger());
      expect(words(el.querySelector('[data-testid=trigger-webhook-events]'))).toBe(
        'CRM hooks delivers issue.created, issue.updated',
      );
      expect(words(el.querySelector('[data-testid=trigger-rule]'))).toContain(
        '/issue/priority is one of High, Highest',
      );
      expect(el.querySelector('[data-testid=run-now]')).toBeNull();
    });

    it('says a channel trigger waits for its bot', async () => {
      const el = await open(aChannelTrigger({ waitingReason: 'BOT_RECONNECT' }));
      expect(words(el.querySelector('[data-testid=waiting-notice]'))).toContain(
        'Waits for an admin to sign the chat’s bot in again',
      );
    });
  });

  describe('the channel picker', () => {
    it('offers the channels the bot is in, one default per channel', async () => {
      TestBed.configureTestingModule({
        providers: [...provideTestHttp(), fakeCurrentCompany('MEMBER').provider],
      });
      const http = TestBed.inject(HttpTestingController);
      const fixture = TestBed.createComponent(TriggerChannelsField);
      fixture.componentRef.setInput('systemId', 'sys-slack');
      fixture.componentRef.setInput('draft', []);
      const served = await serve(http, {
        'GET /api/companies/c1/systems/sys-slack/chat/channels': {
          items: [
            {
              channelId: 'C1',
              name: 'support',
              kind: 'PUBLIC',
              archived: false,
              available: true,
              hasDefault: false,
              triggers: [],
            },
            {
              channelId: 'C2',
              name: 'sales',
              kind: 'PUBLIC',
              archived: false,
              available: true,
              hasDefault: true,
              triggers: [{ triggerId: 't9', name: 'Sales desk', agentId: 'a9', default: true }],
            },
            {
              channelId: 'C3',
              name: 'old',
              kind: 'PRIVATE',
              archived: true,
              available: false,
              hasDefault: false,
              triggers: [],
            },
          ],
        },
      });
      expect(served).toHaveLength(1);
      const el = fixture.nativeElement as HTMLElement;
      const box = (id: string) => el.querySelector<HTMLInputElement>(`[data-channel=${id}] input`)!;
      expect(box('C3').disabled).toBe(true);
      expect(words(el.querySelector('[data-channel=C3]'))).toContain(
        'the bot cannot use it: archived',
      );
      box('C1').click();
      box('C2').click();
      fixture.detectChanges();
      const picked = fixture.componentInstance.draft();
      expect(picked.map((c) => c.channelId)).toEqual(['C1', 'C2']);
      // #sales has another trigger's default: its own default is not offered.
      const defaults = (id: string) =>
        el.querySelectorAll<HTMLInputElement>(`[data-channel=${id}] input`)[1];
      expect(defaults('C2').disabled).toBe(true);
      defaults('C1').click();
      expect(fixture.componentInstance.draft()[0].default).toBe(true);
      http.verify();
    });
  });
});
