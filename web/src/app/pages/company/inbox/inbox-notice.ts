import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { NgIcon, provideIcons } from '@ng-icons/core';
import {
  lucideArchive,
  lucideBadgeCheck,
  lucideBell,
  lucideBellOff,
  lucideCheck,
  lucideLink2,
  lucideTrendingUp,
  lucideUserMinus,
  lucideUserRoundCog,
  lucideWallet,
  lucideX,
  lucideZap,
} from '@ng-icons/lucide';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { Api } from '../../../api/generated/api';
import { disconnectMyAccount } from '../../../api/generated/functions';
import type { InboxItem, InboxItemKind } from '../../../api/generated/models';
import { problemMessage } from '../../../api/problem';
import { Time } from '../../../shared/time';
import { type InboxReader, inboxRowView, unlinkLabel } from './inbox-words';

const ICONS: Partial<Record<InboxItemKind, string>> = {
  RETENTION_OPEN: 'lucideArchive',
  BUDGET: 'lucideWallet',
  COMPANY_SPEND: 'lucideTrendingUp',
  SPEND_SYNC: 'lucideTrendingUp',
  TRIGGER: 'lucideZap',
  DEPARTURE: 'lucideUserMinus',
  CHAT_LINKED: 'lucideLink2',
  CHAT_SUBSCRIPTION_SILENT: 'lucideBellOff',
  SEAT_FALLBACK: 'lucideUserRoundCog',
  LICENCE: 'lucideBadgeCheck',
  SCIM_LAST_OWNER: 'lucideUserRoundCog',
};

/** A notice's action, as a link in its line: 13px green, 44px tall on a phone. */
export const NOTICE_LINK =
  'text-primary focus-visible:ring-ring/50 inline-flex items-center gap-1 rounded-sm text-[0.8125rem] font-semibold whitespace-nowrap underline-offset-2 outline-none hover:underline focus-visible:ring-3 disabled:opacity-50 max-md:min-h-11';
/** What an action of a notice did, in its place. */
export const NOTICE_DONE =
  'text-success inline-flex items-center gap-1 text-xs font-semibold whitespace-nowrap max-md:min-h-11';

/**
 * A notice of the inbox (plan §5), quiet: one line of its kind, what it says and its action links
 * (Unlink a chat account, Open trigger, Your budget…), and how long ago. Its kind's label turns
 * orange only where the reader is the one to act. A trigger's notice projects its own actions
 * (`app-inbox-trigger-item`). A notice that only informs (the server's `dismissible`) ends with a
 * Dismiss button, 44px on a phone: it emits `dismiss`, and the page hides it for the reader (`PUT
 * /inbox/dismissals/{noticeKey}`) with an undo, until its condition changes. One that asks the
 * reader to act has none: it leaves once handled.
 */
