import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  resource,
  signal,
  untracked,
} from '@angular/core';
import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideCircleAlert } from '@ng-icons/lucide';
import { HlmAlertImports } from '@spartan-ng/helm/alert';
import { HlmInput } from '@spartan-ng/helm/input';
import { HlmSkeleton } from '@spartan-ng/helm/skeleton';
import { Api } from '../../../api/generated/api';
import {
  getChatSettings,
  getCompanySettings,
  setChatSettings,
  updateCompanySettings,
} from '../../../api/generated/functions';
import type { ChatSettings, CompanySettings } from '../../../api/generated/models';
import { problemMessage, toProblem } from '../../../api/problem';
import { CurrentCompany } from '../../../core/current-company';
import { Notify } from '../../../core/notify';
import { can } from '../../../core/permissions';
import { ErrorState } from '../../../shared/error-state';
import { SettingRow } from './setting-row';
import { UnsavedChangesBar } from './unsaved-changes-bar';

type RateKey = 'perMinute' | 'perDay' | 'eventsPerMinute' | 'webhookDefault' | 'webhookMax';
type RateModel = Record<RateKey, string>;

/** Each rate, its range and what it limits (package 5, §5.2 and §5.12; open point 11). */
export const RATES: readonly {
  key: RateKey;
  label: string;
  unit: string;
  hint: string;
  max: number;
  group: 'chat' | 'webhook';
}[] = [
  {
    key: 'perMinute',
    label: 'Messages a minute, per chat user',
    unit: 'a minute',
    hint: 'More are refused, and the person told.',
    max: 600,
    group: 'chat',
  },
  {
    key: 'perDay',
    label: 'Messages a day, per chat user',
    unit: 'a day',
    hint: 'Counted over the day.',
    max: 10_000,
    group: 'chat',
  },
  {
    key: 'eventsPerMinute',
    label: 'Events a minute, per chat system',
    unit: 'a minute',
    hint: 'Everything Slack or Teams sends, a flood included.',
    max: 6000,
    group: 'chat',
  },
  {
    key: 'webhookDefault',
    label: 'Deliveries a minute, by default',
    unit: 'a minute',
    hint: 'For a webhook system that names none.',
    max: 6000,
    group: 'webhook',
  },
  {
    key: 'webhookMax',
    label: 'Deliveries a minute, at most',
    unit: 'a minute',
    hint: 'The most a webhook system may name; lowering it lowers those above.',
    max: 6000,
    group: 'webhook',
  },
];

function modelOf(chat: ChatSettings, company: CompanySettings): RateModel {
  const webhook = company.webhookDeliveriesPerMinute;
  return {
    perMinute: String(chat.messagesPerUserPerMinute),
    perDay: String(chat.messagesPerUserPerDay),
    eventsPerMinute: String(chat.eventsPerSystemPerMinute),
    webhookDefault: webhook ? String(webhook.default) : '',
    webhookMax: webhook ? String(webhook.max) : '',
  };
}

/** Why a rate is refused before it is sent, or null. */
export function rateError(model: RateModel, key: RateKey): string | null {
  const rate = RATES.find((r) => r.key === key)!;
  const n = Number(model[key]);
  if (!model[key].trim() || !Number.isInteger(n) || n < 1 || n > rate.max) {
    return `A whole number from 1 to ${rate.max.toLocaleString('en')}.`;
  }
  if (key === 'webhookDefault' && Number(model.webhookMax) >= 1 && n > Number(model.webhookMax)) {
    return 'At most the maximum.';
  }
  return null;
}

/**
 * The settings' Chats and webhooks section: the company's limits on what arrives from outside
 * (package 5; `GET`, `PUT /chat-settings` and `/settings`' `webhookDeliveriesPerMinute`): chat
 * messages per user, chat events per system, and webhook deliveries. They protect the company's
 * budget from a flood; the request size stays the operator's. Changes wait in the unsaved-changes
 * bar, each with the value it replaces. Each save carries the version it read, so that another
 * admin's change meanwhile is not written over: on `409 SETTINGS_CHANGED` the limits are read
 * again and the change waits to be made on top of them.
 */
