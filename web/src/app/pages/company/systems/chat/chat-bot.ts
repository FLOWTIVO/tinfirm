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
import { form, FormField, maxLength, required, submit, validate } from '@angular/forms/signals';
import { RouterLink } from '@angular/router';
import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideBot, lucideCircleAlert, lucideKeyRound, lucideTrash2 } from '@ng-icons/lucide';
import { HlmAlertImports } from '@spartan-ng/helm/alert';
import { HlmAlertDialogImports } from '@spartan-ng/helm/alert-dialog';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmDialogImports } from '@spartan-ng/helm/dialog';
import { HlmFieldImports } from '@spartan-ng/helm/field';
import { HlmInput } from '@spartan-ng/helm/input';
import { HlmNativeSelectImports } from '@spartan-ng/helm/native-select';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { Api } from '../../../../api/generated/api';
import {
  listServiceIdentities,
  removeChatBot,
  setChatBot,
} from '../../../../api/generated/functions';
import type { ExternalSystem, ServiceIdentity } from '../../../../api/generated/models';
import { problemMessage, toProblem } from '../../../../api/problem';
import { Notify } from '../../../../core/notify';
import { firstError } from '../../../../shared/forms';
import { refusalReason } from '../../../../shared/token-hints';
import { CHAT_SYSTEM_MESSAGES, chatPlatform } from './chat-words';

/** The bot's token as each platform calls it, and where its admin finds it (package 5, §5.1). */
export function botTokenHint(kind: ExternalSystem['kind']): {
  label: string;
  placeholder: string;
  hint: string;
} {
  return kind === 'TEAMS'
    ? {
        label: 'App password',
        placeholder: 'A client secret of the app',
        hint: 'A client secret of the Azure Bot’s Microsoft App ID (Certificates & secrets). Tinfirm asks Microsoft for a token with it before it keeps it.',
      }
    : {
        label: 'Bot token',
        placeholder: 'xoxb-…',
        hint: 'The app’s Bot User OAuth Token (OAuth & Permissions, after installing it). Tinfirm checks it with Slack before it keeps it; its workspace becomes the system’s.',
      };
}

/** The bot's holder: the service account that borrows the bot, a credential of the system. */
export interface BotHolder {
  readonly id: string;
  readonly name: string;
}

/**
 * A chat system's bot (package 5, §3.2; CONVENTIONS §15.16; ADR-0072): a credential of the system
 * that a service account borrows, used only to receive and post in the chat, never lent to a group,
 * bound or used by a trigger. Set, replaced and removed only here (`PUT`, `DELETE
 * /systems/{id}/bot`); only the same service account replaces its token, another one waits until
 * the bot is removed. Among the system's credentials it is marked and offers nothing. Without a
 * bot, the chat's messages are verified and dropped unanswered.
 */