@Component({
  selector: 'app-inbox-notice',
  imports: [RouterLink, NgIcon, HlmButton, HlmSpinner, Time],
  providers: [
    provideIcons({
      lucideArchive,
      lucideBadgeCheck,
      lucideBell,
      lucideBellOff,
      lucideCheck,
      lucideLink2,
      lucideTrendingUp,
      lucideUserMinus,
      lucideUserRoundCog,
      lucideWallet,
      lucideX,
      lucideZap,
    }),
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  template: `
    @let i = item();
    @let v = view();
    <div
      class="grid grid-cols-[1rem_minmax(0,1fr)] items-start gap-x-2.5 py-2 ps-4 pe-3 md:min-h-13 md:grid-cols-[8rem_minmax(0,1fr)_auto_auto_2rem] md:items-center md:gap-x-3.5 md:py-1.5 md:pe-2"
      data-testid="inbox-notice"
      [attr.data-kind]="i.kind"
      [attr.data-tone]="v.tone"
    >
      <span
        class="flex min-w-0 items-center gap-1.5 text-xs font-semibold max-md:mt-0.5"
        [class.text-human]="v.tone === 'human'"
        [class.text-muted-foreground]="v.tone !== 'human'"
      >
        <ng-icon class="shrink-0" size="14" aria-hidden="true" [name]="icon()" />
        <span class="truncate max-md:sr-only">{{ v.kind }}</span>
      </span>
      <p class="text-muted-foreground min-w-0 text-[0.8125rem] leading-normal break-words">
        <strong class="text-foreground font-semibold" data-testid="inbox-notice-title">{{
          v.title
        }}</strong>
        @if (body() ?? v.body; as text) {
          · <span data-testid="inbox-notice-text">{{ text }}</span>
        }
      </p>
      <div class="col-start-2 flex flex-wrap items-center gap-x-4 md:contents">
        <span class="flex flex-wrap items-center gap-x-3 empty:hidden">
          <ng-content />
          @if (v.action === 'unlink') {
            @if (unlinked()) {
              <span [class]="doneClass" role="status"
                ><ng-icon name="lucideCheck" size="12" aria-hidden="true" />Unlinked</span
              >
            } @else {
              <button
                type="button"
                data-testid="inbox-chat-unlink"
                [class]="linkClass"
                [attr.aria-label]="unlinkName()"
                [disabled]="busy()"
                (click)="unlink()"
              >
                @if (busy()) {
                  <hlm-spinner class="size-3.5" aria-hidden="true" />
                }
                Unlink
              </button>
            }
          }
          @if (v.link; as link) {
            <a
              data-testid="inbox-row-link"
              [class]="linkClass"
              [routerLink]="['/c', companyId()].concat(link.commands)"
              [queryParams]="link.queryParams"
              [fragment]="link.fragment"
              >{{ link.label }}</a
            >
          }
        </span>
        <span class="text-muted-foreground text-xs whitespace-nowrap md:min-w-16 md:text-end"
          ><app-time [value]="i.since"
        /></span>
        @if (dismissible()) {
          <button
            hlmBtn
            variant="ghost"
            size="icon-sm"
            type="button"
            class="text-muted-foreground ms-auto max-md:-me-1 max-md:size-11 md:ms-0"
            data-testid="inbox-notice-dismiss"
            title="Dismiss"
            [attr.aria-label]="dismissName()"
            (click)="dismiss.emit()"
          >
            <ng-icon name="lucideX" size="16" aria-hidden="true" />
          </button>
        }
      </div>
      @if (error() ?? dismissError(); as message) {
        <p
          class="text-destructive col-start-2 text-xs md:col-span-4"
          role="alert"
          data-testid="inbox-row-error"
        >
          {{ message }}
        </p>
      }
    </div>
  `,
})
export class InboxNotice {
  private readonly api = inject(Api);

  readonly item = input.required<InboxItem>();
  readonly companyId = input.required<string>();
  readonly reader = input<InboxReader>({ admin: false, owner: false });
  /** What it says, when its host knows more than the item (a trigger's versions). */
  readonly body = input<string | null>(null);
  /** Why the page could not dismiss it, shown in its line. */
  readonly dismissError = input<string | null>(null);
  /** It acted (a chat account unlinked): the page reads the inbox again. */
  readonly changed = output<void>();
  /** The reader dismissed it: the page hides it, with an undo. */
  readonly dismiss = output<void>();

  protected readonly linkClass = NOTICE_LINK;
  protected readonly doneClass = NOTICE_DONE;
  protected readonly view = computed(() => inboxRowView(this.item(), this.reader()));
  protected readonly icon = computed(() => ICONS[this.item().kind] ?? 'lucideBell');
  /** Whom Unlink unlinks, for a screen reader: the chat user by name and id, where known. */
  protected readonly unlinkName = computed(() => unlinkLabel(this.item()));
  /** Whether the reader may dismiss it: the server's word, and a key to dismiss it by. */
  protected readonly dismissible = computed(
    () => !!this.item().dismissible && !!this.item().noticeKey,
  );
  /** What Dismiss dismisses, for a screen reader. */
  protected readonly dismissName = computed(() => `Dismiss the notice: ${this.view().title}`);
  protected readonly busy = signal(false);
  protected readonly unlinked = signal(false);
  protected readonly error = signal<string | null>(null);

  /** Unlinks the chat account linked to the reader (`DELETE /me/accounts/{systemId}`). */
  protected async unlink(): Promise<void> {
    const systemId = this.item().chat?.unlinkSystemId;
    if (!systemId || this.busy()) return;
    this.busy.set(true);
    this.error.set(null);
    try {
      await this.api.invoke(disconnectMyAccount, { companyId: this.companyId(), systemId });
      this.unlinked.set(true);
      this.changed.emit();
    } catch (error) {
      this.error.set(problemMessage(error));
    } finally {
      this.busy.set(false);
    }
  }
}
