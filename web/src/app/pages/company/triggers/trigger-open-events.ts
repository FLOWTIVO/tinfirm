import { ChangeDetectionStrategy, Component, computed, input, model, output } from '@angular/core';
import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideShieldAlert } from '@ng-icons/lucide';
import { HlmButton } from '@spartan-ng/helm/button';
import type { TriggerOpenToAnyone } from '../../../api/generated/models';
import { Time } from '../../../shared/time';

/**
 * The warning of a trigger anyone may drive (plan §4.5; the package 4 contract, §5.8,
 * `openToAnyone`): who can drive the agent with these accounts, and what would run without an
 * approval, as whom; the fix in one click ("Ask before every write"); and, while a write still
 * runs without approval, with the author's own account or a credential lent to a service account
 * alike, "I understand" (`acknowledgeOpenEvents`, V14; the security review of 2026-10-27, D2: a
 * service account is no way out, and whoever lent its credential is told). Orange only while that
 * acknowledgement is missing: then the save waits on the reader.
 */
@Component({
  selector: 'app-trigger-open-events',
  imports: [NgIcon, HlmButton, Time],
  providers: [provideIcons({ lucideShieldAlert })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  template: `
    @let o = open();
    <section
      class="bg-card flex flex-col gap-3 rounded-xl p-4 text-sm"
      [class]="missing() ? 'border-human border-2' : 'border'"
      role="region"
      aria-labelledby="trigger-open-title"
      data-testid="open-to-anyone"
    >
      <h2
        class="flex items-center gap-1.5 font-bold"
        [class]="missing() ? 'text-human' : o.writes.length ? 'text-warning' : ''"
        id="trigger-open-title"
      >
        <ng-icon name="lucideShieldAlert" class="shrink-0" aria-hidden="true" />
        Anyone can drive this agent
      </h2>
      <p class="text-pretty">
        {{ capitalised(o.who) }} can drive {{ agentName() ?? 'the agent' }} with these accounts.
        @if (o.writes.length) {
          Without an approval it would:
        } @else {
          Every write asks the team first.
        }
      </p>
      @if (o.writes.length) {
        <ul class="flex flex-col gap-1" data-testid="open-writes">
          @for (w of o.writes; track w.systemId + w.tool) {
            <li class="list-inside list-disc">
              <span class="font-mono text-xs">{{ w.tool }}</span> on
              {{ w.systemName ?? 'a system' }}, as
              @if (w.own) {
                <strong>you</strong> (your own account)
              } @else {
                {{ w.holderName ?? 'a service account' }}
              }
            </li>
          }
        </ul>
        <div class="flex flex-col gap-2 sm:flex-row sm:flex-wrap">
          <button
            hlmBtn
            variant="outline"
            size="sm"
            type="button"
            class="max-sm:h-11"
            data-testid="ask-every-write"
            (click)="askEveryWrite.emit()"
          >
            Ask before every write
          </button>
        </div>
      }
      @if (o.acknowledgementNeeded && !o.acknowledgementMissing && acknowledgedAt()) {
        <p class="text-muted-foreground text-xs" data-testid="acknowledged-before">
          Its author acknowledged this <app-time [value]="acknowledgedAt()!" />.
        </p>
      } @else if (o.acknowledgementNeeded) {
        <label class="flex min-h-11 items-start gap-2 sm:min-h-0" data-testid="acknowledge">
          <input
            type="checkbox"
            class="accent-primary mt-0.5 size-4 shrink-0"
            id="trigger-acknowledge"
            [checked]="acknowledged()"
            [attr.aria-invalid]="!!ackError() || null"
            (change)="acknowledged.set($any($event.target).checked)"
          />
          <span
            ><span class="font-semibold">I understand</span
            ><span class="text-muted-foreground"
              >: anyone who may write there drives the agent, which then writes without asking,
              {{ ackWith() }}.</span
            ></span
          >
        </label>
        @if (ackError(); as message) {
          <p class="text-destructive text-xs" role="alert" data-error="acknowledge">
            {{ message }}
          </p>
        }
      }
    </section>
  `,
})
export class TriggerOpenEvents {
  readonly open = input.required<TriggerOpenToAnyone>();
  readonly agentName = input<string | null>(null);
  readonly acknowledged = model(false);
  /** When the stored trigger's author acknowledged it: a change needs no new tick. */
  readonly acknowledgedAt = input<string | null>(null);
  readonly ackError = input<string | null>(null);

  /** "Ask before every write": every write tool of the agent asks the team. */
  readonly askEveryWrite = output<void>();

  /** The save waits for "I understand": the only orange of the editor. */
  protected readonly missing = computed(
    () => !!this.open().acknowledgementMissing && !this.acknowledged(),
  );

  /** As whom the open writes act, in the acknowledgement's words (D2: own and lent alike). */
  protected readonly ackWith = computed(() => {
    const writes = this.open().writes;
    const own = writes.some((w) => w.own);
    const lent = writes.some((w) => !w.own);
    if (own && lent) {
      return 'with my own account and with a credential lent to a service account, whose lender is told';
    }
    if (lent) return 'with a credential lent to a service account, whose lender is told';
    return 'with my own account';
  });

  protected capitalised(text: string): string {
    return text ? text[0].toUpperCase() + text.slice(1) : text;
  }
}