@Component({
  selector: 'app-chat-bot',
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
    HlmNativeSelectImports,
    HlmSpinner,
  ],
  providers: [provideIcons({ lucideBot, lucideCircleAlert, lucideKeyRound, lucideTrash2 })], // gitleaks:allow (icon names)
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  template: `
    @let s = system();
    <section
      class="bg-card overflow-hidden rounded-xl border"
      aria-labelledby="chat-bot-title"
      data-testid="chat-bot"
      [attr.data-bot-set]="botSet()"
    >
      <div class="flex flex-wrap items-start gap-3 px-4 py-4 sm:px-5 sm:pt-5">
        <div class="flex min-w-0 flex-1 basis-64 flex-col gap-0.5">
          <h2 id="chat-bot-title" class="text-base font-bold">The bot</h2>
          <p class="text-muted-foreground text-sm text-pretty">
            How Tinfirm receives and answers in {{ platform() }}: a credential of {{ s.name }} that
            a service account borrows, used for nothing else. Agents act as the person who writes,
            never as the bot.
          </p>
        </div>
        @if (canManage()) {
          @if (botSet()) {
            <span class="-me-2 flex flex-wrap gap-1">
              <button
                hlmBtn
                variant="ghost"
                size="sm"
                class="max-sm:h-11"
                type="button"
                data-testid="replace-bot"
                (click)="openSet()"
              >
                <ng-icon name="lucideKeyRound" aria-hidden="true" />
                Replace the {{ tokenWord() }}
              </button>
              <button
                hlmBtn
                variant="ghost"
                size="sm"
                class="max-sm:h-11"
                type="button"
                data-testid="remove-bot"
                (click)="openRemove()"
              >
                <ng-icon name="lucideTrash2" aria-hidden="true" />
                Remove
              </button>
            </span>
          } @else {
            <button
              hlmBtn
              size="sm"
              class="max-sm:h-11"
              type="button"
              data-testid="set-bot"
              [disabled]="s.paused"
              (click)="openSet()"
            >
              <ng-icon name="lucideBot" aria-hidden="true" />
              Set the bot
            </button>
          }
        }
      </div>

      @if (botSet()) {
        <dl data-testid="bot-details">
          <div
            class="grid gap-x-8 gap-y-1 border-t px-4 py-3.5 sm:grid-cols-[minmax(0,15rem)_minmax(0,1fr)] sm:px-5"
            data-testid="bot-holder"
          >
            <dt class="text-sm font-semibold">Borrowed by</dt>
            <dd class="text-sm break-words">
              @if (holder(); as h) {
                <a
                  class="text-primary font-semibold underline-offset-2 hover:underline"
                  [routerLink]="['/c', companyId(), 'service-identities', h.id]"
                  >{{ h.name }}</a
                >
              } @else {
                A service account
              }
            </dd>
          </div>
          @if (s.chat?.botCredentialName; as credential) {
            <div
              class="grid gap-x-8 gap-y-1 border-t px-4 py-3.5 sm:grid-cols-[minmax(0,15rem)_minmax(0,1fr)] sm:px-5"
              data-testid="bot-credential"
            >
              <dt class="text-sm font-semibold">Credential</dt>
              <dd class="text-sm break-words">{{ credential }}</dd>
            </div>
          }
          <div
            class="grid gap-x-8 gap-y-1 border-t px-4 py-3.5 sm:grid-cols-[minmax(0,15rem)_minmax(0,1fr)] sm:px-5"
            data-testid="bot-identity"
          >
            <dt class="text-sm font-semibold">
              {{ s.kind === 'TEAMS' ? 'Microsoft App ID' : 'Bot user' }}
            </dt>
            <dd class="font-mono text-[0.8125rem] break-all">
              {{ s.kind === 'TEAMS' ? s.chat?.appId : s.chat?.botUserId }}
            </dd>
          </div>
          @if (s.kind === 'SLACK') {
            <div
              class="grid gap-x-8 gap-y-1 border-t px-4 py-3.5 sm:grid-cols-[minmax(0,15rem)_minmax(0,1fr)] sm:px-5"
              data-testid="bot-workspace"
            >
              <dt class="text-sm font-semibold">Workspace</dt>
              <dd class="text-sm break-words">
                {{ s.chat?.teamName ?? '—' }}
                @if (s.chat?.teamId; as team) {
                  <code class="text-muted-foreground font-mono text-xs">({{ team }})</code>
                }
              </dd>
            </div>
          }
        </dl>
      } @else {
        <p class="border-t px-4 py-4 text-sm text-pretty sm:px-5" data-testid="bot-missing">
          No bot yet: what people write to Tinfirm in {{ platform() }} is checked and dropped,
          unanswered, until one is set.
          @if (canManage()) {
            Pick the service account that borrows it, such as svc-{{
              s.kind === 'TEAMS' ? 'teams' : 'slack'
            }}-bot, and paste the {{ tokenWord() }}.
          }
        </p>
      }
    </section>

    <hlm-dialog [state]="setOpen() ? 'open' : 'closed'" (closed)="closeSet()">
      <hlm-dialog-content
        *hlmDialogPortal="let ctx"
        class="max-h-[92svh] overflow-y-auto sm:max-w-md"
        data-testid="bot-dialog"
      >
        <hlm-dialog-header>
          <h2 hlmDialogTitle>
            {{ botSet() ? 'Replace the ' + tokenWord() + ' of the bot' : 'Set the bot' }}
          </h2>
          <p hlmDialogDescription>
            @if (botSet()) {
              The same service account keeps borrowing the bot; only its {{ tokenWord() }} changes.
              To move the bot to another one, remove it first.
            } @else {
              The bot is a credential of {{ s.name }} that the chosen service account borrows. It is
              never lent to a group, bound in a conversation or used by a trigger.
            }
          </p>
        </hlm-dialog-header>

        <form id="bot-form" class="flex flex-col gap-4" novalidate (submit)="onSubmit($event)">
          @if (formError(); as message) {
            <div hlmAlert variant="destructive" data-testid="bot-form-error">
              <ng-icon name="lucideCircleAlert" aria-hidden="true" />
              <p hlmAlertDescription class="break-words">{{ message }}</p>
            </div>
          }

          <div hlmField>
            <label hlmFieldLabel for="bot-principal">Service account</label>
            @if (botSet()) {
              <p class="text-sm font-semibold" data-testid="bot-principal-fixed">
                {{ holder()?.name ?? 'The bot’s service account' }}
              </p>
            } @else {
              <hlm-native-select
                class="w-full"
                selectId="bot-principal"
                [value]="model().principalId"
                (valueChange)="setPrincipal($event)"
              >
                <option value="" disabled>Choose a service account</option>
                @for (identity of activeIdentities(); track identity.id) {
                  <option [value]="identity.id">{{ identity.name }}</option>
                }
              </hlm-native-select>
              @if (identitiesRes.hasValue() && !activeIdentities().length) {
                <p hlmFieldDescription>
                  No active service account yet.
                  <a
                    class="text-primary font-semibold underline-offset-2 hover:underline"
                    [routerLink]="['/c', companyId(), 'service-identities']"
                    >Create one</a
                  >, such as svc-{{ s.kind === 'TEAMS' ? 'teams' : 'slack' }}-bot.
                </p>
              } @else {
                <p hlmFieldDescription>
                  One that borrows nothing else is best: it is only the bot.
                </p>
              }
            }
            @if (principalError(); as message) {
              <hlm-field-error forceShow>{{ message }}</hlm-field-error>
            }
          </div>

          <div hlmField>
            <label hlmFieldLabel for="bot-token">{{ hint().label }}</label>
            <input
              hlmInput
              id="bot-token"
              type="password"
              class="font-mono"
              autocomplete="off"
              spellcheck="false"
              [placeholder]="hint().placeholder"
              [formField]="botForm.token"
            />
            <p hlmFieldDescription>{{ hint().hint }} It is never shown again.</p>
            <hlm-field-error>{{ firstError(botForm.token) }}</hlm-field-error>
          </div>
        </form>

        <hlm-dialog-footer>
          <button hlmBtn variant="outline" type="button" hlmDialogClose>Cancel</button>
          <button
            hlmBtn
            type="submit"
            form="bot-form"
            data-testid="bot-save"
            [disabled]="botForm().submitting()"
          >
            @if (botForm().submitting()) {
              <hlm-spinner aria-hidden="true" />
            }
            Check and set
          </button>
        </hlm-dialog-footer>
      </hlm-dialog-content>
    </hlm-dialog>

    <hlm-alert-dialog [state]="removeOpen() ? 'open' : 'closed'" (closed)="removeOpen.set(false)">
      <hlm-alert-dialog-content *hlmAlertDialogPortal="let ctx">
        <hlm-alert-dialog-header>
          <h2 hlmAlertDialogTitle>Remove the bot of {{ s.name }}?</h2>
          <p hlmAlertDialogDescription>
            Its {{ tokenWord() }} is deleted. Until a bot is set again, what people write to Tinfirm
            in {{ platform() }} is checked and dropped, and never answered later. Open conversations
            stay, readable in the app.
          </p>
        </hlm-alert-dialog-header>
        @if (removeError(); as message) {
          <div hlmAlert variant="destructive">
            <ng-icon name="lucideCircleAlert" aria-hidden="true" />
            <p hlmAlertDescription>{{ message }}</p>
          </div>
        }
        <hlm-alert-dialog-footer>
          <button hlmAlertDialogCancel [disabled]="removeBusy()">Cancel</button>
          <button
            hlmAlertDialogAction
            variant="destructive"
            data-testid="confirm-remove-bot"
            [disabled]="removeBusy()"
            (click)="confirmRemove()"
          >
            @if (removeBusy()) {
              <hlm-spinner aria-hidden="true" />
            }
            Remove the bot
          </button>
        </hlm-alert-dialog-footer>
      </hlm-alert-dialog-content>
    </hlm-alert-dialog>
  `,
})
export class ChatBot {
  private readonly api = inject(Api);
  private readonly notify = inject(Notify);

