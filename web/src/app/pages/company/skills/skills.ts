import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  inject,
  input,
  linkedSignal,
  resource,
} from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideLibraryBig, lucidePlus, lucideSearch } from '@ng-icons/lucide';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmEmptyImports } from '@spartan-ng/helm/empty';
import { HlmInput } from '@spartan-ng/helm/input';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { Api } from '../../../api/generated/api';
import { listGroups, listSkills } from '../../../api/generated/functions';
import type { SkillSummary } from '../../../api/generated/models';
import { loadAll } from '../../../core/company-data';
import { CurrentCompany } from '../../../core/current-company';
import { pagedResource } from '../../../core/paged-resource';
import { mayWork } from '../../../core/permissions';
import { ErrorState } from '../../../shared/error-state';
import { ListSkeleton } from '../../../shared/list-skeleton';
import { PageHeader } from '../../../shared/page-header';
import { ownerText, skillSharingText, usedByText } from './skill-labels';

const PAGE_SIZE = 100;
/** How long typing waits before the search runs. */
const SEARCH_DELAY_MS = 300;

/**
 * The company's library of skills (ADR-0064, the skills contract §6): the live skills the reader
 * sees, by name (their own, those shared with their groups or with them, and for OWNER and ADMIN
 * every skill), searched on name and description (`?q=`). Each row says its latest version, its
 * owner, whom it is shared with and, for whoever edits it, how many agents pin it. Every member
 * creates skills.
 */
