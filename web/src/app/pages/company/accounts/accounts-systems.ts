import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  resource,
  signal,
} from '@angular/core';
import { form, FormField, maxLength, required, submit, validate } from '@angular/forms/signals';
import { NgTemplateOutlet } from '@angular/common';
import { Router, RouterLink } from '@angular/router';
import { NgIcon, provideIcons } from '@ng-icons/core';
import {
  lucideBlocks,
  lucideCircleAlert,
  lucideCircleCheck,
  lucideClipboardPaste,
  lucideFolderGit2,
  lucideLogIn,
  lucideMessagesSquare,
  lucidePlug,
  lucidePlus,
  lucideSettings2,
  lucideTicket,
  lucideUnplug,
  lucideX,
} from '@ng-icons/lucide';
import { HlmAlertImports } from '@spartan-ng/helm/alert';
import { HlmAlertDialogImports } from '@spartan-ng/helm/alert-dialog';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmDialogImports } from '@spartan-ng/helm/dialog';
import { HlmFieldImports } from '@spartan-ng/helm/field';
import { HlmInput } from '@spartan-ng/helm/input';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { Api } from '../../../api/generated/api';
import {
  disconnectMyAccount,
  getSystemSettings,
  listMyAccounts,
  startMyAccountSignIn,
  storeMyAccountToken,
} from '../../../api/generated/functions';
import type { Account, ExternalSystem, MyAccount } from '../../../api/generated/models';
import { problemMessage } from '../../../api/problem';
import { BrowserNavigation } from '../../../core/browser-navigation';
import { CurrentCompany } from '../../../core/current-company';
import { focusFragmentWhenReady } from '../../../core/fragment-focus';
import { Notify } from '../../../core/notify';
import { managesCompany } from '../../../core/permissions';
import { ErrorState } from '../../../shared/error-state';
import { firstError } from '../../../shared/forms';
import { ACCOUNT_KIND_LABELS } from '../../../shared/labels';
import { ListSkeleton } from '../../../shared/list-skeleton';
import { signInReturn, signInReturnTo } from '../../../shared/sign-in-result';
import { Time } from '../../../shared/time';
import { accountProblemMessage, MAX_TOKEN_LENGTH, tokenHint } from '../../../shared/token-hints';
import { isChatKind, systemKind, systemKindIcon } from '../systems/system-kinds';
import { AddMcpServerDialog } from './add-mcp-server-dialog';
import { MyChatLinks } from './my-chat-links';
import { type AccountTone, myAccountView, type MyAccountView } from './my-account-view';

/** The chip of a row, by tone: orange only where the reader must act (DESIGN.md). */
const TONE_CHIPS: Record<AccountTone, string> = {
  ok: 'border-success/40 bg-success/10 text-success',
  you: 'border-human/40 bg-human-soft text-human',
  waiting: 'border-warning/40 bg-warning/10 text-warning',
  idle: 'bg-card text-muted-foreground',
  off: 'bg-muted text-muted-foreground',
};

interface Row {
  readonly item: MyAccount;
  readonly view: MyAccountView;
}

/**
 * Accounts › Systems (plan §5): per system the reader sees, how a conversation of theirs would act
 * there now (`MyAccount.binding`): their own account, a grant of their group, a choice among
 * grants, an account to connect or to sign in again, an admin's account to wait for, or a
 * switched-off personal server. They connect their own by signing in with the system's app
 * (returning here with `?account=`) or by pasting a token, disconnect it, and register their own MCP
 * server, which opens its system page.
 */
@Component({
  selector: 'app-accounts-systems',
  imports: [
    FormField,
    NgTemplateOutlet,
    RouterLink,
    NgIcon,
    HlmAlertImports,
    HlmAlertDialogImports,
    HlmButton,
    HlmDialogImports,
    HlmFieldImports,
    HlmInput,
    HlmSpinner,
    ErrorState,
    ListSkeleton,
    Time,
    AddMcpServerDialog,
    MyChatLinks,
  ],
  providers: [
    provideIcons({
      lucideBlocks,
      lucideCircleAlert,
      lucideCircleCheck,
      lucideClipboardPaste,
      lucideFolderGit2,
      lucideLogIn,
      lucideMessagesSquare,
      lucidePlug,
      lucidePlus,
      lucideSettings2,
      lucideTicket,
      lucideUnplug,
      lucideX,
    }),
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'flex flex-col gap-5' },
  templateUrl: './accounts-systems.html',
})
export class AccountsSystems {
  private readonly api = inject(Api);
  private readonly router = inject(Router);
  private readonly browser = inject(BrowserNavigation);
  private readonly notify = inject(Notify);
  protected readonly current = inject(CurrentCompany);

