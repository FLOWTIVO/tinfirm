import type { InboxChat, InboxItemKind } from '../../../api/generated/models';
import {
  aChatInboxItem,
  aDecision,
  aGrantChoice,
  aMyAccount,
  anApproval,
  INBOX_ITEMS,
  IDS,
} from '../../../testing/fixtures';
import {
  decidedView,
  enabledView,
  INBOX_KIND_GROUPS,
  inboxBindingCard,
  inboxGroupOf,
  inboxItemKey,
  inboxRowView,
  isPlainLine,
  licenceNoticeWords,
  plainLine,
  retriedView,
  unlinkLabel,
  unlisted,
  waitedText,
  waitingRun,
} from './inbox-words';

const MEMBER = { admin: false, owner: false };
const OWNER = { admin: true, owner: true };

interface RowCase {
  readonly kind: InboxItemKind;
  readonly label: string;
  readonly title: string;
  readonly tone: 'human' | 'destructive' | 'muted';
  readonly link: string | null;
  readonly action: string | null;
}

const CONVERSATION = `conversations/${IDS.conversation}`;

/** Every kind once (plan §5): its kind's label, title, tone, link and action for the Owner. */
const ROWS: Record<InboxItemKind, Omit<RowCase, 'kind'>> = {
  DECISION: {
    label: 'Approval',
    title: 'Business analyst wants to comment on SHOP-241',
    tone: 'human',
    link: CONVERSATION,
    action: null,
  },
  BINDING: {
    label: 'Connect Jira',
    title: 'Business analyst asked to use Jira: connect your own account',
    tone: 'human',
    link: CONVERSATION,
    action: null,
  },
  FAILED: {
    label: 'Failed',
    title: 'Usage limit reached',
    tone: 'destructive',
    link: CONVERSATION,
    action: 'retry',
  },
  WAITING: {
    label: 'Waiting',
    title: 'Your budget is spent.',
    tone: 'muted',
    link: CONVERSATION,
    action: null,
  },
  ACCOUNT: {
    label: 'Sign in again',
    title: 'The credential svc-reader of GitHub needs signing in again',
    tone: 'human',
    link: `systems/${IDS.github}`,
    action: 'sign-in',
  },
  AUDIT_CHECK: {
    label: 'Audit log',
    title: 'The daily check of the audit log failed',
    tone: 'human',
    link: 'audit',
    action: null,
  },
  RETENTION_OPEN: {
    label: 'Retention',
    title: '4 open conversations older than the retention period',
    tone: 'muted',
    link: 'conversations',
    action: null,
  },
  BUDGET: {
    label: 'Budget',
    title: '80 % of your budget used',
    tone: 'muted',
    link: 'accounts',
    action: null,
  },
  COMPANY_SPEND: {
    label: 'Spend',
    title: 'The company’s spend crossed its alert amount',
    tone: 'muted',
    link: 'model-access',
    action: null,
  },
  SPEND_SYNC: {
    label: 'Spend',
    title: 'Spend is counted by Tinfirm, not read from the gateway',
    tone: 'muted',
    link: 'model-access',
    action: null,
  },
  TRIGGER: {
    label: 'Trigger',
    title: 'Triage new bugs',
    tone: 'human',
    link: 'triggers/t-triage',
    action: null,
  },
  DEPARTURE: {
    label: 'Departure',
    title: 'Dave Lin left the company',
    tone: 'human',
    link: 'members',
    action: null,
  },
  CHAT_LINKED: {
    label: 'Slack',
    title: 'A Slack account was linked to you',
    tone: 'muted',
    link: null,
    action: 'unlink',
  },
  CHAT_SUBSCRIPTION_SILENT: {
    label: 'Slack',
    title: 'Slack stopped sending events',
    tone: 'human',
    link: 'systems/sys-slack',
    action: null,
  },
  SEAT_FALLBACK: {
    label: 'Seats',
    title: 'Carol Dvořák joined as a Viewer: every seat was taken',
    tone: 'human',
    link: 'members',
    action: null,
  },
  LICENCE: {
    label: 'Licence',
    title: 'The Pro licence ends on 3 November 2026',
    tone: 'human',
    link: 'settings/licence',
    action: null,
  },
  SCIM_LAST_OWNER: {
    label: 'Provisioning',
    title: 'Your identity provider tried to remove you, the last active owner',
    tone: 'human',
    link: 'members',
    action: null,
  },
};
const ROW_CASES: RowCase[] = Object.entries(ROWS).map(([kind, c]) => ({
  kind: kind as InboxItemKind,
  ...c,
}));