@Component({
  selector: 'app-skills',
  imports: [
    RouterLink,
    NgIcon,
    HlmButton,
    HlmEmptyImports,
    HlmInput,
    HlmSpinner,
    ErrorState,
    ListSkeleton,
    PageHeader,
  ],
  providers: [provideIcons({ lucideLibraryBig, lucidePlus, lucideSearch })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @let companyId = current.id();
    <div class="flex flex-col gap-5">
      <app-page-header
        title="Skills"
        description="Know-how agents read when a task fits: a SKILL.md and the files it needs. An agent pins a version; a new version reaches no agent until its editors move the pin."
      >
        @if (member()) {
          <a hlmBtn data-testid="new-skill" [routerLink]="['/c', companyId, 'skills', 'new']">
            <ng-icon name="lucidePlus" aria-hidden="true" />
            New skill
          </a>
        }
      </app-page-header>

      <form
        class="relative sm:max-w-sm"
        role="search"
        data-testid="skills-search"
        (submit)="$event.preventDefault(); search(typed())"
      >
        <ng-icon
          name="lucideSearch"
          class="text-faint pointer-events-none absolute start-3 top-1/2 -translate-y-1/2"
          aria-hidden="true"
        />
        <input
          hlmInput
          id="skills-search"
          class="w-full ps-9 max-sm:h-11 max-sm:text-base"
          type="search"
          autocomplete="off"
          maxlength="100"
          placeholder="Find a skill by name or description"
          aria-label="Find a skill"
          [value]="typed()"
          (input)="onType($any($event.target).value)"
        />
      </form>

      @if (skills.firstLoad()) {
        <app-list-skeleton [rows]="3" />
      } @else if (skills.failed()) {
        <app-error-state
          [error]="skills.error()"
          heading="Couldn't load the skills"
          (retry)="skills.retry()"
        />
      } @else if (skills.empty()) {
        <div hlmEmpty class="border border-dashed" data-testid="skills-empty">
          <div hlmEmptyHeader>
            <div hlmEmptyMedia variant="icon">
              <ng-icon name="lucideLibraryBig" aria-hidden="true" />
            </div>
            <div hlmEmptyTitle>{{ query() ? 'No skill matches' : 'No skills yet' }}</div>
            <p hlmEmptyDescription>
              @if (query()) {
                No skill you see has “{{ query() }}” in its name or description.
              } @else {
                A skill is a procedure written once for every agent that needs it: its SKILL.md and
                any scripts, references or templates. Yours stay private until you share them.
              }
            </p>
          </div>
          @if (query()) {
            <div hlmEmptyContent>
              <button hlmBtn variant="outline" size="sm" type="button" (click)="clear()">
                Show every skill
              </button>
            </div>
          } @else if (member()) {
            <div hlmEmptyContent>
              <a
                hlmBtn
                variant="outline"
                size="sm"
                [routerLink]="['/c', companyId, 'skills', 'new']"
              >
                <ng-icon name="lucidePlus" aria-hidden="true" />
                Create a skill
              </a>
            </div>
          }
        </div>
      } @else {
        <ul class="bg-card flex flex-col divide-y rounded-xl border" aria-label="Skills">
          @for (skill of skills.items(); track skill.id) {
            <li
              class="flex min-w-0 flex-col gap-1 px-4 py-3.5"
              [attr.data-testid]="'skill-' + skill.id"
            >
              <div class="flex flex-wrap items-center gap-x-2 gap-y-1">
                <a
                  class="focus-visible:ring-ring/50 rounded-sm font-mono text-sm font-semibold underline-offset-2 outline-none hover:underline focus-visible:ring-3"
                  data-testid="skill-link"
                  [routerLink]="['/c', companyId, 'skills', skill.id]"
                  >{{ skill.name }}</a
                >
                <span
                  class="bg-secondary text-muted-foreground inline-flex h-6 items-center rounded-full px-2.5 font-mono text-xs font-semibold"
                  data-testid="skill-version"
                  [attr.title]="'Its latest version is ' + skill.latestVersion"
                  >v{{ skill.latestVersion }}</span
                >
              </div>
              <p class="text-muted-foreground text-sm text-pretty">{{ skill.description }}</p>
              <p class="text-muted-foreground text-xs" data-testid="skill-meta">
                {{ meta(skill) }}
              </p>
            </li>
          }
        </ul>

        @if (skills.moreFailed()) {
          <app-error-state
            [error]="skills.error()"
            heading="Couldn't load more"
            (retry)="skills.retry()"
          />
        } @else if (skills.hasMore()) {
          <div class="flex justify-center">
            <button
              hlmBtn
              variant="outline"
              size="sm"
              type="button"
              [disabled]="skills.loadingMore()"
              (click)="skills.loadMore()"
            >
              @if (skills.loadingMore()) {
                <hlm-spinner aria-hidden="true" />
              }
              Load more
            </button>
          </div>
        }
      }
    </div>
  `,
})
export class Skills {
  private readonly api = inject(Api);
  private readonly router = inject(Router);
  protected readonly current = inject(CurrentCompany);

  /** `?q=` keeps the skills whose name or description holds it. */
  readonly q = input<string | undefined>(undefined);

  /** A member who may create skills: a Viewer writes nothing (ADR-0077). */
  protected readonly member = computed(() => mayWork(this.current.access()));
  protected readonly query = computed(() => (this.q() ?? '').trim());
  /** What the search field holds, ahead of the address while the reader types. */
  protected readonly typed = linkedSignal(() => this.q() ?? '');
  private timer: ReturnType<typeof setTimeout> | undefined;

  protected readonly skills = pagedResource<SkillSummary, { companyId: string; q: string }>({
    params: () => {
      const companyId = this.current.id();
      return companyId ? { companyId, q: this.query() } : undefined;
    },
    load: ({ companyId, q }, cursor) =>
      this.api.invoke(listSkills, {
        companyId,
        ...(q ? { q } : {}),
        limit: PAGE_SIZE,
        cursor,
      }),
  });

  /** The company's groups, to name those a skill is shared with. */
  private readonly groupsRes = resource({
    params: () => this.current.id(),
    loader: ({ params: companyId }) =>
      loadAll((cursor) => this.api.invoke(listGroups, { companyId, limit: 100, cursor })),
  });
  private readonly groupNames = computed(() => {
    const groups = this.groupsRes.hasValue() ? (this.groupsRes.value() ?? []) : [];
    return new Map(groups.map((g) => [g.id, g.name]));
  });

  constructor() {
    inject(DestroyRef).onDestroy(() => clearTimeout(this.timer));
  }

  /** "Version 3 · owned by Bob Svoboda · shared with Analysts · used by 2 agents". */
  protected meta(skill: SkillSummary): string {
    const names = this.groupNames();
    const groups = skill.shareGroupIds.flatMap((id) => (names.has(id) ? [names.get(id)!] : []));
    const sharing = skillSharingText(
      groups,
      skill.shareGroupIds.length,
      [],
      skill.sharePrincipalIds.length,
    );
    const parts = [ownerText(skill.ownerName), sharing.charAt(0).toLowerCase() + sharing.slice(1)];
    const used = usedByText(skill.agentCount);
    if (used) parts.push(used.charAt(0).toLowerCase() + used.slice(1));
    const text = parts.join(' · ');
    return text.charAt(0).toUpperCase() + text.slice(1);
  }

  protected onType(value: string): void {
    this.typed.set(value);
    clearTimeout(this.timer);
    this.timer = setTimeout(() => this.search(value), SEARCH_DELAY_MS);
  }

  protected search(value: string): void {
    clearTimeout(this.timer);
    const q = value.trim();
    if (q === this.query()) return;
    void this.router.navigate([], {
      queryParams: { q: q || null },
      queryParamsHandling: 'merge',
      replaceUrl: true,
    });
  }

  protected clear(): void {
    this.typed.set('');
    this.search('');
  }
}
