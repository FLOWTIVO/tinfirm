import {
  aScheduleTrigger,
  aTrigger,
  aTriggerChoice,
  anAgentSettings,
  anAuthorChoice,
  IDS,
} from '../../../testing/fixtures';
import {
  allRowsOwn,
  changed,
  changeOf,
  createRequest,
  defaultSettingsDraft,
  draftErrors,
  draftOf,
  emptyDraft,
  fillFromChoice,
  neededSystems,
  parseList,
  previewable,
  previewRequest,
  rowOptions,
  type TriggerDraft,
  updateRequest,
  withRow,
} from './trigger-draft';

/** A tracker draft that would pass the client's checks: Alice's, on Jira, svc-reader's accounts. */
function aDraft(overrides: Partial<TriggerDraft> = {}): TriggerDraft {
  return {
    ...emptyDraft('Europe/Prague'),
    name: 'Prepare assigned issues',
    systemId: IDS.jira,
    agentId: IDS.agent,
    agentVersion: 4,
    instruction: 'Prepare it.',
    accounts: [{ systemId: IDS.jira, accountId: 'acc-svc-reader-jira' }],
    payerPrincipalId: IDS.svcReader,
    settings: defaultSettingsDraft(),
    ...overrides,
  };
}

describe('the trigger editor’s draft', () => {
  describe('the one choice', () => {
    it('fills the table from a service account: every credential it borrows, its budget', () => {
      const draft = fillFromChoice(aDraft({ accounts: [] }), aTriggerChoice(), IDS.alice);
      expect(draft.choice).toBe(IDS.svcReader);
      // Each row names the service account it acts through (ADR-0072).
      expect(draft.accounts).toEqual([
        { systemId: IDS.jira, accountId: 'acc-svc-reader-jira', holderPrincipalId: IDS.svcReader },
        {
          systemId: IDS.github,
          accountId: IDS.readerGithubAccount,
          holderPrincipalId: IDS.svcReader,
        },
      ]);
      expect(draft.payerPrincipalId).toBe(IDS.svcReader);
    });

    it('leaves out a system the choice has no account on: not available to its runs', () => {
      const choice = aTriggerChoice({
        systems: [
          { systemId: IDS.jira, systemName: 'Jira', accountId: 'acc-j', status: 'OK' },
          { systemId: IDS.github, systemName: 'GitHub', accountId: null, status: 'NONE' },
        ],
      });
      expect(fillFromChoice(aDraft(), choice, IDS.alice).accounts).toEqual([
        { systemId: IDS.jira, accountId: 'acc-j', holderPrincipalId: IDS.svcReader },
      ]);
    });

    it.each([
      ['the author', anAuthorChoice(), 'SUBSCRIPTION'],
      ['a service account', aTriggerChoice(), 'BUDGET'],
    ] as const)('keeps a subscription only when %s pays', (_who, choice, modelAccess) => {
      const draft = aDraft({
        settings: {
          ...defaultSettingsDraft(),
          modelAccess: 'SUBSCRIPTION',
          subscription: { runnerHostId: IDS.personalHost, label: 'Claude' },
        },
      });
      expect(fillFromChoice(draft, choice, IDS.alice).settings?.modelAccess).toBe(modelAccess);
    });

    it('offers each choice’s account for a row, the author’s as “Yours”', () => {
      const options = rowOptions([aTriggerChoice(), anAuthorChoice()], IDS.jira, IDS.alice);
      expect(options.map((o) => o.label)).toEqual([
        'svc-reader with reader-bot (svc-reader)',
        'Yours (alice)',
      ]);
      expect(options.map((o) => o.key)).toEqual([
        `${IDS.svcReader}:acc-svc-reader-jira`,
        `${IDS.alice}:acc-alice-jira`,
      ]);
      // One credential lent to two service accounts is two options.
      const twice = rowOptions(
        [aTriggerChoice(), aTriggerChoice({ principalId: 'p-svc-ops', name: 'svc-ops' })],
        IDS.jira,
        IDS.alice,
      );
      expect(new Set(twice.map((o) => o.key)).size).toBe(2);
      const reconnect = rowOptions(
        [
          anAuthorChoice({
            systems: [
              {
                systemId: IDS.jira,
                accountId: 'acc-a',
                externalLogin: 'alice',
                status: 'RECONNECT',
              },
            ],
          }),
        ],
        IDS.jira,
        IDS.alice,
      );
      expect(reconnect[0]).toMatchObject({
        reconnect: true,
        label: 'Yours (alice), needs signing in again',
      });
      // A credential lent read-only says so (ADR-0072): its runs call no write tool there.
      const reader = aTriggerChoice();
      const readOnly = rowOptions(
        [aTriggerChoice({ systems: [{ ...reader.systems[0], access: 'READ' }] })],
        IDS.jira,
        IDS.alice,
      );
      expect(readOnly[0]).toMatchObject({
        label: 'svc-reader with reader-bot (svc-reader), read-only',
        access: 'READ',
      });
    });

    it('marks a row changed by hand, and removes one', () => {
      const filled = fillFromChoice(aDraft(), aTriggerChoice(), IDS.alice);
      const changedRow = withRow(filled, IDS.github, {
        accountId: 'acc-alice-github',
        principalId: IDS.alice,
      });
      expect(changedRow.choice).toBeNull();
      expect(changedRow.accounts).toContainEqual({
        systemId: IDS.github,
        accountId: 'acc-alice-github',
        holderPrincipalId: IDS.alice,
      });
      expect(withRow(filled, IDS.github, null).accounts.map((a) => a.systemId)).toEqual([IDS.jira]);
    });

    it.each([
      ['every row the author’s own', [{ systemId: IDS.jira, accountId: 'acc-alice-jira' }], true],
      [
        'a service account’s row',
        [{ systemId: IDS.jira, accountId: 'acc-svc-reader-jira' }],
        false,
      ],
      ['no row', [], true],
    ])('allows one’s own MCP servers with %s: %s', (_case, accounts, own) => {
      expect(
        allRowsOwn(aDraft({ accounts }), [aTriggerChoice(), anAuthorChoice()], IDS.alice),
      ).toBe(own);
    });

    it('needs the source system first, then the agent’s', () => {
      expect(neededSystems(IDS.jira, [IDS.github, IDS.jira])).toEqual([IDS.jira, IDS.github]);
      expect(neededSystems(null, [IDS.github])).toEqual([IDS.github]);
    });
  });

  describe('what the client says before the preview', () => {
    it.each<[string, Partial<TriggerDraft>, string, string]>([
      ['no name', { name: ' ' }, 'name', 'Name the trigger.'],
      ['no system', { systemId: null }, 'when', 'Pick the system the trigger watches.'],
      ['no event', { events: [] }, 'when', 'Pick at least one event.'],
      [
        'a status event without its status',
        { events: [{ event: 'STATUS_ENTERED', value: ' ' }] },
        'when',
        'Name the status or the label of each event that takes one.',
      ],
      ['no agent', { agentId: null }, 'agent', 'Pick the agent.'],
      ['no instruction', { instruction: '' }, 'agent', 'Say what the agent does each time.'],
      [
        'no row on the source (SOURCE_ACCOUNT_REQUIRED)',
        { accounts: [{ systemId: IDS.github, accountId: 'a' }] },
        'accounts',
        'The trigger needs an account on the system it watches: it reads it and writes back with it.',
      ],
      ['no payer', { payerPrincipalId: null }, 'payer', 'Pick who pays.'],
      [
        'firings that are no number',
        { maxFiringsPerHour: 'many' },
        'results',
        'Firings per hour: a whole number, at least 1.',
      ],
    ])('%s', (_case, overrides, at, message) => {
      expect(draftErrors(aDraft(overrides))).toEqual({ [at]: message });
    });

    it.each<[string, Partial<TriggerDraft['schedule']>, string | null]>([
      ['a valid monthly cron', { cron: '0 9 1 * *', zone: 'Europe/Prague' }, null],
      ['a cron every five minutes', { cron: '*/5 * * * *' }, 'minutes apart; a schedule fires'],
      ['an unknown zone', { cron: '0 9 * * 1', zone: 'Mars/Olympus' }, 'Pick a time zone'],
    ])('checks a schedule: %s', (_case, schedule, says) => {
      const draft = aDraft({
        source: 'SCHEDULE',
        systemId: null,
        accounts: [],
        schedule: { ...aDraft().schedule, ...schedule },
      });
      const when = draftErrors(draft).when ?? null;
      if (says === null) expect(when).toBeNull();
      else expect(when).toContain(says);
    });

    it('previews once name, rule, agent and payer are there; the source’s row may still be missing', () => {
      expect(previewable(aDraft())).toBe(true);
      expect(previewable(aDraft({ accounts: [] }))).toBe(true);
      expect(previewable(aDraft({ payerPrincipalId: null }))).toBe(false);
    });
  });

  describe('the requests', () => {
    it('sends a tracker trigger whole, with the values only events take and the lists parsed', () => {
      const body = createRequest(
        aDraft({
          events: [
            { event: 'ASSIGNED', value: 'ignored' },
            { event: 'STATUS_ENTERED', value: ' Ready ' },
          ],
          scope: ' SHOP ',
          conditions: [
            {
              statuses: '',
              notStatuses: '',
              labels: 'ba, BA, urgent',
              notLabels: '',
              types: '',
              notTypes: '',
              authors: '',
            },
            {
              statuses: '',
              notStatuses: '',
              labels: '',
              notLabels: '',
              types: '',
              notTypes: '',
              authors: '',
            },
          ],
          maxFiringsPerHour: '12',
          acknowledgeOpenEvents: true,
        }),
      );
      expect(body).toMatchObject({
        name: 'Prepare assigned issues',
        source: 'TRACKER',
        systemId: IDS.jira,
        events: [
          { event: 'ASSIGNED', value: null },
          { event: 'STATUS_ENTERED', value: 'Ready' },
        ],
        scope: 'SHOP',
        schedule: null,
        agentVersion: 4,
        payerPrincipalId: IDS.svcReader,
        maxFiringsPerHour: 12,
        acknowledgeOpenEvents: true,
      });
      // The empty group is left out; the duplicate label ignoring case too.
      expect(body.conditions).toEqual([
        {
          statuses: [],
          notStatuses: [],
          labels: ['ba', 'urgent'],
          notLabels: [],
          types: [],
          notTypes: [],
          authors: null,
        },
      ]);
      expect(body.settings).toMatchObject({ modelAccess: 'BUDGET', effort: 'DEFAULT' });
    });

    it('sends a schedule without a system, events or write-back actions', () => {
      const body = createRequest(
        aDraft({
          source: 'SCHEDULE',
          schedule: { cron: ' 0 9 1 * * ', zone: 'Europe/Prague', overlap: 'SKIP', catchUp: 'ONE' },
          results: { comment: true, review: true, onSuccess: [{ kind: 'CLOSE' }], onFailure: [] },
        }),
      );
      expect(body).toMatchObject({
        source: 'SCHEDULE',
        systemId: null,
        events: [],
        scope: null,
        schedule: { cron: '0 9 1 * *', zone: 'Europe/Prague', overlap: 'SKIP', catchUp: 'ONE' },
        results: { review: true, onSuccess: [], onFailure: [] },
      });
    });

    it('reads a stored trigger back into the same request', () => {
      const trigger = aTrigger();
      const draft = draftOf(trigger, anAgentSettings());
      expect(draft.accounts).toEqual([
        { systemId: IDS.jira, accountId: 'acc-svc-reader-jira', holderPrincipalId: IDS.svcReader },
      ]);
      expect(draft.maxFiringsPerHour).toBe('30');
      expect(updateRequest(draft)).toMatchObject({
        events: [{ event: 'ASSIGNED', value: null }],
        scope: 'SHOP',
        teamGroupId: IDS.analysts,
        clearTeam: false,
      });
      expect(draftOf(aScheduleTrigger(), null).schedule.cron).toBe('0 9 1 * *');
    });

    it('clears the team when the author alone decides', () => {
      expect(updateRequest(aDraft({ teamGroupId: null }))).toMatchObject({
        teamGroupId: null,
        clearTeam: true,
      });
    });

    it.each<
      [string, Partial<TriggerDraft>, { definition: boolean; accounts: boolean; settings: boolean }]
    >([
      ['nothing', {}, { definition: false, accounts: false, settings: false }],
      [
        'the instruction',
        { instruction: 'Other.' },
        { definition: true, accounts: false, settings: false },
      ],
      [
        'a row',
        { accounts: [{ systemId: IDS.jira, accountId: 'acc-alice-jira' }] },
        { definition: false, accounts: true, settings: false },
      ],
      [
        'the payer',
        { payerPrincipalId: IDS.alice },
        { definition: false, accounts: true, settings: false },
      ],
      [
        'a tool’s mode',
        {
          settings: {
            ...defaultSettingsDraft(),
            toolModes: [{ systemId: IDS.jira, tool: 'comment', mode: 'ASK' }],
          },
        },
        { definition: false, accounts: false, settings: true },
      ],
    ])('sends only what changed: %s', (_case, overrides, change) => {
      const saved = aDraft();
      const result = changeOf(saved, aDraft(overrides));
      expect(result).toEqual(change);
      expect(changed(result)).toBe(Object.values(change).some(Boolean));
    });
  });

  it('previews the draft as the save would send it, its acknowledgement too', () => {
    const request = previewRequest(aDraft({ acknowledgeOpenEvents: false }), 't-prepare');
    expect(request.triggerId).toBe('t-prepare');
    expect(request.draft.acknowledgeOpenEvents).toBe(false);
    expect(
      previewRequest(aDraft({ acknowledgeOpenEvents: true }), null).draft.acknowledgeOpenEvents,
    ).toBe(true);
  });

  it('parses a comma-separated list', () => {
    expect(parseList(' a, b ,, A ,c')).toEqual(['a', 'b', 'c']);
  });
});