describe('inbox words (plan §5)', () => {
  it.each(ROW_CASES)('says a $kind item', ({ kind, label, title, tone, link, action }) => {
    const view = inboxRowView(INBOX_ITEMS[kind](), OWNER);
    expect(view.kind).toBe(label);
    expect(view.title).toBe(title);
    expect(view.tone).toBe(tone);
    expect(view.link?.commands.join('/') ?? null).toBe(link);
    expect(view.action).toBe(action);
  });

  it('puts the cards and admins’ items first, then the turns that stopped, then notices', () => {
    expect(
      Object.entries(INBOX_KIND_GROUPS)
        .filter(([, g]) => g === 'needs')
        .map(([k]) => k),
    ).toEqual(['DECISION', 'BINDING', 'ACCOUNT', 'AUDIT_CHECK']);
    expect(INBOX_KIND_GROUPS.FAILED).toBe('turns');
    expect(INBOX_KIND_GROUPS.CHAT_LINKED).toBe('notices');
    expect(inboxGroupOf('stopped')).toBe('turns');
    expect(inboxGroupOf('needs')).toBe('needs');
    expect(inboxGroupOf(undefined)).toBeNull();
    expect(inboxGroupOf('nonsense')).toBeNull();
  });

  it('asks a question in its first line, plainly, and says when that is all of it', () => {
    const item = INBOX_ITEMS.DECISION();
    item.decision = aDecision({
      question: 'Should the brief cover the **mobile app** too?\n\nIt has a `beta` flag.',
    });
    expect(inboxRowView(item, MEMBER)).toMatchObject({
      kind: 'Question',
      title: 'Should the brief cover the mobile app too?',
    });
    expect(plainLine('- [The release notes](https://x.test) for _2.4_')).toBe(
      'The release notes for 2.4',
    );
    expect(plainLine('Keep github_comment as it is')).toBe('Keep github_comment as it is');
    expect(isPlainLine('Which release should the notes cover?')).toBe(true);
    expect(isPlainLine('Should it cover the **app**?')).toBe(false);
    expect(isPlainLine('One line\nand another')).toBe(false);
  });

  it('says a turn stopped at its time limit as a stop, with Continue', () => {
    const item = INBOX_ITEMS.FAILED();
    const view = inboxRowView(
      {
        ...item,
        turn: {
          ...item.turn!,
          failureClass: 'TIME_LIMIT',
          failureCause: 'the run reached its time limit (60 min)',
        },
      },
      MEMBER,
    );
    expect(view).toMatchObject({
      kind: 'Time limit',
      title: 'Stopped at its time limit',
      body: 'its work is kept, and Continue goes on from where it stopped',
      tone: 'muted',
      action: 'continue',
    });
  });

  it('says a failed turn’s class and cause', () => {
    expect(inboxRowView(INBOX_ITEMS.FAILED(), MEMBER).body).toBe(
      'The budget of Alice Novak is spent until 1 Nov.',
    );
  });

  it('leaves the audit check neutral for an admin who cannot acknowledge it', () => {
    const view = inboxRowView(INBOX_ITEMS.AUDIT_CHECK(), { admin: true, owner: false });
    expect(view.tone).toBe('muted');
    expect(view.body).toContain('an event does not verify (#412)');
  });

  it('a suspended service account is resumed on its page, not signed in', () => {
    const item = INBOX_ITEMS.ACCOUNT();
    item.account = {
      ...item.account!,
      reason: 'SUSPENDED',
      credential: null,
      holderPrincipalId: IDS.svcReader,
      holderName: 'svc-reader',
    };
    const view = inboxRowView(item, OWNER);
    expect(view.kind).toBe('Suspended');
    expect(view.title).toBe('svc-reader is suspended');
    expect(view.action).toBeNull();
    expect(view.link?.commands.join('/')).toBe(`service-identities/${IDS.svcReader}`);
    expect(view.body).toContain('The credential it borrows on GitHub acts for nothing');
    expect(view.body).toContain('It keeps 3 conversations waiting.');
  });

  it.each`
    case                   | change                                             | kind               | title                                                           | action       | link                   | fragment
    ${'a refused sign-in'} | ${{}}                                              | ${'Sign in again'} | ${'The credential svc-reader of GitHub needs signing in again'} | ${'sign-in'} | ${'Open GitHub'}       | ${'credential-acc-svc-reader-github'}
    ${'a refused token'}   | ${{ kind: 'TOKEN' }}                               | ${'New token'}     | ${'The credential svc-reader of GitHub needs a new token'}      | ${null}      | ${'Store a new token'} | ${'credential-acc-svc-reader-github'}
    ${'a disabled one'}    | ${{ enabled: false }}                              | ${'Disabled'}      | ${'The credential svc-reader of GitHub is disabled'}            | ${'enable'}  | ${'Open GitHub'}       | ${'credential-acc-svc-reader-github'}
    ${'a disabled token'}  | ${{ kind: 'TOKEN', enabled: false, status: 'OK' }} | ${'Disabled'}      | ${'The credential svc-reader of GitHub is disabled'}            | ${'enable'}  | ${'Open GitHub'}       | ${'credential-acc-svc-reader-github'}
  `(
    'offers what $case of a system’s credential needs',
    ({ change, kind, title, action, link, fragment }) => {
      const item = INBOX_ITEMS.ACCOUNT();
      item.account = { ...item.account!, credential: { ...item.account!.credential!, ...change } };
      const view = inboxRowView(item, OWNER);
      expect(view).toMatchObject({ kind, title, action, tone: 'human' });
      expect(view.link).toMatchObject({
        label: link,
        commands: ['systems', IDS.github],
        fragment,
      });
    },
  );

  it('sends a chat system’s bot to its Bot tab, and a credential it cannot name to the system', () => {
    const item = INBOX_ITEMS.ACCOUNT();
    item.account = { ...item.account!, systemId: IDS.slack, systemName: 'Acme Slack', bot: true };
    expect(inboxRowView(item, OWNER)).toMatchObject({
      kind: 'Chat bot',
      title: 'The bot of Acme Slack no longer works',
      action: null,
      link: {
        label: 'Open its bot',
        commands: ['systems', IDS.slack],
        queryParams: { tab: 'bot' },
      },
    });
    item.account = { ...item.account!, bot: false, credential: null };
    expect(inboxRowView(item, OWNER)).toMatchObject({
      title: 'A credential of Acme Slack needs signing in again',
      action: null,
      link: { label: 'Open Acme Slack', commands: ['systems', IDS.slack] },
    });
  });

  it('collapses an enabled credential to what goes on', () => {
    expect(enabledView(INBOX_ITEMS.ACCOUNT())).toEqual({
      label: 'Enabled',
      detail: 'svc-reader',
      after: '3 conversations go on',
    });
  });

  it('a service account’s budget links admins to it, and members nowhere', () => {
    const item = INBOX_ITEMS.BUDGET();
    item.budget = {
      ...item.budget!,
      own: false,
      principalId: IDS.svcReader,
      principalName: 'svc-reader',
      principalKind: 'SERVICE',
      threshold: 100,
      waitingRuns: 2,
      waitingTriggers: 1,
    };
    expect(inboxRowView(item, OWNER).link?.commands).toEqual(['service-identities', IDS.svcReader]);
    expect(inboxRowView(item, OWNER).body).toContain('2 turns wait; 1 live trigger it pays.');
    expect(inboxRowView(item, MEMBER).link).toBeNull();
  });

  it('says a paused trigger is paused, in the reason’s own words', () => {
    const item = INBOX_ITEMS.TRIGGER();
    item.trigger = { ...item.trigger!, reason: 'PAUSED', detail: 'ACCOUNT_BROKEN' };
    expect(inboxRowView(item, MEMBER).body).toBe(
      'Paused: an account it acts with needs signing in again',
    );
  });

  it('a waiting turn becomes a queued run with its reason, its reset and its conversation', () => {
    const run = waitingRun(INBOX_ITEMS.WAITING());
    expect(run).toEqual(
      expect.objectContaining({
        status: 'QUEUED',
        conversationId: IDS.conversation,
        agentId: IDS.agent,
      }),
    );
    expect(run?.queueReason?.code).toBe('BUDGET_SPENT');
    expect(run?.queueReason?.retryAt).toBe('2026-11-01T00:00:00Z');
    expect(run?.queueReason?.text).toBe('Your budget is spent.');
  });

  it('takes the server’s words for why a turn waits, when it gave them', () => {
    const item = INBOX_ITEMS.WAITING();
    item.turn = {
      ...item.turn!,
      waitingText: 'Waiting for a free slot on office (1 of 1 in use).',
    };
    expect(inboxRowView(item, MEMBER).title).toBe(
      'Waiting for a free slot on office (1 of 1 in use).',
    );
  });

  it('says how long an item has waited, short', () => {
    const since = '2026-10-14T12:00:00Z';
    const at = (minutes: number) => Date.parse(since) + minutes * 60_000;
    expect(waitedText(since, at(0.5))).toBe('waiting under a minute');
    expect(waitedText(since, at(12))).toBe('waiting 12 min');
    expect(waitedText(since, at(185))).toBe('waiting 3 h');
    expect(waitedText(since, at(60 * 24 * 2 + 5))).toBe('waiting 2 days');
  });

  it('collapses what the reader settled to what was done and what happens next', () => {
    expect(
      decidedView(
        aDecision({
          status: 'DECIDED',
          outcome: 'ANSWERED',
          option: 'Yes',
          answer: 'And the app.',
        }),
      ),
    ).toEqual({
      label: 'Answered',
      detail: 'Yes, “And the app.”',
      after: 'Business analyst goes on once no question is open',
    });
    expect(decidedView(anApproval({ status: 'DECIDED', outcome: 'APPROVED' }))).toEqual({
      label: 'Approved',
      detail: 'Comment on SHOP-241',
      after: 'Business analyst goes on with it',
    });
    expect(
      decidedView(anApproval({ status: 'DECIDED', outcome: 'DENIED', note: 'Not now.' })).after,
    ).toBe('Business analyst is told and goes on without it');
    expect(retriedView(INBOX_ITEMS.FAILED(), true)).toEqual({
      label: 'Queued again',
      detail: 'Checkout fails for guest users',
      after: 'Business analyst goes on from where it stopped',
    });
  });

  it('counts what the inbox holds beyond its list, by group', () => {
    const items = [INBOX_ITEMS.DECISION(), INBOX_ITEMS.FAILED()];
    const counts = { decisions: 3, bindings: 1, failed: 1, waiting: 0, accounts: 0 };
    expect(unlisted('needs', items, counts)).toBe(3);
    expect(unlisted('turns', items, counts)).toBe(0);
    expect(unlisted('notices', items, counts)).toBe(0);
  });

  it('keys a notice by the server’s noticeKey, and any other item by its kind and object', () => {
    expect(inboxItemKey(INBOX_ITEMS.COMPANY_SPEND())).toBe('COMPANY_SPEND:2026-10');
    expect(inboxItemKey(INBOX_ITEMS.BINDING())).toBe('BINDING:b-jira');
    // Two needs of one trigger are two entries.
    const paused = { ...INBOX_ITEMS.TRIGGER(), noticeKey: 'TRIGGER:t-triage:PAUSED:USER:1' };
    expect(inboxItemKey(paused)).not.toBe(inboxItemKey(INBOX_ITEMS.TRIGGER()));
    // A suspended service account keeps conversations waiting on each of its systems: one entry each.
    const suspended = (systemId: string) => {
      const item = INBOX_ITEMS.ACCOUNT();
      item.account = {
        ...item.account!,
        systemId,
        reason: 'SUSPENDED',
        credential: null,
        holderPrincipalId: 'p-svc',
      };
      return item;
    };
    expect(inboxItemKey(suspended('s-jira'))).toBe('ACCOUNT:p-svc:s-jira');
    expect(inboxItemKey(suspended('s-git'))).not.toBe(inboxItemKey(suspended('s-jira')));
  });

  it('makes a Connect card of an inbox binding, its grants from the reader’s accounts', () => {
    const item = INBOX_ITEMS.BINDING();
    item.binding = {
      ...item.binding!,
      systemId: IDS.github,
      systemName: 'GitHub',
      reason: 'PICK_GRANT',
      choices: [IDS.analystsGrant, 'grant-gone'],
    };
    expect(inboxRowView(item, MEMBER)).toMatchObject({
      kind: 'Pick a grant',
      title: 'Business analyst asked to use GitHub: pick the grant it acts through',
    });
    const card = inboxBindingCard(
      item.binding,
      aMyAccount({ account: null, grants: [aGrantChoice()] }),
    );
    expect(card).toEqual(
      expect.objectContaining({ state: 'WAITING', reason: 'PICK_GRANT', systemKind: 'GITHUB' }),
    );
    expect(card.choices).toEqual([
      {
        grantId: IDS.analystsGrant,
        groupId: IDS.analysts,
        groupName: 'Analysts',
        credentialName: 'svc-reader',
        access: 'READ',
      },
      { grantId: 'grant-gone', access: 'READ' },
    ]);
  });
});