  readonly companyId = input.required<string>();
  readonly system = input.required<ExternalSystem>();
  readonly canManage = input(false);
  /** The bot was set, replaced or removed: the page reads the system again. */
  readonly changed = output<void>();

  protected readonly firstError = firstError;
  protected readonly botSet = computed(() => !!this.system().chat?.botSet);
  protected readonly platform = computed(() => chatPlatform(this.system().kind));
  protected readonly hint = computed(() => botTokenHint(this.system().kind));
  protected readonly tokenWord = computed(() =>
    this.system().kind === 'TEAMS' ? 'app password' : 'bot token',
  );

  /** The company's service accounts to pick the bot's from, while it has none. */
  protected readonly identitiesRes = resource({
    params: () => (this.canManage() && !this.botSet() ? this.companyId() : undefined),
    loader: ({ params: companyId }) =>
      this.api.invoke(listServiceIdentities, { companyId, limit: 200 }),
  });
  protected readonly activeIdentities = computed<readonly ServiceIdentity[]>(() =>
    (this.identitiesRes.hasValue() ? this.identitiesRes.value().items : []).filter(
      (identity) => identity.status === 'ACTIVE',
    ),
  );

  /** Who borrows the bot: the service account the system names, for whoever manages it. */
  protected readonly holder = computed<BotHolder | null>(() => {
    const chat = this.system().chat;
    return chat?.botPrincipalId
      ? { id: chat.botPrincipalId, name: chat.botPrincipalName ?? 'A service account' }
      : null;
  });

