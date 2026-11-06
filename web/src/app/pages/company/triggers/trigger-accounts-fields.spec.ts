import { type ComponentFixture, TestBed } from '@angular/core/testing';
import type { SystemKind } from '../../../api/generated/models';
import { anAuthorChoice, aTriggerChoice, IDS } from '../../../testing/fixtures';
import { settle } from '../../../testing/test-helpers';
import { TriggerAccountsFields } from './trigger-accounts-fields';
import { emptyDraft, type TriggerDraft } from './trigger-draft';

describe('TriggerAccountsFields', () => {
  let fixture: ComponentFixture<TriggerAccountsFields>;

  const el = () => fixture.nativeElement as HTMLElement;
  const text = (node: Element | null | undefined) => node?.textContent?.replace(/\s+/g, ' ').trim();

  /** A channel trigger on Slack: Alice signed in to Slack, so her choice holds her Slack account. */
  const channelDraft = (): TriggerDraft => ({
    ...emptyDraft(),
    source: 'CHANNEL',
    systemId: IDS.slack,
  });
  const alice = anAuthorChoice({
    systems: [
      {
        systemId: IDS.slack,
        systemName: 'Acme Slack',
        accountId: 'acc-alice-slack',
        externalLogin: 'U04ABC123',
        status: 'OK',
      },
    ],
  });

  async function create(sourceKind: SystemKind) {
    fixture = TestBed.createComponent(TriggerAccountsFields);
    fixture.componentRef.setInput('draft', channelDraft());
    fixture.componentRef.setInput('choices', [alice]);
    fixture.componentRef.setInput('author', IDS.alice);
    fixture.componentRef.setInput('sourceKind', sourceKind);
    fixture.detectChanges();
    await settle();
  }

  it('says what a Slack channel trigger’s own row is for, and leaves it optional', async () => {
    await create('SLACK');
    const row = el().querySelector(`[data-row="${IDS.slack}"]`);
    expect(text(row?.querySelector(`[data-row-history="${IDS.slack}"]`))).toContain(
      'the agent reads the thread it is asked in, and nothing else in Slack',
    );
    const options = [
      ...el().querySelector<HTMLSelectElement>(`#trigger-row-${IDS.slack}`)!.options,
    ];
    // Each option names whom the row acts through, and with what.
    expect(options.map((o) => o.value)).toEqual(['', `${IDS.alice}:acc-alice-slack`]);
  });

  it('says nothing of history on Teams, which has none', async () => {
    await create('TEAMS');
    expect(el().querySelector('[data-row-history]')).toBeNull();
  });

  it('asks no account of an MCP server that needs none, and fills no row there (ADR-0071)', async () => {
    const logs = { systemId: IDS.crm, systemName: 'Logs', status: 'NOT_NEEDED' as const };
    const choice = anAuthorChoice({ systems: [logs] });
    fixture = TestBed.createComponent(TriggerAccountsFields);
    fixture.componentRef.setInput('draft', { ...emptyDraft(), source: 'SCHEDULE' });
    fixture.componentRef.setInput('choices', [choice]);
    fixture.componentRef.setInput('author', IDS.alice);
    fixture.detectChanges();
    await settle();

    expect(text(el().querySelector(`[data-choice-system="${IDS.crm}"]`))).toBe(
      'Logs: no account needed',
    );
    expect(text(el().querySelector(`[data-row-no-account="${IDS.crm}"]`))).toBe(
      'No account needed: its runs call it without one.',
    );
    expect(el().querySelector(`#trigger-row-${IDS.crm}`)).toBeNull();

    el().querySelector<HTMLInputElement>(`[data-choice="${choice.principalId}"] input`)!.click();
    fixture.detectChanges();
    await settle();
    expect(fixture.componentInstance.draft().accounts).toEqual([]);
  });

  it('names the credential a service account borrows, and its row acts through it (ADR-0072)', async () => {
    const svcReader = aTriggerChoice();
    const svcOps = aTriggerChoice({
      principalId: 'p-svc-ops',
      name: 'svc-ops',
      systems: [{ ...svcReader.systems[0], credentialName: 'reader-bot' }],
    });
    fixture = TestBed.createComponent(TriggerAccountsFields);
    fixture.componentRef.setInput('draft', { ...emptyDraft(), source: 'SCHEDULE' });
    fixture.componentRef.setInput('choices', [svcReader, svcOps, anAuthorChoice()]);
    fixture.componentRef.setInput('author', IDS.alice);
    fixture.detectChanges();
    await settle();

    const choice = el().querySelector(`[data-choice="${IDS.svcReader}"]`)!;
    expect(text(choice)).toContain('service account');
    expect(text(choice.querySelector(`[data-choice-system="${IDS.jira}"]`))).toBe(
      'Jira: reader-bot (svc-reader)',
    );
    choice.querySelector<HTMLInputElement>('input')!.click();
    fixture.detectChanges();
    await settle();
    expect(fixture.componentInstance.draft().accounts).toContainEqual({
      systemId: IDS.jira,
      accountId: 'acc-svc-reader-jira',
      holderPrincipalId: IDS.svcReader,
    });

    // The same credential lent to svc-ops is another option: picking it moves the row to svc-ops.
    const select = el().querySelector<HTMLSelectElement>(`#trigger-row-${IDS.jira}`)!;
    expect([...select.options].map((o) => text(o))).toEqual([
      'Not available to this trigger’s runs',
      'svc-reader with reader-bot (svc-reader)',
      'svc-ops with reader-bot (svc-reader)',
      'Yours (alice)',
    ]);
    select.value = `p-svc-ops:acc-svc-reader-jira`;
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    await settle();
    expect(fixture.componentInstance.draft().choice).toBeNull();
    expect(fixture.componentInstance.draft().accounts).toContainEqual({
      systemId: IDS.jira,
      accountId: 'acc-svc-reader-jira',
      holderPrincipalId: 'p-svc-ops',
    });
  });

  it('says read-only where a service account’s credential is lent read-only', async () => {
    const reader = aTriggerChoice();
    const svcReader = aTriggerChoice({
      systems: [{ ...reader.systems[0], access: 'READ' }, reader.systems[1]],
    });
    fixture = TestBed.createComponent(TriggerAccountsFields);
    fixture.componentRef.setInput('draft', { ...emptyDraft(), source: 'SCHEDULE' });
    fixture.componentRef.setInput('choices', [svcReader, anAuthorChoice()]);
    fixture.componentRef.setInput('author', IDS.alice);
    fixture.detectChanges();
    await settle();

    const choice = el().querySelector(`[data-choice="${IDS.svcReader}"]`)!;
    expect(text(choice.querySelector(`[data-choice-system="${IDS.jira}"]`))).toBe(
      'Jira: reader-bot (svc-reader) · read-only',
    );
    expect(text(choice.querySelector(`[data-choice-system="${IDS.github}"]`))).toBe(
      'GitHub: reader-bot (svc-reader)',
    );
    choice.querySelector<HTMLInputElement>('input')!.click();
    fixture.detectChanges();
    await settle();

    // The row's option and its note say it; the other row writes.
    const select = el().querySelector<HTMLSelectElement>(`#trigger-row-${IDS.jira}`)!;
    expect(text(select.selectedOptions[0])).toBe(
      'svc-reader with reader-bot (svc-reader), read-only',
    );
    expect(text(el().querySelector(`[data-row-read-only="${IDS.jira}"]`))).toBe(
      'Lent read-only: its runs call no write tool on Jira.',
    );
    expect(el().querySelector(`[data-row-read-only="${IDS.github}"]`)).toBeNull();

    // The preview's answer, read after each change, decides once it is there.
    fixture.componentRef.setInput('previewAccounts', [
      { systemId: IDS.jira, status: 'OK', access: 'WRITE' },
    ]);
    fixture.detectChanges();
    await settle();
    expect(el().querySelector(`[data-row-read-only="${IDS.jira}"]`)).toBeNull();
  });
});
