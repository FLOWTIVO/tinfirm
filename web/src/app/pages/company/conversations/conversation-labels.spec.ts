import type { ConversationFilters, ConversationShow } from './conversation-labels';
import {
  conversationTitle,
  endedLabel,
  filterCount,
  filtersOf,
  SHOW_FILTERS,
  showOf,
} from './conversation-labels';

describe('conversation labels', () => {
  it.each<[string, Record<string, string | undefined>, ConversationFilters]>([
    ['nothing', {}, {}],
    [
      'an agent and a trigger',
      { agentId: 'a1', triggerId: 't1' },
      { agentId: 'a1', triggerId: 't1' },
    ],
    ['a known state', { state: 'OPEN' }, { state: 'OPEN' }],
    ['an unknown state', { state: 'open' }, {}],
    ['whom it waits on', { waitingOn: 'PERSON' }, { waitingOn: 'PERSON' }],
    ['an unknown waiting', { waitingOn: 'ME' }, {}],
    [
      'a subject',
      { subjectSystemId: 's1', subjectKey: 'SHOP-1' },
      { subjectSystemId: 's1', subjectKey: 'SHOP-1' },
    ],
    ['a key alone', { subjectKey: 'SHOP-1' }, {}],
    ['a system alone', { subjectSystemId: 's1' }, {}],
    ['blank values', { agentId: ' ', personId: '' }, {}],
  ])('reads %s from the query', (_, query, expected) => {
    const filters = filtersOf(query);
    const set = Object.fromEntries(Object.entries(filters).filter(([, v]) => v !== undefined));
    expect(set).toEqual(expected);
    expect(filterCount(filters)).toBe(
      Object.keys(expected).filter((k) => k !== 'subjectSystemId').length,
    );
  });

  it.each<[string, ConversationFilters, ConversationShow | undefined]>([
    ['every one', {}, 'all'],
    ['the open ones', { state: 'OPEN' }, 'open'],
    ['those that need a person', { waitingOn: 'PERSON' }, 'person'],
    ['those the agent works on', { waitingOn: 'AGENT' }, 'agent'],
    ['those waiting for a runner', { waitingOn: 'RUNNER' }, 'runner'],
    ['the ended ones', { state: 'ENDED' }, 'ended'],
    ['those waiting on nobody, which no pill shows', { waitingOn: 'NONE' }, undefined],
    ['a state and a waiting together', { state: 'OPEN', waitingOn: 'AGENT' }, undefined],
  ])('picks the pill for %s', (_, filters, show) => {
    expect(showOf(filters)).toBe(show);
    const pill = SHOW_FILTERS.find((s) => s.value === show);
    if (pill) expect(showOf({ state: pill.state, waitingOn: pill.waitingOn })).toBe(show);
  });

  it('says why a conversation ended, and names a purged one by its key', () => {
    expect(endedLabel('USER')).toBe('Ended');
    expect(endedLabel('AGENT_DELETED')).toBe('Ended: its agent was deleted');
    expect(endedLabel(null)).toBe('Ended');
    expect(conversationTitle({ title: '', key: 'SHOP-1-2' })).toBe('SHOP-1-2');
    expect(conversationTitle({ title: 'Brief', key: 'SHOP-1-2' })).toBe('Brief');
  });
});
