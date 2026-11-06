import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideChevronRight, lucideUserX } from '@ng-icons/lucide';
import { CurrentCompany } from '../../../core/current-company';
import { Time } from '../../../shared/time';
import { MembersData } from './members-data';

/**
 * A banner while a member who left has a checklist still open (the package 6 contract, §5.5), from
 * `GET /departures`: someone must take over what they left behind, so it is the orange of a person
 * who must act, and it leads to the checklist. Nothing shows while none is open.
 */
@Component({
  selector: 'app-open-departures',
  imports: [NgIcon, RouterLink, Time],
  providers: [provideIcons({ lucideChevronRight, lucideUserX })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (departures().length; as count) {
      <section
        class="border-human bg-card flex flex-wrap items-center gap-x-3.5 gap-y-2.5 rounded-[14px] border py-2.5 ps-3 pe-2.5"
        aria-labelledby="departures-title"
        data-testid="open-departures"
      >
        <span
          class="bg-human-soft text-human flex size-8 shrink-0 items-center justify-center rounded-[10px]"
          aria-hidden="true"
        >
          <ng-icon name="lucideUserX" size="16" />
        </span>
        <p id="departures-title" class="m-0 min-w-0 flex-[1_1_18rem] text-sm text-pretty">
          @if (count === 1) {
            @let d = departures()[0];
            <span class="font-semibold">{{ d.name }} left <app-time [value]="d.leftAt" />:</span>
          } @else {
            <span class="font-semibold">{{ count }} members left:</span>
          }
          <span class="text-muted-foreground"> what they left behind needs someone.</span>
        </p>
        <ul class="m-0 flex list-none flex-wrap gap-2 p-0">
          @for (d of departures(); track d.principalId) {
            <li>
              <a
                class="border-border bg-card hover:bg-accent focus-visible:ring-ring/50 inline-flex h-8 items-center gap-1 rounded-full border ps-3.5 pe-2.5 text-[0.8125rem] font-semibold outline-none focus-visible:ring-3 max-sm:h-11"
                [routerLink]="['/c', companyId(), 'members', 'departures', d.principalId]"
              >
                @if (count === 1) {
                  Open the checklist<span class="sr-only"> of {{ d.name }}</span>
                } @else {
                  {{ d.name }}<span class="sr-only">: open the checklist</span>
                }
                <ng-icon name="lucideChevronRight" size="14" aria-hidden="true" />
              </a>
            </li>
          }
        </ul>
      </section>
    }
  `,
})
export class OpenDepartures {
  private readonly data = inject(MembersData);
  protected readonly companyId = inject(CurrentCompany).id;
  protected readonly departures = computed(() => this.data.departures());
}