  // --- Set or replace -------------------------------------------------------------------------

  protected readonly setOpen = signal(false);
  protected readonly model = signal({ principalId: '', token: '' });
  protected readonly formError = signal<string | null>(null);
  protected readonly principalError = signal<string | null>(null);
  protected readonly botForm = form(this.model, (path) => {
    required(path.token, { message: 'Paste the token.' });
    validate(path.token, ({ value }) =>
      value() && !value().trim() ? { kind: 'blank', message: 'Paste the token.' } : undefined,
    );
    maxLength(path.token, 4000, { message: 'At most 4000 characters.' });
  });

  /** Opens the dialog that sets the bot, or replaces its token; the page's health strip uses it. */
  openSet(): void {
    this.model.set({ principalId: this.holder()?.id ?? '', token: '' });
    this.botForm().reset();
    this.formError.set(null);
    this.principalError.set(null);
    this.setOpen.set(true);
  }

  /** The token lives only in the dialog: it is dropped when the dialog closes. */
  protected closeSet(): void {
    this.setOpen.set(false);
    this.model.update((m) => ({ ...m, token: '' }));
  }

  protected setPrincipal(principalId: string | null | undefined): void {
    this.principalError.set(null);
    this.model.update((m) => ({ ...m, principalId: principalId ?? '' }));
  }

  protected onSubmit(event: Event): void {
    event.preventDefault();
    const principalId = this.model().principalId;
    this.formError.set(null);
    if (!principalId) {
      this.principalError.set(
        this.botSet()
          ? 'Tinfirm could not tell which service account borrows the bot. Reload the page.'
          : 'Choose the service account that borrows the bot.',
      );
      return;
    }
    void submit(this.botForm, async () => {
      try {
        const bot = await this.api.invoke(setChatBot, {
          companyId: this.companyId(),
          systemId: this.system().id,
          body: { principalId, token: this.model().token.trim() },
        });
        this.closeSet();
        this.changed.emit();
        this.notify.success(
          `The bot of ${this.system().name} is set`,
          bot.teamName ? `It answers in ${bot.teamName}.` : undefined,
        );
        return undefined;
      } catch (error) {
        this.formError.set(botProblemMessage(error));
        return undefined;
      }
    });
  }

  // --- Remove ---------------------------------------------------------------------------------

  protected readonly removeOpen = signal(false);
  protected readonly removeBusy = signal(false);
  protected readonly removeError = signal<string | null>(null);

  protected openRemove(): void {
    this.removeError.set(null);
    this.removeOpen.set(true);
  }

  protected async confirmRemove(): Promise<void> {
    if (this.removeBusy()) return;
    const system = this.system();
    this.removeBusy.set(true);
    this.removeError.set(null);
    try {
      await this.api.invoke(removeChatBot, { companyId: this.companyId(), systemId: system.id });
      this.removeOpen.set(false);
      this.changed.emit();
      this.notify.success(`The bot of ${system.name} is removed`);
    } catch (error) {
      this.removeError.set(problemMessage(error, CHAT_SYSTEM_MESSAGES));
    } finally {
      this.removeBusy.set(false);
    }
  }
}

/** A refused bot in the words of this card: the platform's own reason where it gave one. */
export function botProblemMessage(error: unknown): string {
  const problem = toProblem(error);
  if (problem.code === 'ACCOUNT_REFUSED') {
    const reason = refusalReason(problem.detail);
    return reason
      ? `The platform refused the token: ${reason.replace(/\.?$/, '.')}`
      : 'The platform refused the token. Check that it is the installed app’s, and current.';
  }
  return problemMessage(error, CHAT_SYSTEM_MESSAGES);
}
