import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { Router } from '@angular/router';
import { PageHeader } from '../../../shared/page-header';
import { Segmented, type SegmentedOption } from '../../../shared/segmented';
import { AccountsBudget } from './accounts-budget';
import { AccountsHosts } from './accounts-hosts';
import { AccountsSystems } from './accounts-systems';

export type AccountsTab = 'systems' | 'budget' | 'hosts';

const TABS: readonly (SegmentedOption & { value: AccountsTab })[] = [
  { value: 'systems', label: 'Systems' },
  { value: 'budget', label: 'Budget' },
  { value: 'hosts', label: 'Runner hosts' },
];

/**
 * Accounts, `/c/:companyId/accounts?tab=systems|budget|hosts` (plan §3, §5, §10 question 4): who
 * the reader acts as and who pays, on one page with three tabs. Systems: their account on each
 * system, or the grant they act through. Budget: their model access and subscriptions. Runner
 * hosts: their personal hosts. Every member has it; an instance admin who is not one does not
 * (`companyMemberGuard`).
 */
@Component({
  selector: 'app-accounts',
  imports: [PageHeader, Segmented, AccountsBudget, AccountsHosts, AccountsSystems],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'flex flex-col gap-6' },
  template: `
    <app-page-header
      title="Accounts"
      description="Who you act as and who pays: your accounts on the company’s systems, your budget and your runner hosts."
    />

    <app-segmented
      class="sm:self-start"
      inputId="accounts-tab"
      label="Accounts, budget or runner hosts"
      [options]="tabs"
      [value]="activeTab()"
      (valueChange)="openTab($event)"
    />

    @switch (activeTab()) {
      @case ('budget') {
        <app-accounts-budget />
      }
      @case ('hosts') {
        <app-accounts-hosts />
      }
      @default {
        <app-accounts-systems />
      }
    }
  `,
})
export class Accounts {
  private readonly router = inject(Router);

  /** From the `tab` query parameter; Systems without one. */
  readonly tab = input<string | undefined>();

  protected readonly tabs = TABS;
  protected readonly activeTab = computed<AccountsTab>(
    () => TABS.find((t) => t.value === this.tab())?.value ?? 'systems',
  );

  protected openTab(value: string): void {
    void this.router.navigate([], {
      queryParams: { tab: value === 'systems' ? null : value },
      queryParamsHandling: 'merge',
      replaceUrl: true,
    });
  }
}
