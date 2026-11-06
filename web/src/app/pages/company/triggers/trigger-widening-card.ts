import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideShieldPlus } from '@ng-icons/lucide';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { Api } from '../../../api/generated/api';
import { acceptTriggerWidening } from '../../../api/generated/functions';
import type { AgentWidening, Trigger } from '../../../api/generated/models';
import { problemMessage } from '../../../api/problem';
import { CallerPrincipal } from '../../../core/caller-principal';
import { InstructionsDiff } from '../agents/instructions-diff';
import {
  changesOnlyContent,
  skillsSection,
  type WideningAgent,
  wideningSections,
} from '../agents/widening-card';

/**
 * A new version of a trigger's agent to accept (the package 4 contract, §3.3 and §5.11): what it
 * adds over the version the author accepted for the trigger, and "Accept version N"
 * (`acceptTriggerWidening`), naming the version shown. Only the author accepts (the security
 * review of 2026-10-27, H2: the server answers anyone else 403): orange, with the button, for the
 * author; neutral for an admin and the team, "Waiting for {author} to accept" in its place. A
 * version that changes only what the agent is told (its instructions or skills, D1) says so, as
 * the agent's own widening card does.
 */
@Component({
  selector: 'app-trigger-widening-card',
  imports: [NgIcon, HlmButton, HlmSpinner, InstructionsDiff],
  providers: [provideIcons({ lucideShieldPlus })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  template: `
    <section
      class="bg-card flex flex-col gap-3 rounded-xl border px-3.5 py-3 text-sm"
      [class.border-human]="canAccept()"
      data-testid="trigger-widening"
      aria-labelledby="trigger-widening-title"
    >
      <div class="flex items-start gap-2.5">
        <ng-icon
          name="lucideShieldPlus"
          class="mt-0.5 shrink-0"
          [class.text-human]="canAccept()"
          aria-hidden="true"
        />
        <div class="flex min-w-0 flex-col gap-0.5">
          <h2
            class="font-semibold text-pretty"
            [class.text-human]="canAccept()"
            id="trigger-widening-title"
          >
            {{ trigger().agentName ?? 'Its agent' }} has a new version
          </h2>
          <p class="text-muted-foreground text-pretty">
            @if (contentOnly()) {
              Version {{ trigger().currentVersion }} changes what it is told: its instructions or
              skills. This trigger’s runs keep those of version
              {{ trigger().acceptedVersion }} until
              {{ canAccept() ? 'you accept it' : 'its author accepts it' }}.
            } @else {
              Version {{ trigger().currentVersion }} may do more than version
              {{ trigger().acceptedVersion }}, which this trigger’s runs keep until
              {{ canAccept() ? 'you accept it' : 'its author accepts it' }}.
            }
          </p>
        </div>
      </div>
      @if (sections().length || instructions()) {
        <dl class="flex flex-col gap-2.5">
          @for (section of sections(); track section.key) {
            <div class="flex flex-col gap-1" [attr.data-section]="section.key">
              <dt class="text-muted-foreground text-xs font-semibold">{{ section.title }}</dt>
              @for (item of section.items; track $index) {
                <dd class="break-words" [class.font-mono]="item.mono" [class.text-xs]="item.mono">
                  {{ item.text }}
                </dd>
              }
            </div>
          }
          @if (instructions(); as instructions) {
            <div class="flex flex-col gap-1" data-section="instructions">
              <dt class="text-muted-foreground text-xs font-semibold">Instructions</dt>
              <dd>
                <app-instructions-diff
                  [accepted]="instructions.accepted"
                  [current]="instructions.current"
                />
              </dd>
            </div>
          }
        </dl>
      }
      @if (canAccept()) {
        <button
          hlmBtn
          size="sm"
          type="button"
          class="self-start"
          data-testid="accept-trigger-version"
          [disabled]="busy()"
          (click)="accept()"
        >
          @if (busy()) {
            <hlm-spinner aria-hidden="true" />
          }
          Accept version {{ trigger().currentVersion }}
        </button>
      } @else {
        <p class="text-muted-foreground text-xs" data-testid="widening-waits-for-author">
          Waiting for {{ trigger().authorName ?? 'its author' }} to accept
        </p>
      }
      @if (error(); as message) {
        <p class="text-destructive" role="alert">{{ message }}</p>
      }
    </section>
  `,
})
export class TriggerWideningCard {
  private readonly api = inject(Api);
  private readonly caller = inject(CallerPrincipal);

  readonly companyId = input.required<string>();
  readonly trigger = input.required<Trigger>();
  /** What the current version adds (`getTriggerSettings().widening`), when known. */
  readonly widening = input<AgentWidening | null>(null);
  /** The agent, to name its systems and tools; the reader may not see it. */
  readonly agent = input<WideningAgent | null>(null);
  readonly accepted = output<Trigger>();

  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  /** Only its author accepts, never an admin on their behalf (H2). */
  protected readonly canAccept = computed(() => {
    const me = this.caller.id();
    return me !== null && me === this.trigger().authorPrincipalId;
  });

  /** The version adds none of what it may reach, only its instructions or skills (D1). */
  protected readonly contentOnly = computed(() => {
    const w = this.widening();
    return !!w && changesOnlyContent(w);
  });

  /**
   * What widened, by kind; without the agent (the reader may not see it), only the skills, which
   * name themselves.
   */
  protected readonly sections = computed(() => {
    const widening = this.widening();
    const agent = this.agent();
    if (!widening) return [];
    if (agent) return wideningSections(widening, agent);
    const skills = skillsSection(widening);
    return skills ? [skills] : [];
  });
  /** The instructions of both versions when they differ (D1), as a diff. */
  protected readonly instructions = computed(() => this.widening()?.instructions ?? null);

  protected async accept(): Promise<void> {
    if (this.busy()) return;
    this.busy.set(true);
    this.error.set(null);
    try {
      const trigger = await this.api.invoke(acceptTriggerWidening, {
        companyId: this.companyId(),
        triggerId: this.trigger().id,
        body: { version: this.trigger().currentVersion },
      });
      this.accepted.emit(trigger);
    } catch (error) {
      this.error.set(problemMessage(error));
    } finally {
      this.busy.set(false);
    }
  }
}