@Component({
  selector: 'app-chat-rates-setting',
  imports: [
    NgIcon,
    HlmAlertImports,
    HlmInput,
    HlmSkeleton,
    ErrorState,
    SettingRow,
    UnsavedChangesBar,
  ],
  providers: [provideIcons({ lucideCircleAlert })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="flex flex-col gap-5" data-testid="chat-rates">
      <p class="text-muted-foreground max-w-3xl px-1 text-sm text-pretty">
        What arrives from Slack, Teams and webhooks beyond these limits is refused before any agent
        sees it, so a flood spends no budget. A person whose chat message is refused is told why.
      </p>
      @if (loading()) {
        <div class="flex flex-col gap-5" aria-busy="true">
          <hlm-skeleton class="h-56 w-full rounded-xl" />
          <hlm-skeleton class="h-40 w-full rounded-xl" />
        </div>
      } @else if (failed()) {
        <app-error-state
          [error]="chatRes.error() ?? companyRes.error()"
          heading="Couldn’t load the limits"
          (retry)="reload()"
        />
      } @else {
        <form id="chat-rates-form" class="flex flex-col gap-5" novalidate (submit)="save($event)">
          @for (group of groups; track group.key) {
            <section
              class="bg-card rounded-xl border"
              [attr.aria-labelledby]="'rates-' + group.key + '-title'"
            >
              <div class="px-4 pt-4 pb-3.5 sm:px-5">
                <h2 class="text-base font-bold" [id]="'rates-' + group.key + '-title'">
                  {{ group.label }}
                </h2>
                <p class="text-muted-foreground mt-0.5 text-sm">{{ group.description }}</p>
              </div>
              @for (rate of ratesOf(group.key); track rate.key) {
                <app-setting-row
                  [rowId]="'rate-' + rate.key"
                  [label]="rate.label"
                  [for]="'rate-' + rate.key"
                >
                  <span help>{{ rate.hint }}</span>
                  <div class="flex flex-wrap items-center gap-2.5">
                    <input
                      hlmInput
                      inputmode="numeric"
                      class="w-28 text-right font-mono tabular-nums"
                      [id]="'rate-' + rate.key"
                      [value]="model()[rate.key]"
                      [readOnly]="!canChange()"
                      [attr.aria-invalid]="!!errorOf(rate.key)"
                      [attr.aria-describedby]="'rate-' + rate.key + '-help'"
                      (input)="set(rate.key, $event)"
                    />
                    <span class="text-muted-foreground text-sm">{{ rate.unit }}</span>
                    @if (wasOf(rate.key); as was) {
                      <span class="text-faint text-xs" data-testid="rate-was"
                        >was <span class="font-mono tabular-nums">{{ was }}</span></span
                      >
                    }
                  </div>
                  @if (errorOf(rate.key); as message) {
                    <p
                      class="text-destructive flex items-center gap-1.5 text-xs font-medium"
                      role="alert"
                    >
                      <ng-icon name="lucideCircleAlert" size="14" aria-hidden="true" />
                      {{ message }}
                    </p>
                  }
                </app-setting-row>
              }
            </section>
          }
        </form>
        @if (saveError(); as message) {
          <div hlmAlert variant="destructive" data-testid="chat-rates-error">
            <ng-icon name="lucideCircleAlert" aria-hidden="true" />
            <p hlmAlertDescription>{{ message }}</p>
          </div>
        }
        @if (canChange() && changes().length > 0) {
          <app-unsaved-changes-bar
            form="chat-rates-form"
            saveTestId="chat-rates-save"
            saveLabel="Save the limits"
            [changes]="changes()"
            [saving]="saving()"
            [blocked]="invalid() ? 'Correct the marked limit first.' : null"
            (discard)="discard()"
          />
        }
      }
    </div>
  `,
})
export class ChatRatesSetting {
  private readonly api = inject(Api);
  private readonly notify = inject(Notify);
  private readonly current = inject(CurrentCompany);

  protected readonly groups = [
    {
      key: 'chat' as const,
      label: 'Slack and Teams',
      description: 'Counted per chat user and per chat system.',
    },
    {
      key: 'webhook' as const,
      label: 'Webhooks',
      description: 'A webhook system may name its own limit, up to the most allowed here.',
    },
  ];
  protected readonly canChange = computed(() => can.manageSystems(this.current.access()));

  protected readonly chatRes = resource({
    params: () => this.current.id(),
    loader: ({ params: companyId }) => this.api.invoke(getChatSettings, { companyId }),
  });
  protected readonly companyRes = resource({
    params: () => this.current.id(),
    loader: ({ params: companyId }) => this.api.invoke(getCompanySettings, { companyId }),
  });
  protected readonly loading = computed(
    () =>
      (this.chatRes.isLoading() && !this.chatRes.hasValue()) ||
      (this.companyRes.isLoading() && !this.companyRes.hasValue()),
  );
  protected readonly failed = computed(() => !!this.chatRes.error() || !!this.companyRes.error());

  private readonly savedModel = computed(() =>
    this.chatRes.hasValue() && this.companyRes.hasValue()
      ? modelOf(this.chatRes.value(), this.companyRes.value())
      : null,
  );
  protected readonly model = signal<RateModel>({
    perMinute: '',
    perDay: '',
    eventsPerMinute: '',
    webhookDefault: '',
    webhookMax: '',
  });
  /** The limits that differ from the saved ones, by name. */
  protected readonly changes = computed(() => {
    const saved = this.savedModel();
    if (!saved) return [];
    return RATES.filter(({ key }) => this.model()[key].trim() !== saved[key]).map((r) => r.label);
  });
  protected readonly invalid = computed(() => RATES.some(({ key }) => this.errorOf(key)));
  protected readonly saving = signal(false);
  protected readonly saveError = signal<string | null>(null);

  constructor() {
    effect(() => {
      const saved = this.savedModel();
      if (saved) untracked(() => this.model.set({ ...saved }));
    });
  }

  protected ratesOf(group: 'chat' | 'webhook') {
    return RATES.filter((rate) => rate.group === group);
  }

  protected errorOf(key: RateKey): string | null {
    return rateError(this.model(), key);
  }

  /** The saved value a changed limit replaces, or null. */
  protected wasOf(key: RateKey): string | null {
    const saved = this.savedModel();
    return saved && this.model()[key].trim() !== saved[key] ? saved[key] : null;
  }

  protected set(key: RateKey, event: Event): void {
    const value = (event.target as HTMLInputElement).value;
    this.model.update((m) => ({ ...m, [key]: value }));
  }

  protected discard(): void {
    const saved = this.savedModel();
    if (saved) this.model.set({ ...saved });
    this.saveError.set(null);
  }

  protected reload(): void {
    this.chatRes.reload();
    this.companyRes.reload();
  }

  protected async save(event: Event): Promise<void> {
    event.preventDefault();
    const companyId = this.current.id();
    const saved = this.savedModel();
    const chat = this.chatRes.hasValue() ? this.chatRes.value() : null;
    const company = this.companyRes.hasValue() ? this.companyRes.value() : null;
    if (!companyId || !saved || !chat || !company || this.saving()) return;
    if (this.invalid()) return;
    const m = this.model();
    const changed = (keys: RateKey[]) => keys.some((key) => m[key].trim() !== saved[key]);
    this.saving.set(true);
    this.saveError.set(null);
    try {
      if (changed(['perMinute', 'perDay', 'eventsPerMinute'])) {
        this.chatRes.set(
          await this.api.invoke(setChatSettings, {
            companyId,
            body: {
              messagesPerUserPerMinute: Number(m.perMinute),
              messagesPerUserPerDay: Number(m.perDay),
              eventsPerSystemPerMinute: Number(m.eventsPerMinute),
              version: chat.version,
            },
          }),
        );
      }
      if (changed(['webhookDefault', 'webhookMax'])) {
        this.companyRes.set(
          await this.api.invoke(updateCompanySettings, {
            companyId,
            body: {
              // Required by the request; sent as it is.
              triggerFiringsPerHour: company.triggerFiringsPerHour,
              webhookDeliveriesPerMinute: {
                default: Number(m.webhookDefault),
                max: Number(m.webhookMax),
              },
              version: company.version,
            },
          }),
        );
      }
      this.notify.success('The limits are saved');
    } catch (error) {
      this.saveError.set(problemMessage(error));
      // Someone else saved first: show their limits, and the change is made again on top of them.
      if (toProblem(error).code === 'SETTINGS_CHANGED') this.reload();
    } finally {
      this.saving.set(false);
    }
  }
}
