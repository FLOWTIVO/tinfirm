import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  output,
  resource,
  signal,
} from '@angular/core';
import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideCheck } from '@ng-icons/lucide';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { Api } from '../../../api/generated/api';
import { acceptTriggerWidening, getTrigger } from '../../../api/generated/functions';
import type { InboxItem, Trigger } from '../../../api/generated/models';
import { problemMessage } from '../../../api/problem';
import { CallerPrincipal } from '../../../core/caller-principal';
import { InboxNotice, NOTICE_DONE, NOTICE_LINK } from '../inbox/inbox-notice';

/**
 * An inbox item of kind `TRIGGER` (the package 4 contract, §5.13; plan §5), a notice of the inbox:
 * a trigger of the reader's that is paused or waits (with why), has a new version of its agent to
 * accept, could not write a result back, skipped firings, was changed by an admin, or (for admins)
 * lost its author or its poll, or has memory notes to review (ADR-0063); with "Open trigger", where
 * each is fixed, or "Review notes", which opens the trigger's memory. A new version to accept
 * reads the trigger for its versions, and offers "Accept version N" to its author alone (the
 * inbox's item names neither; an admin never accepts on the author's behalf, the security review of
 * 2026-10-27, H2), never for a trigger reassigned and not accepted yet, which its own page accepts
 * whole. Its label is orange only where the reader is the one to act.
 *
 * A notice that only informs is dismissed as any other (`InboxNotice`): `dismiss` passes its
 * Dismiss on to the page, and `dismissError` back.
 *
 * Input: `item` (with `trigger`), `companyId`, `dismissError`. Emits `changed` once a version was
 * accepted, `dismiss` when the reader dismissed it.
 */
@Component({
  selector: 'app-inbox-trigger-item',
  imports: [NgIcon, HlmSpinner, InboxNotice],
  providers: [provideIcons({ lucideCheck })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block', 'data-kind': 'TRIGGER' },
  template: `
    @if (item().trigger; as t) {
      <app-inbox-notice
        data-testid="inbox-trigger"
        [attr.data-reason]="t.reason"
        [item]="item()"
        [companyId]="companyId()"
        [body]="versions()"
        [dismissError]="dismissError()"
        (dismiss)="dismiss.emit()"
      >
        @if (widening(); as w) {
          @if (accepted()) {
            <span [class]="doneClass" role="status"
              ><ng-icon name="lucideCheck" size="12" aria-hidden="true" />Runs version
              {{ w.currentVersion }}</span
            >
          } @else if (mayAccept()) {
            <button
              type="button"
              data-testid="inbox-trigger-accept"
              [class]="linkClass"
              [disabled]="busy()"
              (click)="accept(w)"
            >
              @if (busy()) {
                <hlm-spinner class="size-3.5" aria-hidden="true" />
              }
              Accept version {{ w.currentVersion }}
            </button>
          }
        }
        @if (error(); as message) {
          <span class="text-destructive text-xs" role="alert">{{ message }}</span>
        }
      </app-inbox-notice>
    }
  `,
})
export class InboxTriggerItem {
  private readonly api = inject(Api);
  private readonly caller = inject(CallerPrincipal);

  readonly item = input.required<InboxItem>();
  readonly companyId = input.required<string>();
  /** Why the page could not dismiss it. */
  readonly dismissError = input<string | null>(null);
  /** A version was accepted: the inbox reads itself again. */
  readonly changed = output<void>();
  /** The reader dismissed it: the page hides it, with an undo. */
  readonly dismiss = output<void>();

  protected readonly linkClass = NOTICE_LINK;
  protected readonly doneClass = NOTICE_DONE;
  protected readonly busy = signal(false);
  protected readonly accepted = signal(false);
  protected readonly error = signal<string | null>(null);

  /** The trigger of a new version to accept: its versions, and whether the reader may accept. */
  private readonly trigger = resource({
    params: () => {
      const t = this.item().trigger;
      return t?.reason === 'WIDENING'
        ? { companyId: this.companyId(), triggerId: t.triggerId }
        : undefined;
    },
    loader: ({ params }) => this.api.invoke(getTrigger, params),
  });
  protected readonly widening = computed(() => {
    const t = this.trigger.hasValue() ? this.trigger.value() : undefined;
    return t && t.wideningPending ? t : null;
  });
  /** Only the author accepts, and a reassigned trigger only on its page, whole (H2). */
  protected readonly mayAccept = computed(() => {
    const t = this.widening();
    const me = this.caller.id();
    return !!t && me !== null && me === t.authorPrincipalId && t.pausedReason !== 'TO_ACCEPT';
  });
  protected readonly versions = computed(() => {
    const t = this.widening();
    return t
      ? `Runs version ${t.acceptedVersion} of ${t.agentName ?? 'its agent'}; version ${t.currentVersion} may do more.`
      : null;
  });

  protected async accept(trigger: Trigger): Promise<void> {
    if (this.busy()) return;
    this.busy.set(true);
    this.error.set(null);
    try {
      await this.api.invoke(acceptTriggerWidening, {
        companyId: this.companyId(),
        triggerId: trigger.id,
        body: { version: trigger.currentVersion },
      });
      this.accepted.set(true);
      this.changed.emit();
    } catch (error) {
      this.error.set(problemMessage(error));
    } finally {
      this.busy.set(false);
    }
  }
}
