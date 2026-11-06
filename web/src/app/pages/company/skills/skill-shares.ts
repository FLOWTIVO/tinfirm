import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  linkedSignal,
  output,
  resource,
  signal,
} from '@angular/core';
import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideSearch, lucideUser, lucideUsers } from '@ng-icons/lucide';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmInput } from '@spartan-ng/helm/input';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { Api } from '../../../api/generated/api';
import { listGroups, listMembers, replaceSkillShares } from '../../../api/generated/functions';
import type { Skill } from '../../../api/generated/models';
import { problemMessage } from '../../../api/problem';
import { loadAll } from '../../../core/company-data';
import { Notify } from '../../../core/notify';
import { matchesPerson, sharePeople } from '../../../shared/share-people';
import { namesText } from '../agents/agent-labels';

const sameSet = (a: readonly string[], b: readonly string[]) =>
  a.length === b.length && [...a].sort().join('\n') === [...b].sort().join('\n');

/**
 * Whom a skill is shared with (the skills contract §4.3), as the agent editor shares an agent: the
 * company's groups and people on their own. Whoever edits the skill changes it here and saves it
 * (`replaceSkillShares`); anyone else reads it. Taking a share from someone takes the skill from
 * no agent that pins it.
 */
@Component({
  selector: 'app-skill-shares',
  imports: [NgIcon, HlmButton, HlmInput, HlmSpinner],
  providers: [provideIcons({ lucideSearch, lucideUser, lucideUsers })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    class: 'bg-card flex min-w-0 flex-col gap-3 rounded-xl border p-4',
    'data-testid': 'skill-sharing',
  },
  template: `
    <div class="flex flex-col gap-0.5">
      <h2 class="text-sm font-semibold" id="skill-sharing-title">Shared with</h2>
      <p class="text-muted-foreground text-xs text-pretty">
        Who may see it and add it to the agents they edit. Its owner and the company’s admins edit
        it.
      </p>
    </div>

    @if (!editable()) {
      @if (skill().shareGroupIds.length > 0 || skill().memberShares.length > 0) {
        <ul class="flex flex-wrap gap-1.5" aria-labelledby="skill-sharing-title">
          @for (id of skill().shareGroupIds; track id) {
            <li
              class="bg-secondary inline-flex h-6 max-w-full items-center gap-1 rounded-full px-2.5 text-xs font-semibold"
              [attr.data-group]="id"
            >
              <ng-icon
                name="lucideUsers"
                class="text-muted-foreground shrink-0"
                aria-hidden="true"
              />
              <span class="sr-only">Group</span>
              <span class="truncate" [title]="groupName(id)">{{ groupName(id) }}</span>
            </li>
          }
          @for (m of skill().memberShares; track m.principalId) {
            <li
              class="bg-secondary inline-flex h-6 max-w-full items-center gap-1 rounded-full px-2.5 text-xs font-semibold"
              [attr.data-person]="m.principalId"
            >
              <ng-icon
                name="lucideUser"
                class="text-muted-foreground shrink-0"
                aria-hidden="true"
              />
              <span class="sr-only">Person</span>
              <span class="truncate" [title]="m.name">{{ m.name }}</span>
            </li>
          }
        </ul>
      } @else {
        <p class="text-muted-foreground text-sm" data-testid="not-shared">
          Not shared: only {{ skill().ownerName ?? 'the company’s admins' }} and the admins see it.
        </p>
      }
    } @else {
      <div class="flex flex-col gap-1.5" data-testid="share-groups">
        <h3 class="text-xs font-semibold">Groups</h3>
        @if (groupsRes.isLoading()) {
          <p class="text-muted-foreground text-xs">Loading the groups…</p>
        } @else if (groupsRes.error()) {
          <p class="text-destructive text-xs" role="alert">
            Couldn't load the groups; sharing stays as it is.
          </p>
        } @else if (groups().length === 0) {
          <p class="text-muted-foreground text-xs">
            The company has no groups yet. You can still share it with people.
          </p>
        } @else {
          <ul class="flex flex-col gap-1.5">
            @for (g of groups(); track g.id) {
              @let on = groupIds().includes(g.id);
              <li class="min-w-0" [attr.data-group]="g.id">
                <label
                  class="flex min-h-11 min-w-0 cursor-pointer items-center gap-2.5 rounded-xl border px-3 py-1.5"
                  [class]="on ? 'border-primary/50 bg-primary/5' : 'hover:bg-accent'"
                >
                  <input
                    type="checkbox"
                    class="accent-primary size-4 shrink-0"
                    [checked]="on"
                    [disabled]="saving()"
                    (change)="toggleGroup(g.id, $any($event.target).checked)"
                  />
                  <span class="flex min-w-0 flex-col">
                    <span class="truncate text-sm font-semibold">{{ g.name }}</span>
                    <span class="text-muted-foreground text-xs"
                      >{{ g.memberCount }} {{ g.memberCount === 1 ? 'member' : 'members' }}</span
                    >
                  </span>
                </label>
              </li>
            }
          </ul>
        }
      </div>

      <div class="flex flex-col gap-1.5" data-testid="share-people">
        <h3 class="flex items-center justify-between gap-2 text-xs font-semibold">
          <span id="skill-sharing-people">People</span>
          <span class="text-muted-foreground font-normal">{{ principalIds().length }} picked</span>
        </h3>
        @if (membersRes.isLoading()) {
          <p class="text-muted-foreground text-xs">Loading the members…</p>
        } @else {
          @if (membersRes.error()) {
            <p class="text-destructive text-xs" role="alert">
              Couldn't load the company's members; only the people it is shared with are listed.
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
              class="w-full ps-9"
              type="search"
              autocomplete="off"
              placeholder="Find a person"
              aria-label="Find a person"
              data-testid="share-people-filter"
              [value]="filter()"
              (input)="filter.set($any($event.target).value)"
            />
          </div>
          <ul
            class="m-0 flex max-h-56 list-none flex-col overflow-y-auto rounded-lg border p-1"
            aria-labelledby="skill-sharing-people"
          >
            @for (person of shownPeople(); track person.principalId) {
              <li [attr.data-person]="person.principalId">
                <label
                  class="hover:bg-muted/60 flex cursor-pointer items-center gap-3 rounded-md px-2 py-1.5 text-sm"
                >
                  <input
                    type="checkbox"
                    class="accent-primary size-4 shrink-0"
                    [checked]="principalIds().includes(person.principalId)"
                    [disabled]="saving()"
                    (change)="togglePerson(person.principalId, $any($event.target).checked)"
                  />
                  <span class="flex min-w-0 flex-col">
                    <span class="truncate font-medium">{{ person.name }}</span>
                    @if (person.email) {
                      <span class="text-muted-foreground truncate text-xs">{{ person.email }}</span>
                    }
                  </span>
                </label>
              </li>
            } @empty {
              <li class="text-muted-foreground px-2 py-1.5 text-xs">
                {{ filter() ? 'Nobody matches.' : 'Nobody else to share it with.' }}
              </li>
            }
          </ul>
        }
      </div>

      @if (error(); as message) {
        <p class="text-destructive text-sm" role="alert" data-testid="share-error">
          {{ message }}
        </p>
      }
      @if (changed()) {
        <div class="flex flex-wrap items-center justify-end gap-2 border-t pt-3">
          <span class="text-muted-foreground me-auto text-xs" data-testid="share-summary">{{
            summary()
          }}</span>
          <button
            hlmBtn
            variant="ghost"
            size="sm"
            type="button"
            [disabled]="saving()"
            (click)="discard()"
          >
            Discard
          </button>
          <button
            hlmBtn
            size="sm"
            type="button"
            data-testid="save-shares"
            [disabled]="saving()"
            (click)="save()"
          >
            @if (saving()) {
              <hlm-spinner aria-hidden="true" />
            }
            Save sharing
          </button>
        </div>
      }
    }
  `,
})
export class SkillShares {
  private readonly api = inject(Api);
  private readonly notify = inject(Notify);

