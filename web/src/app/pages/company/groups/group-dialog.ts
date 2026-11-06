import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  output,
  resource,
  signal,
  untracked,
} from '@angular/core';
import { form, FormField, maxLength, required, submit, validate } from '@angular/forms/signals';
import { RouterLink } from '@angular/router';
import { NgIcon, provideIcons } from '@ng-icons/core';
import {
  lucideCircleAlert,
  lucidePlus,
  lucideSearch,
  lucideTrash2,
  lucideUsersRound,
  lucideX,
} from '@ng-icons/lucide';
import { HlmAlertImports } from '@spartan-ng/helm/alert';
import { HlmAlertDialogImports } from '@spartan-ng/helm/alert-dialog';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmDialogImports } from '@spartan-ng/helm/dialog';
import { HlmFieldImports } from '@spartan-ng/helm/field';
import { HlmInput } from '@spartan-ng/helm/input';
import { HlmSkeleton } from '@spartan-ng/helm/skeleton';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { HlmTextarea } from '@spartan-ng/helm/textarea';
import { Api } from '../../../api/generated/api';
import {
  addGroupMember,
  createGroup,
  deleteGroup,
  getGroup,
  listMembers,
  removeGroupMember,
  replaceGroup,
} from '../../../api/generated/functions';
import type { Group, GroupInput, GroupSummary } from '../../../api/generated/models';
import { mapSubmissionError, problemMessage } from '../../../api/problem';
import { loadAll } from '../../../core/company-data';
import { CurrentCompany } from '../../../core/current-company';
import { Notify } from '../../../core/notify';
import { ErrorState } from '../../../shared/error-state';
import { firstError, initials } from '../../../shared/forms';
import { MembersData } from '../members/members-data';
import { grantPhrase, type GroupUsageState, usageSummary } from './group-usage';
import { matchesFilter, pickablePeople, type PickablePerson } from './groups-view';

const FORBIDDEN = { 'status:403': 'Only owners and admins can change groups.' };

interface GroupModel {
  name: string;
  description: string;
}

const EMPTY: GroupModel = { name: '', description: '' };

/** Which group the dialog holds: a new one, or one of the list. */
export type GroupTarget = GroupSummary | 'new';

/**
 * A group opened in a dialog, as a member opens on Members: its name, description and members, what
 * its members may use through it, and Delete. What changed waits in the unsaved-changes bar at its
 * foot; a click outside does not close it. Save adds and takes out each member changed, one at a
 * time (`addGroupMember`, `removeGroupMember`), so that what another admin changes in the group
 * meanwhile stays; only a new name or description replaces the group (`replaceGroup`), with its
 * members as the server last answered them. Save waits until the group's members are read, since
 * the changes are counted from them.
 */
