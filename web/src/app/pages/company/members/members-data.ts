import { computed, effect, inject, Injectable, resource, signal, untracked } from '@angular/core';
import { Api } from '../../../api/generated/api';
import {
  getGroup,
  listGroups,
  listMemberAccounts,
  listMembers,
  listOpenDepartures,
} from '../../../api/generated/functions';
import type {
  DepartureSummary,
  Group,
  GroupSummary,
  Member,
  MemberAccount,
} from '../../../api/generated/models';
import { loadEach } from '../../../core/company-data';
import { CurrentCompany } from '../../../core/current-company';
import { pagedResource } from '../../../core/paged-resource';
import { can } from '../../../core/permissions';
import { onlyOwnerId } from './members-view';

const PAGE_SIZE = 100;
/** How many members' accounts, or groups, the page reads at a time. */
const READS_AT_A_TIME = 6;

/** A member's accounts as the list holds them: loading, read, or failed. */
export interface MemberAccounts {
  readonly items: readonly MemberAccount[] | null;
  readonly error: unknown;
}

/**
 * What Members and its Groups tab read (plan §5), held once for both: the members, the groups, each
 * member's accounts on company systems (status only, I4) and the members who left whose checklist
 * is open. The API lists members without their accounts and groups without their members, so each
 * member's accounts are read (six at a time) once the list is in, and each group's members once a
 * member or the groups' rows need them. Provided by the Members page.
 */
@Injectable()
export class MembersData {
  private readonly api = inject(Api);
  private readonly current = inject(CurrentCompany);

  readonly canManage = computed(() => can.manageMembers(this.current.access()));
  readonly canManageGroups = computed(() => can.manageGroups(this.current.access()));

  readonly members = pagedResource<Member, string>({
    params: () => this.current.id(),
    load: (companyId, cursor) =>
      this.api.invoke(listMembers, { companyId, limit: PAGE_SIZE, cursor }),
  });

  /** The only owner, once every member is loaded: their role and membership stay until another owner exists. */
  readonly onlyOwner = computed(() => onlyOwnerId(this.members.items(), !this.members.hasMore()));

  readonly groups = pagedResource<GroupSummary, string>({
    params: () => this.current.id(),
    load: (companyId, cursor) =>
      this.api.invoke(listGroups, { companyId, limit: PAGE_SIZE, cursor }),
  });

  private readonly openDepartures = resource({
    params: () => (this.canManage() ? this.current.id() : undefined),
    loader: ({ params: companyId }) => this.api.invoke(listOpenDepartures, { companyId }),
  });
  /** The members who left and whose checklist is still open (the package 6 contract, §5.5). */
  readonly departures = computed<readonly DepartureSummary[]>(() =>
    this.openDepartures.hasValue() ? (this.openDepartures.value()?.items ?? []) : [],
  );

  // --- Each member's accounts -------------------------------------------------------------------

  private readonly accounts = signal<ReadonlyMap<string, MemberAccounts>>(new Map());
  /** The members whose accounts were asked for, in the company they were asked in. */
  private asked = new Set<string>();
  private askedIn: string | undefined;

  constructor() {
    effect(() => {
      const companyId = this.current.id();
      const manage = this.canManage();
      const ids = this.members.items().map((m) => m.userId);
      untracked(() => {
        if (companyId !== this.askedIn) {
          this.askedIn = companyId;
          this.asked = new Set();
          this.accounts.set(new Map());
        }
        if (!companyId || !manage) return;
        const fresh = ids.filter((id) => !this.asked.has(id));
        if (fresh.length) void this.readAccounts(companyId, fresh);
      });
    });
  }

  /** The accounts of a member, or null before they were asked for. */
  accountsOf(userId: string): MemberAccounts | null {
    return this.accounts().get(userId) ?? null;
  }

  /** Reads a member's accounts again, after a link is removed or when their dialog opens. */
  reloadAccounts(userId: string): void {
    const companyId = this.current.id();
    if (companyId && this.canManage()) void this.readAccounts(companyId, [userId], true);
  }

  private async readAccounts(companyId: string, ids: string[], keep = false): Promise<void> {
    ids.forEach((id) => this.asked.add(id));
    this.accounts.update((map) => {
      const next = new Map(map);
      for (const id of ids) {
        const before = next.get(id);
        next.set(id, { items: keep ? (before?.items ?? null) : null, error: null });
      }
      return next;
    });
    await loadEach(ids, READS_AT_A_TIME, async (userId) => {
      let state: MemberAccounts;
      try {
        const list = await this.api.invoke(listMemberAccounts, { companyId, userId });
        state = { items: list.items, error: null };
      } catch (error) {
        state = { items: null, error };
      }
      if (this.askedIn !== companyId) return;
      this.accounts.update((map) => new Map(map).set(userId, state));
    });
  }

  // --- Each group's members ---------------------------------------------------------------------

  private readonly groupsWanted = signal(false);
  private readonly groupIds = computed(() =>
    this.groups
      .items()
      .map((g) => g.id)
      .join(','),
  );

  /** Every loaded group with its members (`getGroup`), once a dialog or the groups' rows ask. */
  readonly groupDetails = resource({
    params: () => {
      const companyId = this.current.id();
      if (!companyId || !this.groupsWanted() || !this.canManageGroups()) return undefined;
      if (this.groups.firstLoad() || this.groups.failed()) return undefined;
      return { companyId, ids: this.groupIds() };
    },
    loader: async ({ params }): Promise<readonly Group[]> => {
      const ids = params.ids ? params.ids.split(',') : [];
      return loadEach(ids, READS_AT_A_TIME, (groupId) =>
        this.api.invoke(getGroup, { companyId: params.companyId, groupId }),
      );
    },
  });

  /** Reads every group's members: at once the first time, again when asked once more. */
  wantGroupDetails(): void {
    if (!this.groupsWanted()) this.groupsWanted.set(true);
    else if (!this.groupDetails.isLoading()) this.groupDetails.reload();
  }

  /** The group as last read, with its members; null before. */
  groupOf(groupId: string): Group | null {
    const all = this.groupDetails.hasValue() ? this.groupDetails.value() : null;
    return all?.find((g) => g.id === groupId) ?? null;
  }

  /** A group as the server answered a change: its row and its members follow at once. */
  putGroup(group: Group): void {
    const summary: GroupSummary = {
      id: group.id,
      name: group.name,
      description: group.description ?? null,
      memberCount: group.members.length,
    };
    const known = this.groups.items().some((g) => g.id === group.id);
    this.groups.update((items) =>
      known ? items.map((g) => (g.id === group.id ? summary : g)) : [...items, summary],
    );
    if (this.groupDetails.hasValue()) {
      const all = this.groupDetails.value() ?? [];
      this.groupDetails.set(
        all.some((g) => g.id === group.id)
          ? all.map((g) => (g.id === group.id ? group : g))
          : [...all, group],
      );
    }
  }

  /** A group deleted: gone from the rows and from every member's groups. */
  dropGroup(groupId: string): void {
    this.groups.update((items) => items.filter((g) => g.id !== groupId));
    if (this.groupDetails.hasValue()) {
      this.groupDetails.set((this.groupDetails.value() ?? []).filter((g) => g.id !== groupId));
    }
  }
}
