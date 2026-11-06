import type {
  ChatChannel,
  ChatLink,
  ExternalSystem,
  InboxItem,
  MyAccount,
  WebhookDelivery,
  WebhookMapped,
} from '../../api/generated/models';
import { AT, IDS } from './ids';
import { aSystem } from './systems';

/**
 * Tags `Chat`, `ChatLinks` and `Webhooks` (package 5): Acme's Slack and Teams, a sign-in link, an inbox
 * item, a channel, and the ops webhook with a delivery and a mapping's try.
 */

/** Acme's Slack, Events API, with its bot and signing secret: ready. */
export function aSlackSystem(overrides: Partial<ExternalSystem> = {}): ExternalSystem {
  return aSystem({
    id: IDS.slack,
    name: 'Acme Slack',
    handle: 'slack',
    kind: 'SLACK',
    baseUrl: 'https://slack.com/api',
    oauthAvailable: false,
    app: null,
    oauthCallbackUrl: null,
    chat: {
      transport: 'EVENTS_API',
      teamId: 'T02XYZ789',
      teamName: 'Acme',
      botSet: true,
      botCredentialId: IDS.slackBotAccount,
      botCredentialName: 'Bot',
      botPrincipalId: IDS.svcSlackBot,
      botPrincipalName: 'svc-slack-bot',
      botUserId: 'U07BOT001',
      signingSecretSet: true,
      appTokenSet: false,
      eventsUrl: `https://tinfirm.acme.test/api/chat/slack/${IDS.slack}/events`,
      interactionsUrl: `https://tinfirm.acme.test/api/chat/slack/${IDS.slack}/interactions`,
      messagingUrl: null,
    },
    ...overrides,
  });
}

/** A Slack system just registered: no bot, no secrets, its workspace not known yet. */
export function aNewSlackSystem(overrides: Partial<ExternalSystem> = {}): ExternalSystem {
  const base = aSlackSystem();
  return {
    ...base,
    status: 'NEW',
    chat: {
      ...base.chat!,
      teamId: null,
      teamName: null,
      botSet: false,
      botCredentialId: null,
      botCredentialName: null,
      botPrincipalId: null,
      botPrincipalName: null,
      botUserId: null,
      signingSecretSet: false,
    },
    ...overrides,
  };
}

export function aTeamsSystem(overrides: Partial<ExternalSystem> = {}): ExternalSystem {
  return aSystem({
    id: IDS.teams,
    name: 'Acme Teams',
    handle: 'teams',
    kind: 'TEAMS',
    baseUrl: 'https://smba.trafficmanager.net',
    oauthAvailable: false,
    app: null,
    oauthCallbackUrl: null,
    chat: {
      transport: null,
      tenantId: '72f988bf-86f1-41af-91ab-2d7cd011db47',
      appId: '0b9c1f5e-3d4a-4b8c-9e2f-6a7b8c9d0e1f',
      botSet: true,
      botCredentialId: 'acc-svc-teams-bot',
      botCredentialName: 'Bot',
      botPrincipalId: 'p-svc-teams-bot',
      botPrincipalName: 'svc-teams-bot',
      signingSecretSet: false,
      appTokenSet: false,
      messagingUrl: `https://tinfirm.acme.test/api/chat/teams/${IDS.teams}/messages`,
    },
    ...overrides,
  });
}

/** What Jane's sign-in link links, as `POST /chat-links/read` answers. */
export function aChatLink(overrides: Partial<ChatLink> = {}): ChatLink {
  return {
    systemId: IDS.slack,
    systemKind: 'SLACK',
    systemName: 'Acme Slack',
    chatUserId: 'U04ABC123',
    displayName: 'Jane Doe',
    workspaceId: 'T02XYZ789',
    workspaceName: 'Acme',
    requestedAt: AT.start,
    expiresAt: '2026-10-12T08:23:00Z',
    linked: false,
    accountId: null,
    ownerNotice:
      'Conversations you start from this chat are personal: the Owner of Acme can read them for support and audit.',
    ...overrides,
  };
}