  readonly companyId = input.required<string>();
  readonly skill = input.required<Skill>();
  readonly editable = input(false);
  /** The skill as the server answered the change. */
  readonly saved = output<Skill>();

  protected readonly groupsRes = resource({
    params: () => this.companyId(),
    loader: ({ params: companyId }) =>
      loadAll((cursor) => this.api.invoke(listGroups, { companyId, limit: 100, cursor })),
  });
  protected readonly groups = computed(() =>
    this.groupsRes.hasValue() ? (this.groupsRes.value() ?? []) : [],
  );

  /** The company's members, for whoever edits the skill. */
  protected readonly membersRes = resource({
    params: () => (this.editable() ? this.companyId() : undefined),
    loader: ({ params: companyId }) =>
      loadAll((cursor) => this.api.invoke(listMembers, { companyId, limit: 100, cursor })),
  });
  private readonly people = computed(() =>
    sharePeople(
      this.membersRes.hasValue() ? (this.membersRes.value() ?? []) : [],
      this.skill().memberShares,
      { principalId: this.skill().ownerPrincipalId },
    ),
  );
  protected readonly filter = signal('');
  protected readonly shownPeople = computed(() =>
    this.people().filter((p) => matchesPerson(p, this.filter())),
  );

  protected readonly groupIds = linkedSignal(() => [...this.skill().shareGroupIds]);
  protected readonly principalIds = linkedSignal(() =>
    this.skill().memberShares.map((m) => m.principalId),
  );
  protected readonly changed = computed(
    () =>
      !sameSet(this.groupIds(), this.skill().shareGroupIds) ||
      !sameSet(
        this.principalIds(),
        this.skill().memberShares.map((m) => m.principalId),
      ),
  );
  /** Whom it will be shared with, once saved. */
  protected readonly summary = computed(() => {
    const groups = this.groups()
      .filter((g) => this.groupIds().includes(g.id))
      .map((g) => g.name);
    const people = this.people()
      .filter((p) => this.principalIds().includes(p.principalId))
      .map((p) => p.name);
    const names = [...groups, ...people];
    return names.length === 0 ? 'Private to its owner' : `Shared with ${namesText(names)}`;
  });
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);

  protected groupName(id: string): string {
    return this.groups().find((g) => g.id === id)?.name ?? 'A group';
  }

  protected toggleGroup(id: string, on: boolean): void {
    this.groupIds.update((ids) => [...ids.filter((g) => g !== id), ...(on ? [id] : [])]);
    this.error.set(null);
  }

  protected togglePerson(id: string, on: boolean): void {
    this.principalIds.update((ids) => [...ids.filter((p) => p !== id), ...(on ? [id] : [])]);
    this.error.set(null);
  }

  protected discard(): void {
    this.groupIds.set([...this.skill().shareGroupIds]);
    this.principalIds.set(this.skill().memberShares.map((m) => m.principalId));
    this.error.set(null);
  }

  protected async save(): Promise<void> {
    if (this.saving()) return;
    this.saving.set(true);
    this.error.set(null);
    try {
      const skill = await this.api.invoke(replaceSkillShares, {
        companyId: this.companyId(),
        skillId: this.skill().id,
        body: { groupIds: this.groupIds(), principalIds: this.principalIds() },
      });
      this.notify.success(
        'Sharing saved',
        'Taking a share from someone takes the skill from no agent that pins it.',
      );
      this.saved.emit(skill);
    } catch (error) {
      this.error.set(problemMessage(error));
    } finally {
      this.saving.set(false);
    }
  }
}
