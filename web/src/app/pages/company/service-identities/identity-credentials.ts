import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  output,
  resource,
  untracked,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { NgIcon, provideIcons } from '@ng-icons/core';
import { HlmButton } from '@spartan-ng/helm/button';
import { Api } from '../../../api/generated/api';
import { listServiceIdentityCredentials } from '../../../api/generated/functions';
import type { BorrowedCredential, ServiceIdentity, SystemRef } from '../../../api/generated/models';
import { sameParams } from '../../../core/paged-resource';
import { ErrorState } from '../../../shared/error-state';
import { Time } from '../../../shared/time';
import {
  ACCESS_LABELS,
  type CredentialTone,
  credentialState,
  triggersActing,
} from '../systems/credential-labels';
import { CredentialOpenTriggers } from '../systems/credential-open-triggers';
import { SYSTEM_KIND_ICONS, systemKindIcon } from '../systems/system-kinds';

/** What a service account borrows, for its page's summary and its note on a chat bot. */
export interface BorrowedSummary {
  /** The systems it borrows a credential on, by name; a chat bot's not among them. */
  readonly systems: readonly string[];
  /** The chat systems whose bot it borrows (package 5, C9). */
  readonly bots: readonly SystemRef[];
}

/** What the page shows of what a service account borrows: a chat bot apart. */
export function borrowedSummary(items: readonly BorrowedCredential[]): BorrowedSummary {
  return {
    systems: items.filter((item) => !item.bot).map((item) => item.system.name),
    bots: items.filter((item) => item.bot).map((item) => item.system),
  };
}

const CHIP = 'inline-flex h-6 items-center rounded-full border px-2.5 text-xs font-semibold';
const TONES: Record<CredentialTone, string> = {
  success: 'bg-card text-success border-success/40',
  human: 'bg-human-soft text-human border-transparent',
  destructive: 'bg-destructive/10 text-destructive border-transparent',
  neutral: 'bg-muted text-muted-foreground border-transparent',
};

/**
 * The credentials a service account borrows, one per system at most (ADR-0072, decision 5), by the
 * system's name: the credential, its state, the access it is lent with, since when, and how many of
 * the service account's triggers act with it, and those of them open to anyone by name
 * (`openTriggers`, D2); a chat system's bot marked. Read-only: each links to
 * the credential on its system's page, where credentials are lent, changed and withdrawn. It tells
 * the page what it borrows (`summary`).
 */
