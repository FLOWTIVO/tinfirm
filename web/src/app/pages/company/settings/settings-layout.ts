import {
  afterRenderEffect,
  ChangeDetectionStrategy,
  Component,
  computed,
  type ElementRef,
  inject,
  viewChild,
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import {
  type IsActiveMatchOptions,
  NavigationEnd,
  Router,
  RouterLink,
  RouterOutlet,
} from '@angular/router';
import { NgIcon, provideIcons } from '@ng-icons/core';
import {
  lucideBadgeCheck,
  lucideBrain,
  lucideBuilding2,
  lucideGauge,
  lucideKeyRound,
  lucideUsers,
} from '@ng-icons/lucide';
import { filter, map } from 'rxjs';
import { CurrentCompany } from '../../../core/current-company';
import { LicenceState } from '../../../core/licence';
import { EDITION_LABELS, seatsLine } from '../../../shared/licence-labels';
import { PageHeader } from '../../../shared/page-header';
import { SettingsData } from './settings-data';

const EXACT: IsActiveMatchOptions = {
  paths: 'exact',
  queryParams: 'ignored',
  fragment: 'ignored',
  matrixParams: 'ignored',
};

type SectionId = 'general' | 'limits' | 'memory' | 'secrets' | 'sign-in' | 'licence';

const SECTIONS: readonly { id: SectionId; path: string; label: string; icon: string }[] = [
  { id: 'general', path: '', label: 'General', icon: 'lucideBuilding2' },
  { id: 'limits', path: 'limits', label: 'Chats and webhooks', icon: 'lucideGauge' },
  { id: 'memory', path: 'memory', label: 'Memory', icon: 'lucideBrain' },
  { id: 'secrets', path: 'secrets', label: 'Secrets', icon: 'lucideKeyRound' },
  { id: 'sign-in', path: 'sign-in', label: 'Sign-in', icon: 'lucideUsers' },
  { id: 'licence', path: 'licence', label: 'Licence', icon: 'lucideBadgeCheck' },
];

/**
 * The company's settings (plan §5), one section a page: General (the name, the ID, members' own
 * MCP servers), Chats and webhooks (the limits on what arrives), Memory (the model that tidies
 * agents' memories, ADR-0065), Secrets (the vault), Sign-in (SCIM provisioning, ADR-0087) and the
 * instance's Licence, read-only (ADR-0077). Which providers people sign in with is the deployment's
 * configuration (ADR-0075), not a setting. The index at the left names each section with its
 * state; on a phone it is a row of pills above the section.
 */
@Component({
  selector: 'app-settings-layout',
  imports: [RouterLink, RouterOutlet, NgIcon, PageHeader],
  providers: [
    SettingsData,
    provideIcons({
      lucideBadgeCheck,
      lucideBrain,
      lucideBuilding2,
      lucideGauge,
      lucideKeyRound,
      lucideUsers,
    }),
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="flex flex-col gap-6">
      <app-page-header
        title="Settings"
        description="How the company is named, what may arrive from outside, how agents’ memories are tidied, the secrets in its vault, how its identity provider provisions people, and the licence it runs under."
      />

      <div class="grid gap-5 lg:grid-cols-[15rem_minmax(0,1fr)] lg:items-start lg:gap-8">
        <nav
          #index
          class="-mx-4 flex gap-1.5 overflow-x-auto px-4 pb-1 md:-mx-8 md:px-8 lg:sticky lg:top-18 lg:mx-0 lg:flex-col lg:gap-1 lg:overflow-visible lg:p-0"
          aria-label="Sections of the settings"
          data-testid="settings-sections"
        >
          @for (section of sections(); track section.id) {
            <a
              class="focus-visible:ring-ring/50 flex shrink-0 items-center gap-2.5 rounded-full border px-3.5 py-2.5 text-start outline-none focus-visible:ring-3 lg:items-start lg:rounded-xl lg:px-3"
              [class]="
                section.active
                  ? 'bg-card border-border shadow-[0_1px_3px_rgb(0_0_0/0.08)]'
                  : 'hover:bg-accent border-transparent'
              "
              [routerLink]="section.link"
              [attr.aria-current]="section.active ? 'page' : null"
              [attr.data-section]="section.id"
            >
              <ng-icon
                [name]="section.icon"
                size="16"
                class="shrink-0 lg:mt-0.5"
                [class.text-primary]="section.active"
                [class.text-muted-foreground]="!section.active"
                aria-hidden="true"
              />
              <span class="flex min-w-0 flex-col">
                <span class="text-sm font-semibold whitespace-nowrap">{{ section.label }}</span>
                @if (section.detail) {
                  <span
                    class="text-muted-foreground hidden text-xs leading-snug text-pretty lg:block"
                    data-testid="section-detail"
                    >{{ section.detail }}</span
                  >
                }
              </span>
            </a>
          }
        </nav>

        <div class="min-w-0">
          <router-outlet />
        </div>
      </div>
    </div>
  `,
})
export class SettingsLayout {
  private readonly router = inject(Router);
  private readonly current = inject(CurrentCompany);
  private readonly data = inject(SettingsData);
  private readonly licence = inject(LicenceState);

  private readonly url = toSignal(
    this.router.events.pipe(
      filter((event) => event instanceof NavigationEnd),
      map(() => this.router.url),
    ),
    { initialValue: this.router.url },
  );

  /** What each section holds now, as its index says it. */
  private readonly details = computed<Record<SectionId, string>>(() => ({
    general: 'Name, ID, members’ MCP servers',
    limits: 'How much may arrive',
    memory: this.memoryDetail(),
    secrets: this.secretsDetail(),
    'sign-in': 'SCIM provisioning',
    licence: this.licenceDetail(),
  }));

  protected readonly sections = computed(() => {
    this.url();
    const companyId = this.current.id() ?? '';
    const details = this.details();
    return SECTIONS.filter(
      (s) =>
        (s.id !== 'secrets' || this.data.canListSecrets()) &&
        (s.id !== 'memory' || this.data.canManageMemory()),
    ).map((s) => {
      const link = this.router.createUrlTree(
        ['/c', companyId, 'settings', ...(s.path ? [s.path] : [])],
        {},
      );
      return { ...s, link, active: this.router.isActive(link, EXACT), detail: details[s.id] };
    });
  });

  private readonly index = viewChild.required<ElementRef<HTMLElement>>('index');

  constructor() {
    // On a phone the index is a row of pills: the open section's is kept in view.
    afterRenderEffect(() => {
      this.sections();
      const nav = this.index().nativeElement;
      const open = nav.querySelector<HTMLElement>('[aria-current=page]');
      if (!open || nav.scrollWidth <= nav.clientWidth) return;
      const row = nav.getBoundingClientRect();
      const pill = open.getBoundingClientRect();
      if (pill.left < row.left || pill.right > row.right) {
        nav.scrollLeft += pill.left - row.left - 16;
      }
    });
  }

  private memoryDetail(): string {
    const memory = this.data.memory;
    if (memory.error() || !memory.hasValue()) return '';
    const settings = memory.value();
    if (!settings?.compactionModel) return 'Duplicates only';
    return settings.compactionModelAllowed
      ? `Tidied by ${settings.compactionModel}`
      : 'Model not allowed: duplicates only';
  }

  /** "Pro · 12 of 20 seats used". */
  private licenceDetail(): string {
    const licence = this.licence.value();
    return licence ? `${EDITION_LABELS[licence.edition]} · ${seatsLine(licence.seats)}` : '';
  }

  private secretsDetail(): string {
    const secrets = this.data.secrets;
    if (secrets.firstLoad() || secrets.failed()) return '';
    const items = secrets.items();
    if (items.length === 0) return 'None yet';
    const stored = `${items.length}${secrets.hasMore() ? '+' : ''} stored`;
    const managed = items.filter((s) => s.managed).length;
    return managed > 0 ? `${stored} · ${managed} managed` : stored;
  }
}