/** A member's row of a chat system on Accounts: linked, or not yet. */
export function aChatAccount(linked = true, overrides: Partial<MyAccount> = {}): MyAccount {
  return {
    system: { id: IDS.slack, name: 'Acme Slack', handle: 'slack', kind: 'SLACK', personal: false },
    account: linked
      ? {
          id: 'acc-alice-slack',
          systemId: IDS.slack,
          kind: 'LINK',
          status: 'OK',
          login: 'U04ABC123',
          connectedAt: AT.earlier,
          lastUsedAt: AT.start,
        }
      : null,
    grants: [],
    binding: linked ? 'OWN' : 'CONNECT',
    oauthAvailable: false,
    ...overrides,
  };
}

export function aChatInboxItem(
  kind: 'CHAT_LINKED' | 'CHAT_SUBSCRIPTION_SILENT' = 'CHAT_LINKED',
  overrides: Partial<InboxItem> = {},
): InboxItem {
  return {
    kind,
    since: AT.start,
    chat:
      kind === 'CHAT_LINKED'
        ? {
            systemId: IDS.slack,
            systemName: 'Acme Slack',
            text: 'Your account was linked to Slack user Jane Doe (U04ABC123) of Acme at 10:05.',
            unlinkSystemId: IDS.slack,
            chatUserId: 'U04ABC123',
            chatUserName: 'Jane Doe',
          }
        : {
            systemId: IDS.slack,
            systemName: 'Acme Slack',
            text: 'Slack stopped sending this system’s events after failed deliveries. Re-enable them on the app’s Event Subscriptions page.',
            unlinkSystemId: null,
          },
    ...overrides,
  };
}

/** A channel the bot is in, with the support trigger as its default. */
export function aChatChannel(overrides: Partial<ChatChannel> = {}): ChatChannel {
  return {
    channelId: 'C01SUPPORT',
    name: 'support',
    kind: 'PUBLIC',
    teamId: null,
    archived: false,
    available: true,
    hasDefault: true,
    triggers: [{ triggerId: 't-support', name: 'Support desk', agentId: IDS.agent, default: true }],
    ...overrides,
  };
}

/** Acme's ops webhook: a secret header, its mapping, its own rate. */
export function aWebhookSystem(overrides: Partial<ExternalSystem> = {}): ExternalSystem {
  return aSystem({
    id: IDS.hook,
    name: 'Ops alerts',
    handle: 'ops_alerts',
    kind: 'WEBHOOK',
    baseUrl: null,
    oauthAvailable: false,
    app: null,
    oauthCallbackUrl: null,
    webhook: {
      scheme: 'SECRET_HEADER',
      secretHeader: 'X-Ops-Token',
      mapping: { event: '/action', subjectKey: '/issue/key', subjectTitle: '/issue/title' },
      maxPerMinute: 120,
      perMinute: 120,
      url: `https://tinfirm.acme.test/api/hooks/${IDS.hook}`,
      secretSetAt: AT.earlier,
      previousUntil: null,
      secret: null,
    },
    ...overrides,
  });
}

export function aWebhookDelivery(overrides: Partial<WebhookDelivery> = {}): WebhookDelivery {
  return {
    id: 'd-1',
    deliveryId: 'msg_2KWPBgLlAfxdpx2AI54pPJ85f4W',
    signedAt: AT.start,
    receivedAt: AT.start,
    status: 'FIRED',
    event: 'opened',
    subjectKey: 'OPS-7',
    firings: 1,
    failure: null,
    bodyBytes: 92,
    body: { action: 'opened', issue: { key: 'OPS-7', title: 'Disk full' } },
    ...overrides,
  };
}

export function aWebhookMapped(overrides: Partial<WebhookMapped> = {}): WebhookMapped {
  return {
    event: 'opened',
    subjectKey: 'OPS-7',
    subjectTitle: 'Disk full',
    subjectUrl: null,
    actorId: null,
    actorName: null,
    text: null,
    deliveryId: null,
    failure: null,
    triggers: [{ triggerId: 't-ops', name: 'Ops triage', matches: true }],
    ...overrides,
  };
}