  protected readonly kindIcon = systemKindIcon;
  protected readonly firstError = firstError;
  protected readonly admin = computed(() => managesCompany(this.current.access()));

  protected readonly accounts = resource({
    params: () => this.current.id(),
    loader: ({ params: companyId }) => this.api.invoke(listMyAccounts, { companyId }),
  });
  protected readonly settings = resource({
    params: () => this.current.id(),
    loader: ({ params: companyId }) => this.api.invoke(getSystemSettings, { companyId }),
  });

  private readonly rows = computed<readonly Row[]>(() =>
    (this.accounts.hasValue() ? this.accounts.value().items : []).map((item) => ({
      item,
      view: myAccountView(item),
    })),
  );
  /**
   * The company's systems an account connects on: never a chat's (its own section, below) nor a
   * webhook's, which takes deliveries under its signing secret and holds no one's account.
   */
  protected readonly companyRows = computed(() =>
    this.rows().filter((r) => !r.item.system.personal && systemKind(r.item.system.kind).accounts),
  );
  /** The chat systems: a link, made from the chat, instead of an account to connect (package 5a). */
  protected readonly chatItems = computed(() =>
    this.rows()
      .filter((r) => isChatKind(r.item.system.kind))
      .map((r) => r.item),
  );
  protected readonly personalRows = computed(() =>
    this.rows().filter((r) => r.item.system.personal),
  );
  protected readonly firstLoad = computed(
    () => this.accounts.isLoading() && !this.accounts.hasValue(),
  );
  /** Whether the company lets members register their own MCP servers; the server decides again. */
  protected readonly personalOn = computed(() =>
    this.settings.hasValue() ? this.settings.value().personalMcpServers : true,
  );

  /** Back from a sign-in this page started: what happened, said once. */
  protected readonly back = signInReturn();
  protected readonly backText = computed(() => {
    const result = this.back.result();
    if (!result) return null;
    if (!result.connected) return { ok: false, text: result.message };
    const row = this.rows().find((r) => r.item.system.id === result.systemId);
    if (!row) return { ok: true, text: result.message };
    const login = row.item.account?.login;
    return {
      ok: true,
      text: login
        ? `${row.view.name} is connected. Agents act there as you: ${login}.`
        : `${row.view.name} is connected. Agents act there as you.`,
    };
  });

  protected toneChip(tone: AccountTone): string {
    return TONE_CHIPS[tone];
  }

  protected accountKind(kind: Account['kind']): string {
    return ACCOUNT_KIND_LABELS[kind];
  }

  constructor() {
    // A link to one system (`#system-…`, from a conversation or the inbox) lands on its row.
    focusFragmentWhenReady(() => this.accounts.hasValue());
  }

  // --- Sign in with the system's app -----------------------------------------------------------

  protected readonly signingIn = signal<string | null>(null);

  protected async signIn(item: MyAccount): Promise<void> {
    const companyId = this.current.id();
    if (!companyId || this.signingIn()) return;
    this.signingIn.set(item.system.id);
    try {
      const start = await this.api.invoke(startMyAccountSignIn, {
        companyId,
        systemId: item.system.id,
        body: { returnTo: signInReturnTo(this.router) },
      });
      this.browser.assign(start.authorizationUrl);
    } catch (error) {
      this.notify.error(
        `Couldn’t sign in to ${item.system.name}`,
        new Error(accountProblemMessage(error, item.system.name)),
      );
      this.signingIn.set(null);
    }
  }

  // --- Paste a token -----------------------------------------------------------------------------