@Component({
  selector: 'app-identity-credentials',
  imports: [RouterLink, NgIcon, HlmButton, CredentialOpenTriggers, ErrorState, Time],
  providers: [provideIcons({ ...SYSTEM_KIND_ICONS })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block min-w-0' },
  template: `
    <section
      class="bg-card flex flex-col rounded-xl border"
      aria-labelledby="borrowed-title"
      data-testid="identity-credentials"
    >
      <div class="flex flex-col gap-1 p-4 sm:p-5">
        <h2 id="borrowed-title" class="text-base font-bold">Borrowed credentials</h2>
        <p class="text-muted-foreground max-w-prose text-[0.8125rem] text-pretty">
          {{ identity().name }} acts on each system with the credential lent to it there, at most
          one per system, in the triggers of whoever may use it. Credentials are lent, changed and
          withdrawn on each system’s page.
        </p>
      </div>

      @if (borrowed.error() && !borrowed.hasValue()) {
        <div class="border-t p-4 sm:p-5">
          <app-error-state
            [error]="borrowed.error()"
            heading="Couldn't load what it borrows"
            (retry)="borrowed.reload()"
          />
        </div>
      } @else if (!borrowed.hasValue()) {
        <p class="text-muted-foreground border-t p-4 text-sm sm:p-5" role="status">
          Loading what it borrows…
        </p>
      } @else if (items().length === 0) {
        <p
          class="text-muted-foreground m-4 mt-0 rounded-lg border border-dashed px-3 py-4 text-center text-sm text-pretty sm:m-5 sm:mt-0"
          data-testid="borrowed-empty"
        >
          It borrows no credential yet. Lend it one under Credentials on a system’s page:
          <a
            class="text-primary underline underline-offset-2"
            [routerLink]="['/c', companyId(), 'systems']"
            >open Systems</a
          >.
        </p>
      } @else {
        <ul class="divide-y border-t">
          @for (item of items(); track item.credential.id) {
            @let state = stateOf(item);
            <li
              class="flex flex-wrap items-center gap-x-3.5 gap-y-2 px-4 py-3.5 sm:px-5"
              data-testid="borrowed-credential"
              [attr.data-system]="item.system.handle"
            >
              <span
                class="bg-muted flex size-10 shrink-0 items-center justify-center rounded-[10px] border"
                aria-hidden="true"
              >
                <ng-icon [name]="kindIcon(item)" size="18" />
              </span>
              <div class="flex min-w-0 flex-1 basis-56 flex-col gap-0.5">
                <span class="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1">
                  <span class="font-semibold break-words">{{ item.system.name }}</span>
                  <span class="text-muted-foreground" aria-hidden="true">·</span>
                  <span class="break-words" data-testid="borrowed-name">{{
                    item.credential.name
                  }}</span>
                  @if (item.bot) {
                    <span
                      [class]="chip + ' bg-secondary text-secondary-foreground border-transparent'"
                      data-testid="borrowed-bot"
                      >Chat bot</span
                    >
                  }
                  <span [class]="chip + ' ' + tones[state.tone]" data-testid="borrowed-state">{{
                    state.label
                  }}</span>
                </span>
                <span class="text-muted-foreground text-[0.8125rem]" data-testid="borrowed-access">
                  {{ accessLabel(item) }}
                  @if (item.credential.login) {
                    · as
                    <span class="text-foreground font-mono text-xs break-all">{{
                      item.credential.login
                    }}</span>
                  }
                </span>
                <span class="text-muted-foreground text-xs" data-testid="borrowed-since">
                  Lent since <app-time [value]="item.createdAt" mode="day" />
                  @if (!item.bot) {
                    · {{ triggersActing(item.triggers) }}
                  }
                </span>
                <app-credential-open-triggers
                  class="mt-1"
                  [companyId]="companyId()"
                  [triggers]="item.openTriggers ?? []"
                  [showIdentity]="false"
                />
              </div>
              <a
                hlmBtn
                variant="ghost"
                size="sm"
                class="max-sm:h-11"
                data-testid="borrowed-link"
                [routerLink]="['/c', companyId(), 'systems', item.system.id]"
                [queryParams]="item.bot ? { tab: 'bot' } : {}"
                [fragment]="item.bot ? undefined : 'credential-' + item.credential.id"
                >{{ item.bot ? 'Open its bot' : 'Change on ' + item.system.name }}</a
              >
            </li>
          }
        </ul>
      }
    </section>
  `,
})
export class IdentityCredentials {
  private readonly api = inject(Api);

  readonly companyId = input.required<string>();
  readonly identity = input.required<ServiceIdentity>();
  /** What it borrows, each time it is read. */
  readonly summary = output<BorrowedSummary>();

  protected readonly chip = CHIP;
  protected readonly tones = TONES;
  protected readonly triggersActing = triggersActing;

  private readonly key = computed(
    () => ({ companyId: this.companyId(), serviceIdentityId: this.identity().id }),
    { equal: sameParams },
  );
  protected readonly borrowed = resource({
    params: () => this.key(),
    loader: ({ params }) => this.api.invoke(listServiceIdentityCredentials, params),
  });
  protected readonly items = computed<readonly BorrowedCredential[]>(() =>
    this.borrowed.hasValue() ? this.borrowed.value().items : [],
  );

  constructor() {
    effect(() => {
      if (!this.borrowed.hasValue()) return;
      const summary = borrowedSummary(this.items());
      untracked(() => this.summary.emit(summary));
    });
  }

  protected stateOf(item: BorrowedCredential): ReturnType<typeof credentialState> {
    return credentialState(item.credential);
  }

  /** "Read and write": the access it is lent with; a bot's is its own. */
  protected accessLabel(item: BorrowedCredential): string {
    return item.bot ? 'Receives and posts in the chat' : ACCESS_LABELS[item.access];
  }

  protected kindIcon(item: BorrowedCredential): string {
    return systemKindIcon(item.system.kind);
  }
}
