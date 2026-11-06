import {
  activeFilterCount,
  actorChoice,
  type AuditFilters,
  auditQuery,
  conversationFilter,
  conversationText,
  filterChips,
  filterErrors,
  filtersFromQuery,
  filtersToQuery,
  fromActorChoice,
  moreFilterCount,
  sameFilters,
} from './audit-filters';

const PERSON = '0192f0c4-7d1e-7c3a-9b4e-2f1a3c5d7e9f';
const CONVERSATION = '0192f0c4-0000-7c3a-9b4e-2f1a3c5d7e9f';
const HOST = '3f2a1c9e-1111-4c3a-9b4e-2f1a3c5d7e9f';

describe('the audit log’s filters', () => {
  it.each<[string, Record<string, string | undefined>, AuditFilters]>([
    ['nothing', {}, {}],
    ['an action prefix', { action: 'tool.' }, { action: 'tool.' }],
    ['an action, trimmed', { action: ' member.added ' }, { action: 'member.added' }],
    ['an action with capitals', { action: 'Member.added' }, {}],
    ['a kind of actor', { actorType: 'SYSTEM' }, { actorType: 'SYSTEM' }],
    ['an unknown kind of actor', { actorType: 'ROBOT' }, {}],
    ['a person', { actorType: 'USER', actorId: PERSON }, { actorType: 'USER', actorId: PERSON }],
    ['a person without their kind', { actorId: PERSON }, {}],
    ['a person who is no id', { actorType: 'USER', actorId: 'alice' }, { actorType: 'USER' }],
    [
      'a day range',
      { from: '2026-10-01', to: '2026-10-12' },
      { from: '2026-10-01', to: '2026-10-12' },
    ],
    ['a day that does not exist', { from: '2026-02-30' }, {}],
    ['a range backwards', { from: '2026-10-12', to: '2026-10-01' }, { from: '2026-10-12' }],
    ['the last hour', { when: 'hour' }, { when: 'hour' }],
    ['a time that is none', { when: 'month' }, {}],
    ['a time and days, the time first', { when: 'today', from: '2026-10-01' }, { when: 'today' }],
    ['a conversation', { conversationId: CONVERSATION }, { conversationId: CONVERSATION }],
    ['a conversation that is no id', { conversationId: 'SHOP-1' }, {}],
    [
      'a conversation’s key, in lower case',
      { conversationKey: ' SHOP-123-Dev-1 ' },
      { conversationKey: 'shop-123-dev-1' },
    ],
    ['a conversation’s key that is none', { conversationKey: 'shop 1' }, {}],
    ['whom the events are about', { principalId: PERSON }, { principalId: PERSON }],
    [
      'a target',
      { targetType: 'runner_host', targetId: 'h1' },
      { targetType: 'runner_host', targetId: 'h1' },
    ],
  ])('reads %s from the URL', (_, query, filters) => {
    expect(filtersFromQuery(query)).toEqual(filters);
  });

  it.each<[string, AuditFilters, string[]]>([
    ['valid filters', { action: 'tool.', from: '2026-10-01', principalId: PERSON }, []],
    ['an action with spaces', { action: 'tool called' }, ['action']],
    ['a target type with a dot', { targetType: 'runner.host' }, ['targetType']],
    ['a principal that is no id', { principalId: 'svc-dev' }, ['principalId']],
    ['the last day before the first', { from: '2026-10-12', to: '2026-10-01' }, ['to']],
    ['a key with a space', { conversationKey: 'shop 1' }, ['conversationKey']],
    ['a key that ends in a hyphen', { conversationKey: 'shop-' }, ['conversationKey']],
    ['a key', { conversationKey: 'dev-12' }, []],
  ])('checks %s', (_, filters, fields) => {
    expect(Object.keys(filterErrors(filters))).toEqual(fields);
  });

  it.each<[string, Pick<AuditFilters, 'conversationId' | 'conversationKey'>]>([
    ['', { conversationId: undefined, conversationKey: undefined }],
    [' SHOP-123-dev-1 ', { conversationId: undefined, conversationKey: 'shop-123-dev-1' }],
    [CONVERSATION, { conversationId: CONVERSATION, conversationKey: undefined }],
  ])('takes %j typed as the conversation', (text, filter) => {
    expect(conversationFilter(text)).toEqual(filter);
  });

  it('shows the conversation’s key in its field, else its id', () => {
    expect(conversationText({ conversationKey: 'dev-1', conversationId: CONVERSATION })).toBe(
      'dev-1',
    );
    expect(conversationText({ conversationId: CONVERSATION })).toBe(CONVERSATION);
    expect(conversationText({})).toBe('');
  });

  it('puts every key in the URL, null for those not set, so a merge drops them', () => {
    expect(filtersToQuery({ action: 'tool.', actorType: 'AGENT', when: 'week' })).toEqual({
      action: 'tool.',
      actorType: 'AGENT',
      actorId: null,
      targetType: null,
      targetId: null,
      when: 'week',
      from: null,
      to: null,
      conversationId: null,
      conversationKey: null,
      principalId: null,
    });
  });

  it('compares filters by what they hold', () => {
    expect(sameFilters({ action: 'tool.', from: undefined }, { action: 'tool.' })).toBe(true);
    expect(sameFilters({ action: 'tool.' }, { action: 'run.' })).toBe(false);
  });

  it.each<[AuditFilters, number, number]>([
    [{}, 0, 0],
    [{ action: 'tool.' }, 1, 0],
    [{ actorType: 'USER', actorId: PERSON }, 1, 0],
    [{ actorType: 'USER', actorId: PERSON, from: '2026-10-01', to: '2026-10-02' }, 2, 0],
    [{ targetType: 'runner_host', targetId: 'h1', conversationId: CONVERSATION }, 2, 2],
    [{ principalId: PERSON, when: 'today' }, 2, 1],
    [{ conversationKey: 'dev-1', conversationId: CONVERSATION }, 1, 1],
  ])('counts %j as %i filters, %i of them under “More filters”', (filters, count, more) => {
    expect(activeFilterCount(filters)).toBe(count);
    expect(moreFilterCount(filters)).toBe(more);
  });

  it('asks the API for whole days in the reader’s time zone, the last one included', () => {
    expect(
      auditQuery({
        action: 'tool.',
        actorType: 'USER',
        actorId: PERSON,
        from: '2026-10-01',
        to: '2026-10-12',
        conversationId: CONVERSATION,
      }),
    ).toEqual({
      action: 'tool.',
      actorType: 'USER',
      actorId: PERSON,
      from: new Date(2026, 9, 1).toISOString(),
      to: new Date(2026, 9, 13).toISOString(),
      conversationId: CONVERSATION,
    });
    expect(auditQuery({ actorType: 'SYSTEM', actorId: PERSON })).toEqual({ actorType: 'SYSTEM' });
    expect(auditQuery({ conversationKey: 'shop-123-dev-1', principalId: PERSON })).toEqual({
      conversationKey: 'shop-123-dev-1',
      principalId: PERSON,
    });
  });

  it.each<[AuditFilters['when'], Date]>([
    ['hour', new Date(2026, 9, 14, 13, 20, 0)],
    ['today', new Date(2026, 9, 14)],
    ['week', new Date(2026, 9, 8)],
  ])('asks the API from the start of “%s”, with no end', (when, from) => {
    const now = new Date(2026, 9, 14, 14, 20, 0);
    expect(auditQuery({ when }, now)).toEqual({ from: from.toISOString() });
  });

  it.each<[string, Pick<AuditFilters, 'actorType' | 'actorId'>]>([
    ['', { actorType: undefined, actorId: undefined }],
    ['AGENT', { actorType: 'AGENT', actorId: undefined }],
    [`USER:${PERSON}`, { actorType: 'USER', actorId: PERSON }],
  ])('picks the actor %j', (choice, filters) => {
    expect(fromActorChoice(choice)).toEqual(filters);
    expect(actorChoice(filters)).toBe(choice);
  });

  describe('as chips', () => {
    const names = new Map<string, string>([
      [PERSON, 'Alice Analyst'],
      [HOST, 'build-01'],
    ]);
    const now = new Date();
    const year = now.getFullYear();

    it.each<[string, AuditFilters, string[]]>([
      ['an action', { action: 'tool.' }, ['Action tool.']],
      ['a person', { actorType: 'USER', actorId: PERSON }, ['By Alice Analyst']],
      [
        'a person no longer known',
        { actorType: 'USER', actorId: CONVERSATION },
        ['By a person 0192f0c4'],
      ],
      ['a kind of actor', { actorType: 'SYSTEM' }, ['By Tinfirm itself']],
      ['seven days', { when: 'week' }, ['Last 7 days']],
      ['days', { from: `${year}-10-08`, to: `${year}-10-14` }, ['8 Oct to 14 Oct']],
      ['one day', { from: `${year}-10-08`, to: `${year}-10-08` }, ['8 Oct only']],
      ['a first day', { from: `${year}-10-08` }, ['From 8 Oct']],
      [
        'a known target',
        { targetType: 'runner_host', targetId: HOST },
        ['On runner host build-01'],
      ],
      ['an unknown target', { targetType: 'runner_host', targetId: 'h1' }, ['On runner host h1']],
      ['a kind of target', { targetType: 'principal_model_key' }, ['On any model key']],
      ['a conversation', { conversationId: CONVERSATION }, ['In conversation 0192f0c4']],
      [
        'a conversation by its key',
        { conversationKey: 'shop-123-dev-1' },
        ['In conversation shop-123-dev-1'],
      ],
      ['whom they are about', { principalId: PERSON }, ['About Alice Analyst']],
      ['whom they are about, not known', { principalId: CONVERSATION }, ['About 0192f0c4']],
    ])('names %s', (_, filters, labels) => {
      expect(
        filterChips(filters, names).map((c) => (c.value ? `${c.label} ${c.value}` : c.label)),
      ).toEqual(labels);
    });

    it('clears all a chip stands for', () => {
      const [time] = filterChips({ from: '2026-10-08', to: '2026-10-09' });
      expect(time.clears).toEqual({ when: undefined, from: undefined, to: undefined });
      const [person] = filterChips({ actorType: 'USER', actorId: PERSON });
      expect(person.clears).toEqual({ actorType: undefined, actorId: undefined });
      const [conversation] = filterChips({ conversationKey: 'dev-1' });
      expect(conversation.clears).toEqual({
        conversationId: undefined,
        conversationKey: undefined,
      });
    });
  });
});
