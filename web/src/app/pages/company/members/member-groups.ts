import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucidePlus, lucideX } from '@ng-icons/lucide';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmSkeleton } from '@spartan-ng/helm/skeleton';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { Api } from '../../../api/generated/api';
import { addGroupMember, removeGroupMember } from '../../../api/generated/functions';
import type { GroupSummary } from '../../../api/generated/models';
import { CurrentCompany } from '../../../core/current-company';
import { Notify } from '../../../core/notify';
import { ErrorState } from '../../../shared/error-state';
import { MembersData } from './members-data';

const FORBIDDEN = { 'status:403': 'Only owners and admins can change groups.' };

/**
 * The groups a member is in, as chips in their dialog: what else they may use comes from groups.
 * Adding or taking them out saves at once, this member alone (`addGroupMember`,
 * `removeGroupMember`): a change another admin makes to the same group meanwhile stays, and the
 * answer, the group with every member, shows it.
 */
@Component({
  selector: 'app-member-groups',
  imports: [RouterLink, NgIcon, HlmButton, HlmSkeleton, HlmSpinner, ErrorState],
  providers: [provideIcons({ lucidePlus, lucideX })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'flex flex-col gap-3' },
  template: `
    @if (data.groupDetails.error() && !data.groupDetails.hasValue()) {
      <app-error-state
        [error]="data.groupDetails.error()"
        [heading]="'Couldn’t read the groups of ' + name()"
        (retry)="data.groupDetails.reload()"
      />
    } @else if (!data.groupDetails.hasValue()) {
      <div class="flex gap-2" aria-busy="true">
        <span class="sr-only" role="status">Loading the groups of {{ name() }}…</span>
        <hlm-skeleton class="h-8 w-28 rounded-full" />
        <hlm-skeleton class="h-8 w-24 rounded-full" />
      </div>
    } @else {
      <div class="flex flex-wrap items-center gap-2">
        <ul
          class="m-0 flex list-none flex-wrap items-center gap-2 p-0 empty:hidden"
          [attr.aria-label]="'Groups of ' + name()"
          data-testid="member-group-list"
        >
          @for (group of inGroups(); track group.id) {
            <li
              class="bg-card inline-flex h-8 items-center gap-1.5 rounded-full border ps-3 pe-1 text-[0.8125rem] font-semibold max-sm:h-11"
              [attr.data-group]="group.name"
            >
              {{ group.name }}
              <button
                type="button"
                class="text-muted-foreground hover:bg-accent hover:text-foreground focus-visible:ring-ring/50 inline-flex size-6 items-center justify-center rounded-full outline-none focus-visible:ring-3 disabled:opacity-50 max-sm:size-10"
                [attr.aria-label]="'Take ' + name() + ' out of ' + group.name"
                [disabled]="busy() !== null"
                (click)="remove(group)"
              >
                @if (busy() === group.id) {
                  <hlm-spinner class="size-3" aria-hidden="true" />
                } @else {
                  <ng-icon name="lucideX" size="12" aria-hidden="true" />
                }
              </button>
            </li>
          }
        </ul>
        @if (inGroups().length === 0) {
          <span class="text-muted-foreground text-[0.8125rem]">In no group yet.</span>
        }
        <button
          hlmBtn
          variant="outline"
          size="xs"
          type="button"
          class="max-sm:h-11 max-sm:px-4 max-sm:text-sm"
          data-testid="add-to-group"
          [attr.aria-expanded]="picking()"
          aria-controls="member-group-options"
          (click)="picking.set(!picking())"
        >
          <ng-icon name="lucidePlus" aria-hidden="true" />
          Add to a group
        </button>
      </div>
      @if (picking()) {
        <div
          id="member-group-options"
          class="bg-muted flex flex-wrap items-center gap-2 rounded-xl border p-2.5"
          role="group"
          aria-label="Groups to add to"
          data-testid="member-group-options"
        >
          @for (group of otherGroups(); track group.id) {
            <button
              hlmBtn
              variant="outline"
              size="xs"
              type="button"
              class="max-sm:h-11 max-sm:px-4 max-sm:text-sm"
              [attr.data-group]="group.name"
              [disabled]="busy() !== null"
              (click)="add(group)"
            >
              @if (busy() === group.id) {
                <hlm-spinner class="size-3" aria-hidden="true" />
              }
              {{ group.name }}
              <span class="text-faint font-mono font-medium"
                >{{ group.memberCount
                }}<span class="sr-only">
                  {{ group.memberCount === 1 ? 'member' : 'members' }}</span
                ></span
              >
            </button>
          } @empty {
            <span class="text-muted-foreground text-[0.8125rem]">
              {{
                data.groups.items().length
                  ? name() + ' is in every group.'
                  : 'The company has no groups yet.'
              }}
              <a
                class="text-primary font-semibold underline-offset-2 hover:underline"
                [routerLink]="['/c', companyId(), 'members']"
                [queryParams]="{ tab: 'groups' }"
                >New group</a
              >
            </span>
          }
        </div>
      }
    }
  `,
})
export class MemberGroups {
  private readonly api = inject(Api);
  private readonly notify = inject(Notify);
  protected readonly companyId = inject(CurrentCompany).id;
  protected readonly data = inject(MembersData);

  readonly userId = input.required<string>();
  readonly name = input.required<string>();

  protected readonly picking = signal(false);
  /** The group being changed. */
  protected readonly busy = signal<string | null>(null);

  private readonly memberOf = computed(() => {
    const userId = this.userId();
    const all = this.data.groupDetails.hasValue() ? (this.data.groupDetails.value() ?? []) : [];
    return new Set(all.filter((g) => g.members.some((m) => m.userId === userId)).map((g) => g.id));
  });
  protected readonly inGroups = computed(() =>
    this.data.groups.items().filter((g) => this.memberOf().has(g.id)),
  );
  protected readonly otherGroups = computed(() =>
    this.data.groups.items().filter((g) => !this.memberOf().has(g.id)),
  );

  protected add(group: GroupSummary): Promise<void> {
    return this.change(group, true);
  }

  protected remove(group: GroupSummary): Promise<void> {
    return this.change(group, false);
  }

  /**
   * Adds the member to the group or takes them out. Both are idempotent: when someone else made the
   * same change meanwhile, the server changes nothing and answers the group all the same.
   */
  private async change(group: GroupSummary, into: boolean): Promise<void> {
    const companyId = this.companyId();
    const userId = this.userId();
    if (!companyId || this.busy()) return;
    this.busy.set(group.id);
    try {
      const params = { companyId, groupId: group.id, userId };
      const saved = into
        ? await this.api.invoke(addGroupMember, params)
        : await this.api.invoke(removeGroupMember, params);
      this.data.putGroup(saved);
      if (into) this.picking.set(false);
      this.notify.success(
        into ? `${this.name()} is in ${saved.name} now` : `${this.name()} is out of ${saved.name}`,
      );
    } catch (error) {
      this.notify.error(
        into
          ? `Couldn't add ${this.name()} to ${group.name}`
          : `Couldn't take ${this.name()} out of ${group.name}`,
        error,
        FORBIDDEN,
      );
    } finally {
      this.busy.set(null);
    }
  }
}