  protected readonly tokenOpen = signal(false);
  /** The system in the dialog; kept after closing so that its text does not blank out. */
  protected readonly tokenFor = signal<MyAccount | null>(null);
  protected readonly hint = computed(() => tokenHint(this.tokenFor()?.system.kind));
  protected readonly tokenModel = signal({ token: '' });
  protected readonly tokenForm = form(this.tokenModel, (path) => {
    required(path.token, { message: 'Paste the token.' });
    validate(path.token, ({ value }) =>
      value() && !value().trim() ? { kind: 'blank', message: 'Paste the token.' } : undefined,
    );
    maxLength(path.token, MAX_TOKEN_LENGTH, {
      message: `The token is too long: at most ${MAX_TOKEN_LENGTH} characters.`,
    });
  });
  protected readonly tokenError = signal<string | null>(null);

  protected openToken(item: MyAccount): void {
    this.tokenFor.set(item);
    this.tokenModel.set({ token: '' });
    this.tokenForm().reset();
    this.tokenError.set(null);
    this.tokenOpen.set(true);
  }

  /** The token lives only in the dialog: it is dropped when the dialog closes. */
  protected closeToken(): void {
    this.tokenOpen.set(false);
    this.tokenModel.set({ token: '' });
  }

  protected onTokenSubmit(event: Event): void {
    event.preventDefault();
    const companyId = this.current.id();
    const item = this.tokenFor();
    if (!companyId || !item) return;
    this.tokenError.set(null);
    void submit(this.tokenForm, async () => {
      try {
        const account = await this.api.invoke(storeMyAccountToken, {
          companyId,
          systemId: item.system.id,
          body: { token: this.tokenModel().token.trim() },
        });
        this.closeToken();
        // One's own account that works binds first (CONVENTIONS §15.3); the list read again says
        // the rest, such as a grant one no longer acts through.
        this.replace({ ...item, account, binding: 'OWN' });
        this.accounts.reload();
        this.notify.success(
          `${item.system.name} is connected`,
          account.login ? `Agents act there as you: ${account.login}.` : undefined,
        );
        return undefined;
      } catch (error) {
        this.tokenError.set(accountProblemMessage(error, item.system.name));
        return undefined;
      }
    });
  }

  // --- Disconnect --------------------------------------------------------------------------------

  protected readonly disconnecting = signal<MyAccount | null>(null);
  protected readonly disconnectOpen = signal(false);
  protected readonly disconnectBusy = signal(false);
  protected readonly disconnectError = signal<string | null>(null);

  protected askDisconnect(item: MyAccount): void {
    this.disconnecting.set(item);
    this.disconnectError.set(null);
    this.disconnectOpen.set(true);
  }

  protected async confirmDisconnect(): Promise<void> {
    const companyId = this.current.id();
    const item = this.disconnecting();
    if (!companyId || !item || this.disconnectBusy()) return;
    this.disconnectBusy.set(true);
    this.disconnectError.set(null);
    try {
      await this.api.invoke(disconnectMyAccount, { companyId, systemId: item.system.id });
      this.disconnectOpen.set(false);
      // Without one's own, a conversation acts through the grant, chooses among several, or asks
      // to connect (CONVENTIONS §15.3); the list read again confirms it.
      const grants = item.grants.length;
      this.replace({
        ...item,
        account: null,
        binding: grants === 0 ? 'CONNECT' : grants === 1 ? 'GRANTED' : 'CHOOSE',
      });
      this.accounts.reload();
      this.notify.success(`${item.system.name} is disconnected`);
    } catch (error) {
      this.disconnectError.set(problemMessage(error));
    } finally {
      this.disconnectBusy.set(false);
    }
  }

  // --- Your own MCP server -------------------------------------------------------------------

  protected readonly addOpen = signal(false);

  protected async onServerAdded(system: ExternalSystem): Promise<void> {
    this.notify.success(
      `${system.name} is registered`,
      'Only you see it. Confirm what its tools do here, then connect your account on it under Accounts.',
    );
    await this.router.navigate(['/c', this.current.id(), 'systems', system.id]);
  }

  private replace(item: MyAccount): void {
    this.accounts.update((list) =>
      list
        ? {
            ...list,
            items: list.items.map((i) => (i.system.id === item.system.id ? item : i)),
          }
        : list,
    );
  }
}