describe('unlinkLabel', () => {
  const linked = (chat: Partial<InboxChat>) =>
    aChatInboxItem('CHAT_LINKED', { chat: { ...aChatInboxItem().chat!, ...chat } });

  it.each`
    case                  | item                                                                  | label
    ${'name and id'}      | ${linked({})}                                                         | ${'Unlink Acme Slack user Jane Doe (U04ABC123)'}
    ${'the id only'}      | ${linked({ chatUserName: null })}                                     | ${'Unlink Acme Slack user U04ABC123'}
    ${'neither'}          | ${linked({ chatUserName: null, chatUserId: null })}                   | ${'Unlink the Acme Slack account'}
    ${'a removed system'} | ${linked({ systemName: null, chatUserName: null, chatUserId: null })} | ${'Unlink the chat account'}
  `('names $case', ({ item, label }) => {
    expect(unlinkLabel(item)).toBe(label);
  });
});

describe('the licence’s notice (ADR-0077)', () => {
  it('says each state with its day', () => {
    const l = { endsAt: '2026-10-10', graceEndsAt: '2026-11-09', edition: 'PRO' as const };
    expect(licenceNoticeWords({ ...l, state: 'GRACE' })).toEqual({
      title: 'The Pro licence has ended: its paid features stop on 9 November 2026',
      body: 'It ended on 10 October 2026; everything keeps working until 9 November 2026. A new key, which an instance admin enters, restores everything at once.',
    });
    expect(
      licenceNoticeWords({ ...l, state: 'EXPIRED', auditExportUntil: '2027-02-07' }).body,
    ).toContain('the audit’s export until 7 February 2027');
  });

  it('says that SCIM stops with an Enterprise licence (ADR-0087)', () => {
    const l = { endsAt: '2026-10-10', graceEndsAt: '2026-11-09', edition: 'ENTERPRISE' as const };
    expect(licenceNoticeWords({ ...l, state: 'EXPIRING' }).body).toContain(
      'SCIM provisioning stops then: remove a person who leaves under Members.',
    );
    expect(licenceNoticeWords({ ...l, state: 'GRACE' }).body).toContain('SCIM provisioning stops');
    expect(licenceNoticeWords({ ...l, edition: 'PRO', state: 'EXPIRING' }).body).not.toContain(
      'SCIM',
    );
  });
});
