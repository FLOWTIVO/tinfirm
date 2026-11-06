import type { InboxTrigger, SystemKind } from '../../../api/generated/models';
import { anInboxTrigger } from '../../../testing/fixtures';
import {
  actionsFor,
  actionText,
  eventsFor,
  inboxTriggerNeedsYou,
  inboxTriggerText,
  isTracker,
  subjectsFor,
} from './trigger-labels';

describe('the words of triggers', () => {
  it.each<[SystemKind, boolean, string[], string[]]>([
    ['JIRA_DC', true, ['ISSUE'], ['TRANSITION', 'ATTACH_DOCUMENTS']],
    ['JIRA_CLOUD', true, ['ISSUE'], ['TRANSITION', 'ATTACH_DOCUMENTS']],
    ['GITHUB', true, ['ANY', 'ISSUE', 'CHANGE_REQUEST'], ['LABEL_ADD', 'LABEL_REMOVE', 'CLOSE']],
    ['GITLAB', true, ['ANY', 'ISSUE', 'CHANGE_REQUEST'], ['LABEL_ADD', 'LABEL_REMOVE', 'CLOSE']],
    ['MCP', false, ['ANY', 'ISSUE', 'CHANGE_REQUEST'], []],
  ])('%s: a tracker %s, its subjects and its write-back', (kind, tracker, subjects, actions) => {
    expect(isTracker(kind)).toBe(tracker);
    expect(subjectsFor(kind)).toEqual(subjects);
    expect(actionsFor(kind)).toEqual(actions);
  });

  it('offers Jira statuses and no change requests, and code hosts the reverse', () => {
    expect(eventsFor('JIRA_DC')).toContain('STATUS_ENTERED');
    expect(eventsFor('JIRA_DC')).not.toContain('CHANGE_REQUEST_OPENED');
    expect(eventsFor('GITHUB')).toContain('CHANGE_REQUEST_UPDATED');
    expect(eventsFor('GITHUB')).not.toContain('STATUS_ENTERED');
  });

  it('says an action with its value', () => {
    expect(actionText({ kind: 'TRANSITION', value: 'In review' })).toBe('Move to status In review');
    expect(actionText({ kind: 'CLOSE' })).toBe('Close it');
  });

  it.each<[Partial<InboxTrigger>, string, boolean]>([
    [{ reason: 'PAUSED', detail: 'AUTHOR_LEFT' }, 'Its author left the company', false],
    [
      { reason: 'PAUSED', detail: 'ACCOUNT_BROKEN' },
      'An account it acts with needs signing in again',
      true,
    ],
    [{ reason: 'PAUSED', detail: 'SYSTEM_PAUSED' }, 'An admin paused its system', false],
    [
      { reason: 'WAITING', detail: 'COMPANY_STOPPED' },
      'Waits: an admin stopped the company’s runs',
      false,
    ],
    [
      { reason: 'WAITING', detail: 'RECONNECT_YOURS' },
      'Waits for its author to sign in again',
      true,
    ],
    [{ reason: 'WIDENING', detail: null }, 'Its agent may do more in its new version', true],
    [{ reason: 'WRITE_BACK_DROPPED', count: 2 }, '2 results could not be written back', false],
    [
      { reason: 'FIRINGS_SKIPPED', count: 5, detail: 'SKIPPED_RATE' },
      '5 firings were skipped in the last hour (SKIPPED_RATE)',
      false,
    ],
    [{ reason: 'CHANGED_BY_ADMIN', detail: 'the payer' }, 'An admin changed it: the payer', false],
    [
      { reason: 'TO_REASSIGN', detail: null },
      'Its author left the company: it is paused until an admin reassigns it.',
      true,
    ],
    [
      { reason: 'POLL_WAITING', detail: 'svc-dev must sign in again' },
      'Its system is not read: svc-dev must sign in again',
      false,
    ],
  ])('an inbox item %o', (overrides, says, orange) => {
    const item = anInboxTrigger({ detail: null, count: null, ...overrides });
    expect(inboxTriggerText(item)).toContain(says);
    expect(inboxTriggerNeedsYou(item)).toBe(orange);
  });
});
