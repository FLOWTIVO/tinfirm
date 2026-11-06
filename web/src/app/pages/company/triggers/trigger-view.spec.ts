import type { TriggerAccount, TriggerPayer } from '../../../api/generated/models';
import {
  aTrigger,
  aTriggerAccount,
  aTriggerPayer,
  aTriggerPreview,
  IDS,
} from '../../../testing/fixtures';
import {
  accountLine,
  fromTrigger,
  fromTriggerPreview,
  hostsLine,
  payerLine,
  previewSummary,
} from './trigger-view';

describe('who acts and who pays for a trigger', () => {
  it.each<[TriggerAccount['status'], string, string, string | null]>([
    ['OK', 'Jira: svc-reader with reader-bot (svc-reader)', 'ok', null],
    ['RECONNECT_YOURS', 'Jira: svc-reader with reader-bot (svc-reader)', 'you', 'Sign in again'],
    [
      'RECONNECT_COMPANY',
      'Jira: svc-reader with reader-bot (svc-reader)',
      'blocked',
      'An admin must sign it in again.',
    ],
    [
      'SUSPENDED',
      'Jira: svc-reader with reader-bot (svc-reader)',
      'blocked',
      'Its holder is suspended.',
    ],
    [
      'NOT_ENTITLED',
      'Jira: svc-reader with reader-bot (svc-reader)',
      'you',
      'The author may not use it',
    ],
    ['ACCOUNT_REMOVED', 'Jira: svc-reader with reader-bot (svc-reader)', 'gone', 'Disconnected'],
  ])('a row %s', (status, text, tone, detail) => {
    const line = accountLine(aTriggerAccount({ status }), IDS.alice);
    expect(line.text).toBe(text);
    expect(line.tone).toBe(tone);
    if (detail) expect(line.detail).toContain(detail);
    else expect(line.detail).toBeNull();
  });

  it('says a member’s account by whose it is, without a credential', () => {
    const line = accountLine(
      aTriggerAccount({ holderKind: 'MEMBER', holderName: 'Bob', credentialName: null }),
      IDS.alice,
    );
    expect(line.text).toBe('Jira: Bob’s account (svc-reader)');
  });

  it('says a credential lent read-only as such', () => {
    const line = accountLine(aTriggerAccount({ access: 'READ' }), IDS.alice);
    expect(line.text).toBe('Jira: svc-reader with reader-bot (svc-reader), read-only');
    expect(line.tone).toBe('ok');
  });

  it('says “your own account” for the reader’s row, without its login', () => {
    const line = accountLine(
      aTriggerAccount({
        holderPrincipalId: IDS.alice,
        holderName: 'Alice',
        externalLogin: 'alice',
      }),
      IDS.alice,
    );
    expect(line.text).toBe('Jira: your own account');
  });

  it('says a row whose account went has none', () => {
    const line = accountLine(
      aTriggerAccount({ accountId: null, status: 'ACCOUNT_REMOVED' }),
      IDS.alice,
    );
    expect(line).toMatchObject({ text: 'Jira: no account', tone: 'gone' });
  });

  it.each<[Partial<TriggerPayer>, string, string, string | null]>([
    [{}, 'svc-reader’s budget', 'ok', null],
    [{ principalId: IDS.alice, name: 'Alice' }, 'Your budget', 'ok', null],
    [
      { state: 'WAITING', until: '2026-11-01T00:00:00Z' },
      'svc-reader’s budget',
      'blocked',
      'Spent: its turns wait until 1 Nov.',
    ],
    [
      { state: 'REFUSED', reason: 'GATEWAY_NOT_CONFIGURED' },
      'svc-reader’s budget',
      'blocked',
      'An admin must set up model access first.',
    ],
    [{ modelAccess: 'SUBSCRIPTION', principalId: IDS.alice }, 'Your subscription', 'ok', null],
  ])('who pays: %o', (overrides, text, tone, detail) => {
    const line = payerLine(aTriggerPayer(overrides), IDS.alice);
    expect(line.text).toBe(text);
    expect(line.tone).toBe(tone);
    expect(line.detail ?? null).toBe(detail);
  });

  it.each([
    [{ runnable: true, eligible: 1, reason: null }, 'A runner host can run it', 'ok'],
    [{ runnable: true, eligible: 3, reason: null }, '3 runner hosts can run it', 'ok'],
    [
      { runnable: false, eligible: 0, reason: 'Every host is offline' },
      'No runner host can run it now',
      'blocked',
    ],
  ])('where it runs: %o', (hosts, text, tone) => {
    expect(hostsLine(hosts)).toMatchObject({ text, tone });
  });

  it('draws the preview: rows, the systems left out, the payer, the hosts and the conversations kept', () => {
    const view = fromTriggerPreview(
      aTriggerPreview({ openConversationsKeepingAccounts: 3 }),
      IDS.alice,
    );
    // The preview names the credential a service account acts with (ADR-0072).
    expect(view.acts.map((l) => l.text)).toEqual([
      'Jira: svc-reader with reader-bot (svc-reader)',
      'GitHub: not available to this trigger’s runs',
    ]);
    expect(view.actsNote).toBe('3 open conversations keep the accounts it started with.');
    expect(view.pays.text).toBe('svc-reader’s budget');
    expect(view.runs?.text).toBe('A runner host can run it');
  });

  it('draws a preview row lent read-only as read-only', () => {
    const preview = aTriggerPreview();
    const view = fromTriggerPreview(
      { ...preview, accounts: [{ ...preview.accounts[0], access: 'READ' }] },
      IDS.alice,
    );
    expect(view.acts[0].text).toBe('Jira: svc-reader with reader-bot (svc-reader), read-only');
  });

  it('draws a stored trigger without where it runs', () => {
    const view = fromTrigger(aTrigger(), IDS.alice);
    expect(view.runs).toBeNull();
    expect(view.acts).toHaveLength(2);
  });

  it.each([
    [aTriggerPreview(), 'Acts as svc-reader · paid by svc-reader · can run'],
    [
      aTriggerPreview({
        accounts: [
          { systemId: IDS.jira, holderPrincipalId: IDS.alice, holderName: 'Alice', status: 'OK' },
        ],
        payer: aTriggerPayer({ principalId: IDS.alice, state: 'REFUSED' }),
        hosts: { runnable: false, eligible: 0 },
      }),
      'Acts as you · you cannot pay · cannot run now',
    ],
  ])('sums the preview up for the phone’s bar', (preview, summary) => {
    expect(previewSummary(preview, IDS.alice)).toBe(summary);
  });
});
