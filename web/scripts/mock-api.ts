import type { Page, Route } from '@playwright/test';
import type { CurrentUser } from '../src/app/api/generated/models';
import {
  aBorrowedCredential,
  aCompany,
  aConversation,
  aGrantChoice,
  aGroup,
  aGroupRef,
  aGroupSummary,
  aGroupUsage,
  aHold,
  aJiraSystem,
  aMember,
  aMemberAccount,
  aModelAccess,
  aModelSettings,
  aMyAccount,
  anAgent,
  anAgentSummary,
  anErasurePreview,
  anInbox,
  aPersonalRunnerHost,
  aRetention,
  aRetentionPreview,
  aRun,
  aRunnerHost,
  aServiceIdentity,
  aSubscription,
  aSystem,
  aSystemCredential,
  aSystemCredentialList,
  aSystemRef,
  aSystemRepository,
  aSystemTool,
  aWebhookDelivery,
  aWebhookSystem,
  AT,
  IDS,
  aLicence,
  noSweep,
  noSwitches,
  runEvents,
  toolCall,
} from '../src/app/testing/fixtures';

/**
 * The API the screenshots run against: the fixtures of the component specs, answered by
 * Playwright for every `/api/` request of the page, so that `ng serve` needs no server. A request
 * the table does not know is a 404 problem, and is listed in `unknown` for the script to report.
 */
/** The time the given minutes before now, as the API writes it. */
function ago(minutes: number): string {
  return new Date(Date.now() - minutes * 60_000).toISOString();
}

