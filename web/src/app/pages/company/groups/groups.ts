import { NgTemplateOutlet } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  signal,
  untracked,
} from '@angular/core';
import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideChevronRight, lucideKeyRound, lucidePlus, lucideUsersRound } from '@ng-icons/lucide';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmEmptyImports } from '@spartan-ng/helm/empty';
import { HlmSkeleton } from '@spartan-ng/helm/skeleton';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { Api } from '../../../api/generated/api';
import { getGroupUsage } from '../../../api/generated/functions';
import type { GroupSummary, GroupUsage, GroupUsageItem } from '../../../api/generated/models';
import { loadEach } from '../../../core/company-data';
import { CurrentCompany } from '../../../core/current-company';
import { ErrorState } from '../../../shared/error-state';
import { initials } from '../../../shared/forms';
import { ListSkeleton } from '../../../shared/list-skeleton';
import { MembersData } from '../members/members-data';
import { GroupDialog, type GroupTarget } from './group-dialog';
import { accountLines, type GroupUsageState } from './group-usage';
import { stackOf } from './groups-view';

/** How many agents a group's row names before "+2". */
const AGENTS_SHOWN = 2;
/** How many groups' usage the page reads at a time. */
const READS_AT_A_TIME = 6;

/**
 * The company's groups (ADR-0049 §2), the Groups tab of Members (plan §3): named sets of members
 * that agents are shared with, grants lend systems' credentials to, and service accounts name as their
 * users. One row per group: its name, its members' initials, and what it may use. Owners and admins
 * open a group in a dialog to rename it, describe it, fill it from the company's members, or delete
 * it; members are added and taken out one at a time, so another admin's change to the same group
 * stays. Other roles see the names only.
 */
@Component({
  selector: 'app-groups',
  imports: [
    NgIcon,
    NgTemplateOutlet,
    HlmButton,
    HlmEmptyImports,
    HlmSkeleton,
    HlmSpinner,
    ErrorState,
    GroupDialog,
    ListSkeleton,
  ],
  providers: [provideIcons({ lucideChevronRight, lucideKeyRound, lucidePlus, lucideUsersRound })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './groups.html',
})
export class Groups {
  private readonly api = inject(Api);
  private readonly current = inject(CurrentCompany);
  protected readonly data = inject(MembersData);

  protected readonly companyName = this.current.name;
  protected readonly canManage = this.data.canManageGroups;
  protected readonly groups = this.data.groups;

  /** One grid: on a narrow list what the group holds goes under its name; wide, one line. */
  protected readonly rowClass =
    'grid w-full grid-cols-[36px_minmax(0,1fr)] items-start gap-x-3 gap-y-2 px-4 py-3.5 text-start @3xl:grid-cols-[36px_minmax(0,1.2fr)_11rem_minmax(0,1.6fr)_16px] @3xl:items-center @3xl:gap-x-4';

  constructor() {
    // Owners and admins see each group's members on its row.
    effect(() => {
      if (this.canManage()) untracked(() => this.data.wantGroupDetails());
    });
    // And what each group may use: read once per group, as the groups come in.
    effect(() => {
      const companyId = this.current.id();
      const manage = this.canManage();
      const ids = this.groups.items().map((g) => g.id);
      untracked(() => {
        if (companyId !== this.askedIn) {
          this.askedIn = companyId;
          this.asked = new Set();
          this.usages.set(new Map());
        }
        if (!companyId || !manage) return;
        const fresh = ids.filter((id) => !this.asked.has(id));
        if (fresh.length) void this.readUsage(companyId, fresh);
      });
    });
  }

  // --- A row ---------------------------------------------------------------------------------

  protected count(n: number): string {
    return `${n} ${n === 1 ? 'member' : 'members'}`;
  }

  /** The members' initials to stack, once the group's members are read; null before. */
  protected stack(group: GroupSummary): { initials: string[]; more: number } | null {
    const read = this.data.groupOf(group.id);
    return read ? stackOf(read.members) : null;
  }

  protected agentInitials(name: string): string {
    return initials(name);
  }

  /** What the group may use, in the row's few words; null while its usage is read. */
  protected uses(group: GroupSummary): {
    agents: readonly GroupUsageItem[];
    moreAgents: number;
    accounts: string[];
    identities: string[];
  } | null {
    const usage = this.usages().get(group.id)?.usage;
    if (!usage) return null;
    return {
      agents: usage.agents.slice(0, AGENTS_SHOWN),
      moreAgents: Math.max(0, usage.agents.length - AGENTS_SHOWN),
      accounts: accountLines(usage),
      identities: usage.serviceIdentities.map((i) => i.name),
    };
  }

  // --- What each group may use -----------------------------------------------------------------

  /** Each group's usage (`getGroupUsage`), by group id: loading, read, or failed. */
  protected readonly usages = signal<ReadonlyMap<string, GroupUsageState>>(new Map());
  /** The groups whose usage was asked for, in the company it was asked in. */
  private asked = new Set<string>();
  private askedIn: string | undefined;

  /** The groups listed whose usage could not be read; a deleted group's no longer counts. */
  private readonly failedUsage = computed(() => {
    const usages = this.usages();
    return this.groups.items().filter((g) => usages.get(g.id)?.error);
  });
  /** The first failed read, for the page's error state; null while none failed. */
  protected readonly usageError = computed<unknown>(() => {
    const failed = this.failedUsage()[0];
    return failed ? this.usages().get(failed.id)?.error : null;
  });

  /** Reads the groups' usage, six at a time; a group read again keeps what it showed meanwhile. */
  private async readUsage(companyId: string, ids: readonly string[]): Promise<void> {
    ids.forEach((id) => this.asked.add(id));
    this.usages.update((map) => {
      const next = new Map(map);
      for (const id of ids) next.set(id, { usage: next.get(id)?.usage ?? null, error: null });
      return next;
    });
    await loadEach(ids, READS_AT_A_TIME, async (groupId) => {
      let state: GroupUsageState;
      try {
        const usage: GroupUsage = await this.api.invoke(getGroupUsage, { companyId, groupId });
        state = { usage, error: null };
      } catch (error) {
        state = { usage: null, error };
      }
      if (this.askedIn !== companyId) return;
      this.usages.update((map) => new Map(map).set(groupId, state));
    });
  }

  /** Reads again the groups whose usage could not be read. */
  protected retryUsage(): void {
    const companyId = this.current.id();
    const failed = this.failedUsage().map((g) => g.id);
    if (companyId && failed.length) void this.readUsage(companyId, failed);
  }

  // --- The group's dialog ----------------------------------------------------------------------

  protected readonly target = signal<GroupTarget | null>(null);

  /** Opens the dialog for a new group (the page's "New group"). */
  openCreate(): void {
    if (this.canManage()) this.target.set('new');
  }

  protected open(group: GroupSummary): void {
    this.target.set(group);
  }

  protected isOpen(group: GroupSummary): boolean {
    const target = this.target();
    return target !== null && target !== 'new' && target.id === group.id;
  }
}