@Component({
  selector: 'app-group-dialog',
  imports: [
    FormField,
    RouterLink,
    NgIcon,
    HlmAlertImports,
    HlmAlertDialogImports,
    HlmButton,
    HlmDialogImports,
    HlmFieldImports,
    HlmInput,
    HlmSkeleton,
    HlmSpinner,
    HlmTextarea,
    ErrorState,
  ],
  providers: [
    provideIcons({
      lucideCircleAlert,
      lucidePlus,
      lucideSearch,
      lucideTrash2,
      lucideUsersRound,
      lucideX,
    }),
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <hlm-dialog
      [state]="target() ? 'open' : 'closed'"
      [closeOnOutsidePointerEvents]="false"
      (closed)="close()"
    >
      <hlm-dialog-content
        *hlmDialogPortal="let ctx"
        [showCloseButton]="false"
        class="flex max-h-[min(88svh,56rem)] flex-col gap-0 overflow-hidden p-0 sm:max-w-[40rem] max-sm:h-svh max-sm:max-h-none max-sm:w-screen max-sm:max-w-none max-sm:rounded-none max-sm:ring-0"
        data-testid="group-dialog"
      >
        <header class="flex shrink-0 items-start gap-3 border-b py-4 ps-4 pe-3 sm:ps-5">
          <span
            class="bg-secondary text-foreground flex size-11 shrink-0 items-center justify-center rounded-[10px]"
            aria-hidden="true"
          >
            <ng-icon name="lucideUsersRound" size="18" />
          </span>
          <div class="min-w-0 flex-1">
            <h2 hlmDialogTitle class="text-[1.0625rem] leading-snug font-bold break-words">
              {{ editing() ? editing()!.name : 'New group' }}
            </h2>
            <p class="text-muted-foreground m-0 text-[0.8125rem] text-pretty">
              @if (editing(); as g) {
                {{ count(g.memberCount) }}
                @if (usage(); as u) {
                  · {{ summary() }}
                }
              } @else {
                A named set of members of {{ companyName() ?? 'the company' }}. Share agents with
                it, or lend it accounts with a grant, afterwards.
              }
            </p>
          </div>
          <button
            hlmBtn
            variant="ghost"
            size="icon"
            type="button"
            class="text-muted-foreground shrink-0 max-sm:size-11"
            aria-label="Close"
            hlmDialogClose
          >
            <ng-icon name="lucideX" aria-hidden="true" />
          </button>
        </header>

        <div class="flex min-h-0 flex-1 flex-col overflow-y-auto">
          <form
            id="group-form"
            class="flex flex-col gap-4 px-4 pt-[18px] pb-5 sm:px-5"
            novalidate
            (submit)="onSubmit($event)"
          >
            @if (formError(); as message) {
              <div hlmAlert variant="destructive" data-testid="group-form-error">
                <ng-icon name="lucideCircleAlert" aria-hidden="true" />
                <p hlmAlertDescription>{{ message }}</p>
              </div>
            }
            <div hlmField>
              <label hlmFieldLabel for="group-name">Name</label>
              <input
                hlmInput
                id="group-name"
                autocomplete="off"
                placeholder="Release managers"
                class="max-sm:h-11 max-sm:text-base"
                [formField]="groupForm.name"
              />
              <hlm-field-error>{{ firstError(groupForm.name) }}</hlm-field-error>
            </div>
            <div hlmField>
              <label hlmFieldLabel for="group-description"
                >Description
                <span class="text-muted-foreground font-normal">(optional)</span></label
              >
              <textarea
                hlmTextarea
                id="group-description"
                rows="2"
                class="min-h-16 [field-sizing:content] max-sm:text-base"
                placeholder="Who they are, and what they look after"
                [formField]="groupForm.description"
              ></textarea>
              <hlm-field-error>{{ firstError(groupForm.description) }}</hlm-field-error>
            </div>
          </form>

          <section
            class="flex flex-col gap-3 border-t px-4 pt-[18px] pb-5 sm:px-5"
            aria-labelledby="group-members-title"
            data-testid="member-picker"
          >
            <div class="flex items-baseline justify-between gap-3">
              <h3 id="group-members-title" class="text-sm font-bold">Members</h3>
              <span class="text-muted-foreground text-xs" data-testid="picked-count">{{
                count(picked().length)
              }}</span>
            </div>
            @if (loadError()) {
              <app-error-state
                [error]="loadError()"
                heading="Couldn't read the group's members"
                (retry)="readGroup()"
              />
            } @else if (loadingGroup()) {
              <div class="flex flex-col gap-2" aria-busy="true">
                <span class="sr-only" role="status">Loading the members…</span>
                <hlm-skeleton class="h-12 w-full rounded-xl" />
              </div>
            } @else {
              @if (pickedPeople().length) {
                <ul
                  class="m-0 flex list-none flex-col divide-y rounded-xl border p-0"
                  aria-label="Members of the group"
                  data-testid="group-member-list"
                >
                  @for (person of pickedPeople(); track person.userId) {
                    <li
                      class="flex min-h-12 items-center gap-2.5 py-1.5 ps-3 pe-1.5"
                      [attr.data-user]="person.userId"
                    >
                      <span
                        class="bg-secondary flex size-[30px] shrink-0 items-center justify-center rounded-full text-[11px] font-bold"
                        aria-hidden="true"
                        >{{ initialsOf(person.displayName) }}</span
                      >
                      <span class="flex min-w-0 flex-1 flex-col leading-snug">
                        <span class="truncate text-sm font-semibold">{{ person.displayName }}</span>
                        <span class="text-muted-foreground truncate text-xs">{{
                          person.email
                        }}</span>
                      </span>
                      <button
                        hlmBtn
                        variant="ghost"
                        size="icon-sm"
                        type="button"
                        class="text-muted-foreground max-sm:size-11"
                        [attr.aria-label]="'Take ' + person.displayName + ' out of the group'"
                        (click)="unpick(person.userId)"
                      >
                        <ng-icon name="lucideX" size="14" aria-hidden="true" />
                      </button>
                    </li>
                  }
                </ul>
              } @else {
                <p
                  class="text-muted-foreground m-0 rounded-xl border border-dashed px-3.5 py-3 text-[0.8125rem]"
                >
                  No members yet.
                </p>
              }
              <div>
                <button
                  hlmBtn
                  variant="outline"
                  size="xs"
                  type="button"
                  class="max-sm:h-11 max-sm:px-4 max-sm:text-sm"
                  data-testid="add-group-member"
                  aria-controls="group-member-options"
                  [attr.aria-expanded]="adding()"
                  (click)="adding.set(!adding())"
                >
                  <ng-icon name="lucidePlus" aria-hidden="true" />
                  Add a member
                </button>
              </div>
              @if (adding()) {
                <div
                  id="group-member-options"
                  class="bg-muted flex flex-col gap-2 rounded-xl border p-2.5"
                  role="group"
                  aria-label="Members to add"
                >
                  @if (members.error()) {
                    <p class="text-destructive m-0 text-xs" role="alert">
                      Couldn't load the company's members; only the group's own are listed.
                    </p>
                  }
                  <div class="relative">
                    <ng-icon
                      name="lucideSearch"
                      class="text-faint pointer-events-none absolute start-3 top-1/2 -translate-y-1/2"
                      aria-hidden="true"
                    />
                    <input
                      hlmInput
                      id="group-member-filter"
                      class="w-full ps-9 max-sm:h-11 max-sm:text-base"
                      type="search"
                      autocomplete="off"
                      placeholder="Find a member by name or email"
                      aria-label="Find a member"
                      [value]="filter()"
                      (input)="onFilter($event)"
                    />
                  </div>
                  @if (members.isLoading()) {
                    <hlm-skeleton class="h-7 w-1/2" />
                  } @else {
                    <ul class="m-0 flex max-h-56 list-none flex-wrap gap-2 overflow-y-auto p-0">
                      @for (person of options(); track person.userId) {
                        <li>
                          <button
                            hlmBtn
                            variant="outline"
                            size="xs"
                            type="button"
                            class="max-sm:h-11 max-sm:px-4 max-sm:text-sm"
                            [id]="'group-member-' + person.userId"
                            [title]="person.email"
                            (click)="pick(person.userId)"
                          >
                            {{ person.displayName }}
                          </button>
                        </li>
                      } @empty {
                        <li class="text-muted-foreground text-[0.8125rem]">
                          {{
                            filter()
                              ? 'Nobody matches.'
                              : 'Everyone in ' + (companyName() ?? 'the company') + ' is in it.'
                          }}
                        </li>
                      }
                    </ul>
                  }
                </div>
              }
            }
          </section>

          @if (editing(); as g) {
            <section
              class="flex flex-col gap-3 border-t px-4 pt-[18px] pb-5 sm:px-5"
              aria-labelledby="group-uses-title"
            >
              <div>
                <h3 id="group-uses-title" class="text-sm font-bold">
                  What its members may use through it
                </h3>
                @if (usage()) {
                  <p
                    class="text-muted-foreground mt-0.5 text-[0.8125rem]"
                    data-testid="group-usage-summary"
                  >
                    {{ summary() }}
                  </p>
                }
              </div>
              @if (usageError()) {
                <app-error-state
                  [error]="usageError()"
                  heading="Couldn’t read what it may use"
                  (retry)="retryUsage.emit()"
                />
              } @else if (usage(); as u) {
                @if (u.agents.length || u.grants.length || u.serviceIdentities.length) {
                  <dl
                    class="m-0 grid grid-cols-1 gap-x-3 gap-y-2 text-[0.8125rem] sm:grid-cols-[8rem_minmax(0,1fr)]"
                  >
                    @if (u.agents.length) {
                      <dt class="text-muted-foreground">Agents</dt>
                      <dd class="m-0 flex flex-wrap gap-x-2" data-testid="group-agents">
                        @for (agent of u.agents; track agent.id) {
                          <a
                            class="text-primary underline-offset-2 hover:underline"
                            [routerLink]="['/c', companyId(), 'agents', agent.id]"
                            >{{ agent.name }}</a
                          >
                        }
                        <span class="text-muted-foreground">· shared with it</span>
                      </dd>
                    }
                    @if (u.grants.length) {
                      <dt class="text-muted-foreground">Credentials</dt>
                      <dd class="m-0" data-testid="group-grants">
                        <ul class="m-0 flex list-none flex-col gap-0.5 p-0">
                          @for (grant of u.grants; track grant.id) {
                            <li>{{ phrase(grant) }}</li>
                          }
                        </ul>
                      </dd>
                    }
                    @if (u.serviceIdentities.length) {
                      <dt class="text-muted-foreground">Service accounts</dt>
                      <dd class="m-0 flex flex-wrap gap-x-2" data-testid="group-identities">
                        @for (identity of u.serviceIdentities; track identity.id) {
                          <a
                            class="text-primary font-mono text-xs underline-offset-2 hover:underline"
                            [routerLink]="['/c', companyId(), 'service-identities', identity.id]"
                            >{{ identity.name }}</a
                          >
                        }
                      </dd>
                    }
                  </dl>
                }
                <p class="text-muted-foreground m-0 text-xs">
                  Share an agent from the agent’s page; lend a credential on its system’s page,
                  under Credentials.
                </p>
              } @else {
                <div class="flex flex-col gap-1.5" aria-busy="true">
                  <hlm-skeleton class="h-4 w-48" />
                  <hlm-skeleton class="h-4 w-32" />
                </div>
              }
            </section>

            <section
              class="bg-muted/70 mt-auto flex flex-col gap-2 border-t px-4 py-4 sm:flex-row sm:items-start sm:gap-4 sm:px-5"
              aria-labelledby="group-end-title"
            >
              <div class="min-w-0 flex-1">
                <h3 id="group-end-title" class="text-sm font-semibold">Delete {{ g.name }}</h3>
                <p class="text-muted-foreground mt-0.5 text-[0.8125rem] text-pretty">
                  What it was given goes with it: its members no longer use the agents shared with
                  it, the credentials lent to it or the service accounts it may use. They stay
                  members of
                  {{ companyName() ?? 'the company' }}.
                </p>
              </div>
              <button
                hlmBtn
                variant="destructive"
                size="sm"
                type="button"
                class="self-start max-sm:h-11 max-sm:px-4"
                aria-haspopup="dialog"
                data-testid="delete-group"
                (click)="askDelete()"
              >
                <ng-icon name="lucideTrash2" aria-hidden="true" />
                Delete…
              </button>
            </section>
          }
        </div>

        @if (showBar()) {
          <div
            class="bg-card mx-3 mb-3 flex shrink-0 flex-wrap items-center gap-x-3 gap-y-2 rounded-[18px] border py-2.5 ps-4 pe-2.5 shadow-[0_10px_30px_-12px_rgb(0_0_0/0.25)]"
            role="region"
            aria-label="Unsaved changes"
            data-testid="group-changes"
          >
            <p class="m-0 flex min-w-0 flex-1 basis-40 flex-col text-sm leading-snug">
              <span class="font-semibold" data-testid="unsaved-count">{{ barTitle() }}</span>
              <span class="text-muted-foreground truncate text-xs" data-testid="unsaved-names">{{
                barDetail()
              }}</span>
            </p>
            <div class="ms-auto flex gap-2">
              <button
                hlmBtn
                variant="ghost"
                type="button"
                class="max-sm:h-11"
                [disabled]="groupForm().submitting()"
                (click)="discard()"
              >
                {{ editing() ? 'Discard' : 'Cancel' }}
              </button>
              <button
                hlmBtn
                type="submit"
                form="group-form"
                class="max-sm:h-11"
                data-testid="save-group"
                [disabled]="groupForm().submitting() || !canSave()"
              >
                @if (groupForm().submitting()) {
                  <hlm-spinner aria-hidden="true" />
                }
                {{ editing() ? 'Save' : 'Create group' }}
              </button>
            </div>
          </div>
        }
      </hlm-dialog-content>
    </hlm-dialog>

    <hlm-alert-dialog [state]="deleteOpen() ? 'open' : 'closed'" (closed)="deleteOpen.set(false)">
      <hlm-alert-dialog-content *hlmAlertDialogPortal="let ctx">
        <hlm-alert-dialog-header>
          <h2 hlmAlertDialogTitle>Delete {{ deleting()?.name }}?</h2>
          <p hlmAlertDialogDescription>
            What it was given goes with it: its members no longer use the agents shared with it, its
            the credentials lent to it or the service accounts it may use. They stay members of the
            company. You can't undo this.
          </p>
        </hlm-alert-dialog-header>
        @if (deleteError(); as message) {
          <div hlmAlert variant="destructive" data-testid="delete-error">
            <ng-icon name="lucideCircleAlert" aria-hidden="true" />
            <p hlmAlertDescription>{{ message }}</p>
          </div>
        }
        <hlm-alert-dialog-footer>
          <button hlmAlertDialogCancel [disabled]="deleteBusy()">Cancel</button>
          <button
            hlmAlertDialogAction
            variant="destructive"
            [disabled]="deleteBusy()"
            (click)="confirmDelete()"
          >
            @if (deleteBusy()) {
              <hlm-spinner aria-hidden="true" />
            }
            Delete group
          </button>
        </hlm-alert-dialog-footer>
      </hlm-alert-dialog-content>
    </hlm-alert-dialog>
  `,
})
export class GroupDialog {
  private readonly api = inject(Api);
  private readonly notify = inject(Notify);
  private readonly current = inject(CurrentCompany);
  private readonly data = inject(MembersData);

  /** The group opened, `new` for one to create; null closes the dialog. */
  readonly target = input<GroupTarget | null>(null);
  /** Each group's usage as the page reads it, by group id. */
  readonly usages = input<ReadonlyMap<string, GroupUsageState>>(new Map());
  readonly retryUsage = output<void>();
  readonly closed = output<void>();

  protected readonly companyId = this.current.id;
  protected readonly companyName = this.current.name;
  protected readonly firstError = firstError;

  /** The group being changed; null creates one. Kept while the dialog closes so the title holds. */
  protected readonly editing = signal<GroupSummary | null>(null);
  /** The group as read for a change, with its members; null while it loads or when creating. */
  protected readonly loadedGroup = signal<Group | null>(null);
  protected readonly loadingGroup = signal(false);
  protected readonly loadError = signal<unknown>(null);

  protected readonly groupModel = signal<GroupModel>(EMPTY);
  protected readonly groupForm = form(this.groupModel, (path) => {
    required(path.name, { message: 'Enter a name.' });
    validate(path.name, ({ value }) =>
      value() && !value().trim() ? { kind: 'blank', message: 'Enter a name.' } : undefined,
    );
    maxLength(path.name, 100, { message: 'At most 100 characters.' });
    maxLength(path.description, 500, { message: 'At most 500 characters.' });
  });
  protected readonly formError = signal<string | null>(null);
  /** The members picked, by user id, in the order they were. */
  protected readonly picked = signal<readonly string[]>([]);
  protected readonly adding = signal(false);
  protected readonly filter = signal('');

  /** The company's members for the picker, read each time the dialog opens. */
  protected readonly members = resource({
    params: () => (this.target() ? this.current.id() : undefined),
    loader: ({ params: companyId }) =>
      loadAll((cursor) => this.api.invoke(listMembers, { companyId, limit: 100, cursor })),
  });

  protected readonly people = computed(() =>
    pickablePeople(
      this.members.hasValue() ? this.members.value() : [],
      this.loadedGroup()?.members ?? [],
    ),
  );
  protected readonly pickedPeople = computed(() => {
    const byId = new Map(this.people().map((p) => [p.userId, p]));
    return this.picked()
      .map((id) => byId.get(id))
      .filter((p): p is PickablePerson => !!p)
      .sort((a, b) => a.displayName.localeCompare(b.displayName));
  });
  protected readonly options = computed(() => {
    const picked = new Set(this.picked());
    return this.people().filter((p) => !picked.has(p.userId) && matchesFilter(p, this.filter()));
  });

  /** The group's usage state as the page read it; null for a new group. */
  private readonly usageState = computed(() => {
    const group = this.editing();
    return group ? (this.usages().get(group.id) ?? null) : null;
  });
  protected readonly usage = computed(() => this.usageState()?.usage ?? null);
  protected readonly usageError = computed(() => this.usageState()?.error ?? null);
  protected readonly phrase = grantPhrase;
  protected readonly summary = computed(() => {
    const usage = this.usage();
    return usage ? usageSummary(usage) : '';
  });

  /**
   * Counts the dialog's openings and closings: a `getGroup` answer of an earlier opening is stale
   * and ignored, so it can neither overwrite newer edits nor end the current load.
   */
  private generation = 0;

  constructor() {
    effect(() => {
      const target = this.target();
      untracked(() => {
        if (target === 'new') this.startCreate();
        else if (target) void this.startEdit(target);
      });
    });
  }

  private startCreate(): void {
    this.generation++;
    this.loadingGroup.set(false);
    this.editing.set(null);
    this.loadedGroup.set(null);
    this.loadError.set(null);
    this.reset(EMPTY, []);
  }

  /** Opens at once with what the list knows; the members follow from `getGroup`. */
  private async startEdit(summary: GroupSummary): Promise<void> {
    this.editing.set(summary);
    this.loadedGroup.set(null);
    this.reset({ name: summary.name, description: summary.description ?? '' }, []);
    await this.readGroup();
  }

  protected async readGroup(): Promise<void> {
    const companyId = this.current.id();
    const summary = this.editing();
    if (!companyId || !summary) return;
    const generation = ++this.generation;
    this.loadError.set(null);
    this.loadingGroup.set(true);
    try {
      const group = await this.api.invoke(getGroup, { companyId, groupId: summary.id });
      if (generation !== this.generation) return;
      this.loadedGroup.set(group);
      this.reset(
        { name: group.name, description: group.description ?? '' },
        group.members.map((m) => m.userId),
      );
    } catch (error) {
      if (generation === this.generation) this.loadError.set(error);
    } finally {
      if (generation === this.generation) this.loadingGroup.set(false);
    }
  }

  /** Closing makes a load still under way stale too. */
  protected close(): void {
    this.generation++;
    this.loadingGroup.set(false);
    this.closed.emit();
  }

  private reset(model: GroupModel, memberIds: readonly string[]): void {
    this.groupModel.set({ ...model });
    this.groupForm().reset();
    this.picked.set([...memberIds]);
    this.filter.set('');
    this.adding.set(false);
    this.formError.set(null);
  }

  protected count(n: number): string {
    return `${n} ${n === 1 ? 'member' : 'members'}`;
  }

  protected initialsOf(name: string): string {
    return initials(name);
  }

  protected pick(userId: string): void {
    this.picked.update((ids) => (ids.includes(userId) ? ids : [...ids, userId]));
  }

  protected unpick(userId: string): void {
    this.picked.update((ids) => ids.filter((id) => id !== userId));
  }

  protected onFilter(event: Event): void {
    this.filter.set((event.target as HTMLInputElement).value);
  }

  // --- What changed ----------------------------------------------------------------------------

  /** The names of what differs from the group as read: Name, Description, Members. */
  protected readonly changes = computed(() => {
    const group = this.loadedGroup();
    if (!this.editing() || !group) return [];
    const m = this.groupModel();
    const changed: string[] = [];
    if (m.name.trim() !== group.name) changed.push('Name');
    if (m.description.trim() !== (group.description ?? '')) changed.push('Description');
    const before = new Set(group.members.map((p) => p.userId));
    const after = this.picked();
    if (after.length !== before.size || after.some((id) => !before.has(id))) {
      changed.push('Members');
    }
    return changed;
  });

  protected readonly showBar = computed(() => !this.editing() || this.changes().length > 0);
  protected readonly barTitle = computed(() => {
    if (!this.editing()) return 'A new group';
    const n = this.changes().length;
    return n === 1 ? '1 unsaved change' : `${n} unsaved changes`;
  });
  protected readonly barDetail = computed(() =>
    this.editing() ? this.changes().join(' · ') : 'Name it, then add its members.',
  );

  protected discard(): void {
    const group = this.loadedGroup();
    if (!this.editing()) {
      this.closed.emit();
      return;
    }
    if (group) {
      this.reset(
        { name: group.name, description: group.description ?? '' },
        group.members.map((m) => m.userId),
      );
    }
  }

  /** A change waits for the group's members: who was added and taken out is counted from them. */
  protected readonly canSave = computed(
    () => !this.editing() || (this.loadedGroup() !== null && !this.loadError()),
  );

  protected onSubmit(event: Event): void {
    event.preventDefault();
    const companyId = this.current.id();
    if (!companyId || !this.canSave()) return;
    this.formError.set(null);
    void submit(this.groupForm, async () => {
      const m = this.groupModel();
      const body: GroupInput = {
        name: m.name.trim(),
        description: m.description.trim() || null,
        memberIds: [...this.picked()],
      };
      const existing = this.editing();
      const read = this.loadedGroup();
      try {
        if (existing) {
          if (!read) return undefined;
          const saved = await this.save(companyId, read, body);
          this.applySaved(saved);
          this.reset(
            { name: saved.name, description: saved.description ?? '' },
            saved.members.map((p) => p.userId),
          );
          this.notify.success(`${saved.name} saved`);
        } else {
          const saved = await this.api.invoke(createGroup, { companyId, body });
          this.closed.emit();
          this.data.groups.refresh();
          this.notify.success(
            `${saved.name} created`,
            'Share agents with it, or lend it accounts with a grant.',
          );
        }
        return undefined;
      } catch (error) {
        const failure = mapSubmissionError(this.groupForm, error, {
          codeFields: { DUPLICATE_GROUP_NAME: 'name' },
          overrides: FORBIDDEN,
        });
        this.formError.set(failure.message);
        return failure.fieldErrors;
      }
    });
  }

  /**
   * Saves a group's changes against the group as read: each member added or taken out by
   * themselves, then, when the name or the description changed, the group replaced with the
   * members as the last answer gave them (read again when no member changed). A failure part way
   * keeps what was saved before it as the group read, with the changes not yet saved on top, so the
   * bar shows only those, and what another admin changed meanwhile is not undone by the next Save.
   */
  private async save(companyId: string, read: Group, body: GroupInput): Promise<Group> {
    const groupId = read.id;
    const before = new Set(read.members.map((p) => p.userId));
    const after = new Set(body.memberIds);
    const adds = body.memberIds.filter((id) => !before.has(id));
    const removes = [...before].filter((id) => !after.has(id));
    let saved: Group | null = null;
    try {
      for (const userId of adds) {
        saved = await this.api.invoke(addGroupMember, { companyId, groupId, userId });
      }
      for (const userId of removes) {
        saved = await this.api.invoke(removeGroupMember, { companyId, groupId, userId });
      }
      if (body.name !== read.name || (body.description ?? null) !== (read.description ?? null)) {
        const now = saved ?? (await this.api.invoke(getGroup, { companyId, groupId }));
        saved = await this.api.invoke(replaceGroup, {
          companyId,
          groupId,
          body: { ...body, memberIds: now.members.map((p) => p.userId) },
        });
      }
      return saved ?? read;
    } catch (error) {
      if (saved) {
        this.applySaved(saved);
        const kept = saved.members.map((p) => p.userId).filter((id) => !removes.includes(id));
        this.picked.set([...kept, ...adds.filter((id) => !kept.includes(id))]);
      }
      throw error;
    }
  }

  /** The group as the server answered a save: the dialog's group, its row and its members. */
  private applySaved(group: Group): void {
    this.data.putGroup(group);
    this.loadedGroup.set(group);
    this.editing.set({
      id: group.id,
      name: group.name,
      description: group.description ?? null,
      memberCount: group.members.length,
    });
  }

  // --- Delete ---------------------------------------------------------------------------------

  protected readonly deleting = signal<GroupSummary | null>(null);
  protected readonly deleteOpen = signal(false);
  protected readonly deleteBusy = signal(false);
  protected readonly deleteError = signal<string | null>(null);

  protected askDelete(): void {
    this.deleting.set(this.editing());
    this.deleteError.set(null);
    this.deleteOpen.set(true);
  }

  protected async confirmDelete(): Promise<void> {
    const companyId = this.current.id();
    const group = this.deleting();
    if (!companyId || !group || this.deleteBusy()) return;
    this.deleteBusy.set(true);
    this.deleteError.set(null);
    try {
      await this.api.invoke(deleteGroup, { companyId, groupId: group.id });
      this.deleteOpen.set(false);
      this.closed.emit();
      this.data.dropGroup(group.id);
      this.notify.success(`${group.name} deleted`);
    } catch (error) {
      this.deleteError.set(problemMessage(error, FORBIDDEN));
    } finally {
      this.deleteBusy.set(false);
    }
  }
}