export async function mockApi(page: Page, user: CurrentUser): Promise<{ unknown: string[] }> {
  const role = user.memberships[0]?.role;
  const company = `/api/companies/${IDS.company}`;
  const unknown: string[] = [];
  const members = [
    aMember({
      userId: IDS.ownerUser,
      displayName: 'Olga Owner',
      email: 'olga@acme.test',
      role: 'OWNER',
    }),
    aMember({
      userId: IDS.bobUser,
      displayName: 'Bob Svoboda',
      email: 'bob@acme.test',
      role: 'ADMIN',
    }),
    aMember(),
    aMember({ userId: IDS.carolUser, displayName: 'Carol Dvořák', email: 'carol@acme.test' }),
    aMember({ userId: IDS.daveUser, displayName: 'Dave Malý', email: 'dave@acme.test' }),
  ];

  const answers: [RegExp, () => unknown][] = [
    [/^\/api\/me$/, () => user],
    [/^\/api\/me\/oauth\/grants$/, () => []],
    [/^\/api\/me\/tokens$/, () => []],
    [/^\/api\/system\/info$/, () => ({ version: '0.6.0' })],
    // The instance's licence (ADR-0077): Pro, every Pro feature in force.
    [/^\/api\/licence$/, () => aLicence()],
    [new RegExp(`^${company}$`), () => aCompany({ myRole: role })],
    [new RegExp(`^${company}/inbox$`), () => anInbox()],
    // A notice dismissed or shown again (`PUT`, `DELETE`): no content.
    [new RegExp(`^${company}/inbox/dismissals/[^/]+$`), () => null],
    // Nothing switched off: the company runs, no one is suspended, nothing is paused.
    [new RegExp(`^${company}/kill-switches$`), () => noSwitches()],
    [new RegExp(`^${company}/members$`), () => ({ items: members })],
    [new RegExp(`^${company}/groups$`), () => ({ items: [aGroupSummary()] })],
    [new RegExp(`^${company}/secrets$`), () => ({ items: [] })],
    [new RegExp(`^${company}/audit-events$`), () => ({ items: [] })],
    [new RegExp(`^${company}/runs/${IDS.run}$`), () => aRun()],
    [
      new RegExp(`^${company}/runs/${IDS.run}/events$`),
      () => ({
        complete: true,
        items: runEvents(
          ['STATUS', { phase: 'running' }],
          toolCall('t1', 'Read src/checkout/guest.ts', 'read'),
          [
            'AGENT_MESSAGE',
            { text: 'The brief is ready: the guest flow skips the address check.' },
          ],
        ),
      }),
    ],
    [new RegExp(`^${company}/conversations/${IDS.conversation}$`), () => aConversation()],
    // Accounts (W1): the reader's accounts, budget and personal runner hosts.
    [
      new RegExp(`^${company}/me/accounts$`),
      () => ({
        items: [
          aMyAccount(),
          aMyAccount({
            system: aSystemRef({ id: IDS.jira, name: 'Jira', handle: 'jira', kind: 'JIRA_DC' }),
            account: null,
            binding: 'GRANTED',
            grants: [aGrantChoice()],
            oauthAvailable: false,
          }),
        ],
      }),
    ],
    [new RegExp(`^${company}/system-settings$`), () => ({ personalMcpServers: true })],
    // The limits on chats and webhooks, the instance's defaults.
    [
      new RegExp(`^${company}/chat-settings$`),
      () => ({
        version: 1,
        messagesPerUserPerMinute: 20,
        messagesPerUserPerDay: 200,
        eventsPerSystemPerMinute: 600,
      }),
    ],
    [
      new RegExp(`^${company}/settings$`),
      () => ({
        version: 1,
        triggerFiringsPerHour: { default: 30, max: 300 },
        triggerSubscriptions: false,
        webhookDeliveriesPerMinute: { default: 60, max: 600 },
      }),
    ],
    [
      new RegExp(`^${company}/me/model-access$`),
      () => aModelAccess({ subscriptions: [aSubscription()] }),
    ],
    [
      new RegExp(`^${company}/runner-hosts$`),
      () => ({ items: [aRunnerHost(), aPersonalRunnerHost()] }),
    ],
    // Model access (W2b).
    [new RegExp(`^${company}/model-settings$`), () => aModelSettings()],
    [
      new RegExp(`^${company}/model-settings/models$`),
      () => ({ models: ['claude-sonnet-4-5', 'claude-haiku-4-5', 'gpt-5.4'] }),
    ],
    // The company's own key (ADR-0065), with its own budget, and the model that tidies memories.
    [
      new RegExp(`^${company}/model-access/company-key$`),
      () => ({
        ...aModelAccess({
          budgetUsd: 5,
          budgetOverridden: true,
          spentUsd: 0.42,
          models: ['claude-haiku-4-5'],
          modelsOverridden: true,
        }),
        principalId: undefined,
      }),
    ],
    [
      new RegExp(`^${company}/memory-settings$`),
      () => ({ compactionModel: 'claude-haiku-4-5', compactionModelAllowed: true }),
    ],
    // Systems and service identities (W2a).
    [new RegExp(`^${company}/systems$`), () => ({ items: [aJiraSystem(), aSystem()] })],
    // The stored last test (V043), whoever ran it, as an admin reads it.
    [
      new RegExp(`^${company}/systems/${IDS.github}$`),
      () =>
        aSystem({
          lastTest: {
            at: ago(2 * 60),
            ok: true,
            withAccount: true,
            byPrincipalId: IDS.bob,
            byName: 'Bob Svoboda',
            credentialId: IDS.readerGithubAccount,
            credentialName: 'svc-reader',
          },
        }),
    ],
    // A webhook system, its deliveries and their last 7 days.
    [new RegExp(`^${company}/systems/${IDS.hook}$`), () => aWebhookSystem()],
    [
      new RegExp(`^${company}/systems/${IDS.hook}/webhook/deliveries$`),
      () => ({
        items: [aWebhookDelivery({ receivedAt: ago(12), signedAt: ago(12) })],
        nextCursor: 'more',
        lastSevenDays: { since: ago(7 * 24 * 60), deliveries: 312, failed: 2, refused: 4 },
      }),
    ],
    [
      new RegExp(`^${company}/systems/[^/]+/repositories$`),
      () => ({ items: [aSystemRepository()] }),
    ],
    [
      new RegExp(`^${company}/systems/[^/]+/tools$`),
      () => ({
        items: [
          aSystemTool({ name: 'get_issue', title: 'Read an issue', effect: 'READ' }),
          aSystemTool(),
        ],
      }),
    ],
    [new RegExp(`^${company}/service-identities$`), () => ({ items: [aServiceIdentity()] })],
    [new RegExp(`^${company}/service-identities/${IDS.svcReader}$`), () => aServiceIdentity()],
    [
      new RegExp(`^${company}/service-identities/[^/]+/credentials$`),
      () => ({ items: [aBorrowedCredential()] }),
    ],
    // A system's credentials (ADR-0072), on its page.
    [
      new RegExp(`^${company}/systems/[^/]+/credentials$`),
      () =>
        aSystemCredentialList([
          aSystemCredential(),
          aSystemCredential({
            id: 'cred-ci',
            name: 'CI bot',
            kind: 'OAUTH',
            status: 'RECONNECT',
            login: 'acme-ci',
            lastError: 'The grant expired',
            grants: [],
            serviceAccounts: [],
          }),
        ]),
    ],
    [
      new RegExp(`^${company}/principals/[^/]+/model-access$`),
      () => aModelAccess({ principalId: IDS.svcReader }),
    ],
    // Members and its Groups tab: each member's accounts, the members who left, each group's
    // members and what it may use; the agents, shared with the groups, for the agents' pages.
    [new RegExp(`^${company}/departures$`), () => ({ items: [] })],
    [new RegExp(`^${company}/members/[^/]+/accounts$`), () => ({ items: [aMemberAccount()] })],
    [new RegExp(`^${company}/groups/[^/]+$`), () => aGroup()],
    [new RegExp(`^${company}/groups/[^/]+/members/[^/]+$`), () => aGroup()],
    [new RegExp(`^${company}/groups/[^/]+/usage$`), () => aGroupUsage()],
    [new RegExp(`^${company}/agents$`), () => ({ items: [anAgentSummary()] })],
    [new RegExp(`^${company}/agents/[^/]+$`), () => anAgent({ shares: [aGroupRef()] })],
    // Retention (W7): the periods, what the next sweep removes, a hold, and the previews of a
    // change of the periods and of an erasure.
    [
      new RegExp(`^${company}/retention$`),
      () =>
        aRetention({
          openOlder: 1,
          lastSweptAt: AT.start,
          due: { conversations: 2, runs: 31, files: 0 },
          nextSweep: noSweep({ conversations: 2, runEvents: 31 }),
        }),
    ],
    [
      new RegExp(`^${company}/retention/open-conversations$`),
      () => ({
        items: [
          {
            id: IDS.conversation,
            key: 'SHOP-241-1',
            place: 'CHAT_THREAD',
            createdAt: AT.earlier,
            ageDays: 41,
          },
        ],
      }),
    ],
    [new RegExp(`^${company}/retention/preview$`), () => aRetentionPreview()],
    [
      new RegExp(`^${company}/holds$`),
      () => ({
        items: [
          aHold({
            principalId: IDS.carol,
            principalName: 'Carol Dvořák',
            reason: 'Litigation with a supplier: counsel asked us to keep everything.',
            setByUserId: IDS.ownerUser,
          }),
        ],
      }),
    ],
    [new RegExp(`^${company}/principals/[^/]+/erase/preview$`), () => anErasurePreview()],
    [
      new RegExp(`^${company}/systems/[^/]+/identities/[^/]+/erase/preview$`),
      () =>
        anErasurePreview({
          erases: {
            conversations: 0,
            messages: 0,
            decisions: 0,
            outputs: 0,
            files: 0,
            runs: 0,
            runEvents: 2,
            other: { messages: 3, decisions: 1, chatUsers: 1 },
          },
        }),
    ],
  ];

  await page.route('**/api/**', async (route: Route) => {
    const url = new URL(route.request().url());
    const answer = answers.find(([pattern]) => pattern.test(url.pathname));
    if (answer) {
      await route.fulfill({ json: answer[1]() });
      return;
    }
    unknown.push(`${route.request().method()} ${url.pathname}`);
    await route.fulfill({
      status: 404,
      contentType: 'application/problem+json',
      json: { status: 404, title: 'Not Found' },
    });
  });
  return { unknown };
}
