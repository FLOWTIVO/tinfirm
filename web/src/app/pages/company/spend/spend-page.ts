import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { CurrentCompany } from '../../../core/current-company';
import { managesCompany } from '../../../core/permissions';
import { PageHeader } from '../../../shared/page-header';
import { SpendView } from './spend-view';

/**
 * Spend for every member (the package 6 contract, §5.2): what their own turns and their triggers'
 * cost; an admin sees the company's here too, and from Model access.
 */
@Component({
  selector: 'app-spend-page',
  imports: [PageHeader, SpendView],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="flex flex-col gap-5">
      <app-page-header title="Spend" [description]="description()" />
      <app-spend-view />
    </div>
  `,
})
export class SpendPage {
  private readonly current = inject(CurrentCompany);
  protected readonly description = computed(() =>
    managesCompany(this.current.access())
      ? 'What the agents’ turns cost in this company, by person, agent, trigger and model.'
      : 'What your turns and your triggers’ cost, by agent, trigger and model.',
  );
}
