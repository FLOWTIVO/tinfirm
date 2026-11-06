import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  output,
  resource,
  signal,
  untracked,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { NgIcon, provideIcons } from '@ng-icons/core';
import {
  lucideBan,
  lucideBot,
  lucideCircleAlert,
  lucideCircleCheck,
  lucideCirclePlay,
  lucideClipboardPaste,
  lucideEllipsis,
  lucideKeyRound,
  lucideLogIn,
  lucidePencil,
  lucidePlus,
  lucideTrash2,
  lucideUsers,
  lucideX,
} from '@ng-icons/lucide';
import { HlmAlertImports } from '@spartan-ng/helm/alert';
import { HlmAlertDialogImports } from '@spartan-ng/helm/alert-dialog';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmDialogImports } from '@spartan-ng/helm/dialog';
import { HlmDropdownMenuImports } from '@spartan-ng/helm/dropdown-menu';
import { HlmFieldImports } from '@spartan-ng/helm/field';
import { HlmInput } from '@spartan-ng/helm/input';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { Api } from '../../../api/generated/api';
import {
  deleteGrant,
  lendSystemCredential,
  listSystemCredentials,
  removeSystemCredential,
  updateGrant,
  updateSystemCredential,
  withdrawSystemCredential,
} from '../../../api/generated/functions';
import type {
  AccountAccess,
  CredentialGrant,
  CredentialLending,
  ExternalSystem,
  MemberAccountCount,
  SystemCredential,
} from '../../../api/generated/models';
import { problemMessage, toProblem } from '../../../api/problem';
import { focusFragmentWhenReady } from '../../../core/fragment-focus';
import { Notify } from '../../../core/notify';
import { sameParams } from '../../../core/paged-resource';
import { ErrorState } from '../../../shared/error-state';
import { plural } from '../../../shared/labels';
import { Segmented } from '../../../shared/segmented';
import { signInReturn } from '../../../shared/sign-in-result';
import { Time } from '../../../shared/time';
import {
  ACCESS_LABELS,
  ACCESS_OPTIONS,
  CREDENTIAL_KIND_LABELS,
  CREDENTIAL_MESSAGES,
  type CredentialTone,
  credentialProblem,
  credentialState,
  memberAccountsLine,
  takesClient,
  takesSignIn,
  takesToken,
  triggersActing,
  triggersPausing,
} from './credential-labels';
import { MAX_CREDENTIAL_NAME, CredentialDialog } from './credential-dialog';
import { CredentialLendDialog, type Lent, type LendTo } from './credential-lend-dialog';
import { CredentialOpenTriggers } from './credential-open-triggers';
import { systemKind } from './system-kinds';

/** A control whose value a refused change sets back: the access pills of a grant or a lending. */
interface ValueHolder {
  readonly value: { set(value: string): void };
}

const CHIP = 'inline-flex h-6 items-center rounded-full border px-2.5 text-xs font-semibold';
const TONES: Record<CredentialTone, string> = {
  success: 'bg-card text-success border-success/40',
  human: 'bg-human-soft text-human border-transparent',
  destructive: 'bg-destructive/10 text-destructive border-transparent',
  neutral: 'bg-muted text-muted-foreground border-transparent',
};

/** "Analysts and Ops", "Analysts, Ops and Support". */
function andList(names: readonly string[]): string {
  return names.length < 2
    ? (names[0] ?? '')
    : `${names.slice(0, -1).join(', ')} and ${names[names.length - 1]}`;
}

/**
 * What removing a credential does, a line each (`removeSystemCredential`): its grants go first,
 * then its lendings to service accounts, whose triggers that act with it pause (each by how many),
 * then the credential with its secret.
 */
export function removalLines(
  credential: Pick<SystemCredential, 'grants' | 'serviceAccounts'>,
  systemName: string,
): string[] {
  const lines: string[] = [];
  const groups = credential.grants.map((g) => g.groupName);
  if (groups.length) {
    lines.push(
      `${andList(groups)} no longer ${groups.length === 1 ? 'acts' : 'act'} with it: ` +
        `${groups.length === 1 ? 'its grant goes' : 'their grants go'} first.`,
    );
  }
  const lendings = credential.serviceAccounts;
  if (lendings.length) {
    const borrowers = `${andList(lendings.map((l) => l.name))} no longer ${lendings.length === 1 ? 'borrows' : 'borrow'} it`;
    const acting = lendings.filter((l) => l.triggers > 0);
    const total = acting.reduce((sum, l) => sum + l.triggers, 0);
    if (total) {
      const whose = andList(acting.map((l) => `${plural(l.triggers, 'trigger')} of ${l.name}`));
      const one = total === 1;
      lines.push(
        `${borrowers}: ${whose} ${one ? 'acts' : 'act'} with it and ${one ? 'pauses' : 'pause'} ` +
          `until ${one ? 'its author names' : 'their authors name'} another.`,
      );
    } else {
      lines.push(`${borrowers}; no trigger acts with it.`);
    }
  }
  lines.push(
    `Its token is deleted from the vault, and its sign-in revoked at ${systemName} where it ` +
      'offers that. Conversations that act with it stop at their next use of it.',
  );
  return lines;
}

/**
 * What withdrawing a credential from a service account does: it borrows none on the system then,
 * and its triggers that act with it pause, by how many; nothing pauses when none does.
 */
export function withdrawalText(
  lending: Pick<CredentialLending, 'name' | 'triggers'>,
  systemName: string,
): string {
  const pausing = triggersPausing(lending);
  return (
    `${lending.name} borrows no credential on ${systemName} then. ` +
    (pausing ? `${pausing}.` : `No trigger of ${lending.name} acts with it.`)
  );
}

/**
 * A company system's credentials, on its page (ADR-0072, decision 11): the company's tokens and
 * sign-ins on the system, each named, with its state, who it is there, who connected it, the
 * groups it is lent to (`READ` or `WRITE`, as pills), the service accounts that borrow it and the
 * triggers open to anyone that act with it through them (`openTriggers`, D2).
 * OWNER and ADMIN add one (a token, or a sign-in with the system's app that comes back here), store
 * a new token or sign it in again, rename, disable, enable and remove it, and lend it to a group or
 * a service account, all here. A chat system's bot is among them, marked, and managed under Bot
 * only. It tells the page the credentials each time they are read (`credentials`), for the tools'
 * and the test's choice of credential and for what a removal of the system says.
 */
@Component({
  selector: 'app-system-credentials',
  imports: [
    RouterLink,
    NgIcon,
    HlmAlertImports,
    HlmAlertDialogImports,
    HlmButton,
    HlmDialogImports,
    HlmDropdownMenuImports,
    HlmFieldImports,
    HlmInput,
    HlmSpinner,
    CredentialDialog,
    CredentialLendDialog,
    CredentialOpenTriggers,
    ErrorState,
    Segmented,
    Time,
  ],
  providers: [
    provideIcons({
      lucideBan,
      lucideBot,
      lucideCircleAlert,
      lucideCircleCheck,
      lucideCirclePlay,
      lucideClipboardPaste,
      lucideEllipsis,
      lucideKeyRound,
      lucideLogIn,
      lucidePencil,
      lucidePlus,
      lucideTrash2,
      lucideUsers,
      lucideX,
    }),
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block min-w-0' },
  template: `
    @let s = system();
    <section
      class="bg-card overflow-hidden rounded-xl border"
      aria-labelledby="credentials-title"
      data-testid="system-credentials"
    >
      <div class="flex flex-wrap items-start gap-3 px-4 py-4 sm:px-5 sm:pt-5">
        <div class="flex min-w-0 flex-1 basis-72 flex-col gap-0.5">
          <h2 id="credentials-title" class="flex items-center gap-2 text-base font-bold">
            Credentials
            @if (items().length) {
              <span class="text-muted-foreground font-mono text-xs font-medium">{{
                items().length
              }}</span>
            }
          </h2>
          <p class="text-muted-foreground text-sm text-pretty">
            The company’s tokens and sign-ins on {{ s.name }}. Lend one to groups, whose members act
            with it in their own conversations when they hold no account of their own here, and to
            service accounts, for their triggers. Agents never see a token.
          </p>
        </div>
        @if (canAdd()) {
          <button
            hlmBtn
            size="sm"
            variant="outline"
            class="max-sm:h-11"
            type="button"
            data-testid="add-credential"
            [disabled]="!!s.paused"
            (click)="openAdd()"
          >
            <ng-icon name="lucidePlus" aria-hidden="true" />
            Add credential
          </button>
        }
      </div>

      @if (signIn.result(); as result) {
        <div class="px-4 pb-3 sm:px-5">
          <div
            hlmAlert
            [variant]="result.connected ? 'default' : 'destructive'"
            role="status"
            data-testid="credential-sign-in-result"
          >
            <ng-icon
              [name]="result.connected ? 'lucideCircleCheck' : 'lucideCircleAlert'"
              [class.text-success]="result.connected"
              aria-hidden="true"
            />
            <p hlmAlertDescription class="text-foreground pe-6">
              {{
                result.connected
                  ? 'The credential is connected. Lend it to a group or a service account.'
                  : result.message
              }}
            </p>
            <button
              hlmBtn
              variant="ghost"
              size="icon-xs"
              type="button"
              class="absolute end-2 top-2"
              aria-label="Dismiss"
              (click)="signIn.dismiss()"
            >
              <ng-icon name="lucideX" aria-hidden="true" />
            </button>
          </div>
        </div>
      }

      @if (credentialsRes.error() && !credentialsRes.hasValue()) {
        <div class="border-t px-4 py-4 sm:px-5">
          <app-error-state
            [error]="credentialsRes.error()"
            heading="Couldn't load the credentials"
            [messages]="messages"
            (retry)="credentialsRes.reload()"
          />
        </div>
      } @else if (!credentialsRes.hasValue()) {
        <p class="text-muted-foreground border-t px-4 py-4 text-sm sm:px-5" role="status">
          Loading the credentials…
        </p>
      } @else if (items().length === 0) {
        <p
          class="text-muted-foreground mx-4 mb-4 rounded-lg border border-dashed px-3 py-4 text-center text-sm text-pretty sm:mx-5 sm:mb-5"
          data-testid="credentials-empty"
        >
          @if (!s.accountsRequired) {
            {{ s.name }} needs no account: runs call it without one. Add a credential only if it
            asks for one.
          } @else if (canAdd()) {
            No credential yet. Add one, then lend it to the groups and service accounts that act on
            {{ s.name }}.
          } @else {
            No credential yet.
          }
        </p>
      } @else {
        <ul class="divide-y border-t" aria-labelledby="credentials-title">
          @for (c of items(); track c.id) {
            @let state = stateOf(c);
            <li
              class="focus-visible:ring-ring/50 flex flex-col gap-3 px-4 py-4 outline-none focus-visible:ring-3 focus-visible:ring-inset sm:px-5"
              tabindex="-1"
              data-testid="credential"
              [id]="'credential-' + c.id"
              [attr.data-credential]="c.id"
              [attr.data-status]="c.status"
              [attr.data-enabled]="c.enabled"
            >
              <div class="flex flex-wrap items-start gap-x-3.5 gap-y-2">
                <span
                  class="bg-muted text-muted-foreground flex size-10 shrink-0 items-center justify-center rounded-[10px] border"
                  aria-hidden="true"
                >
                  <ng-icon [name]="c.bot ? 'lucideBot' : 'lucideKeyRound'" size="18" />
                </span>
                <div class="flex min-w-0 flex-1 basis-56 flex-col gap-1">
                  <span class="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1">
                    <span class="font-semibold break-words" data-testid="credential-name">{{
                      c.name
                    }}</span>
                    @if (c.bot) {
                      <span
                        [class]="
                          chip + ' bg-secondary text-secondary-foreground border-transparent'
                        "
                        data-testid="credential-bot"
                        >Chat bot</span
                      >
                    }
                    <span
                      [class]="chip + ' ' + tones[state.tone]"
                      data-testid="credential-state"
                      [attr.data-tone]="state.tone"
                      >{{ state.label }}</span
                    >
                    <span class="text-muted-foreground text-xs">{{ kindLabel(c) }}</span>
                  </span>
                  <span class="text-muted-foreground text-[0.8125rem] text-pretty">
                    @if (c.login) {
                      As
                      <span
                        class="text-foreground font-mono text-xs break-all"
                        data-testid="credential-login"
                        >{{ c.login }}</span
                      >
                      ·
                    }
                    Connected <app-time [value]="c.connectedAt" />{{
                      c.connectedByName ? ' by ' + c.connectedByName : ''
                    }}
                    @if (c.lastUsedAt; as used) {
                      · last used <app-time [value]="used" />
                    }
                  </span>
                  @if (c.status !== 'OK' && c.lastError) {
                    <span class="text-muted-foreground text-xs" data-testid="credential-error">{{
                      c.lastError
                    }}</span>
                  }
                </div>
                @if (!c.bot) {
                  <div class="flex shrink-0 items-center gap-1">
                    @if (c.enabled && c.status !== 'OK' && canReconnect()) {
                      <button
                        hlmBtn
                        variant="outline"
                        size="sm"
                        type="button"
                        class="border-human/40 text-human hover:text-human max-sm:h-11"
                        data-testid="credential-fix"
                        [disabled]="!!s.paused"
                        (click)="openReconnect(c)"
                      >
                        <ng-icon
                          [name]="reconnectsBySignIn(c) ? 'lucideLogIn' : 'lucideClipboardPaste'"
                          aria-hidden="true"
                        />
                        {{ reconnectLabel(c) }}
                      </button>
                    }
                    <button
                      hlmBtn
                      variant="ghost"
                      size="icon"
                      type="button"
                      class="max-sm:size-11"
                      data-testid="credential-menu"
                      [attr.aria-label]="'Actions for ' + c.name"
                      [hlmDropdownMenuTrigger]="menu"
                      [hlmDropdownMenuTriggerData]="{ $implicit: c }"
                      align="end"
                    >
                      <ng-icon name="lucideEllipsis" aria-hidden="true" />
                    </button>
                  </div>
                }
              </div>

              @if (c.bot) {
                <div
                  class="bg-muted/50 flex flex-col gap-1 rounded-[10px] px-3.5 py-2.5 text-[0.8125rem] text-pretty sm:ms-[3.375rem]"
                  data-testid="credential-bot-note"
                >
                  <span>
                    @if (c.serviceAccounts.length) {
                      Borrowed by
                      @for (lending of c.serviceAccounts; track lending.serviceIdentityId) {
                        <a
                          class="text-primary font-semibold underline-offset-2 hover:underline"
                          [routerLink]="[
                            '/c',
                            companyId(),
                            'service-identities',
                            lending.serviceIdentityId,
                          ]"
                          >{{ lending.name }}</a
                        >{{ $last ? '.' : ', ' }}
                      }
                    }
                    It only receives and posts in the chat, and is lent to no one else.
                  </span>
                  <a
                    class="text-primary w-fit font-semibold underline-offset-2 hover:underline"
                    data-testid="credential-bot-link"
                    [routerLink]="[]"
                    [queryParams]="{ tab: 'bot' }"
                    queryParamsHandling="merge"
                    >Set, replace or remove it under Bot</a
                  >
                </div>
              } @else {
                <div
                  class="bg-muted/50 flex flex-col gap-2 rounded-[10px] px-3.5 py-2.5 sm:ms-[3.375rem]"
                  data-testid="credential-groups"
                >
                  <div class="flex flex-wrap items-center justify-between gap-2">
                    <span class="text-muted-foreground text-xs font-semibold">{{
                      groupsLine(c)
                    }}</span>
                    <button
                      hlmBtn
                      variant="ghost"
                      size="sm"
                      type="button"
                      class="text-primary hover:text-primary -me-2 h-7 max-sm:h-9"
                      data-testid="lend-group"
                      (click)="openLend(c, 'group')"
                    >
                      <ng-icon name="lucidePlus" aria-hidden="true" />
                      Lend to a group
                    </button>
                  </div>
                  @for (grant of c.grants; track grant.id) {
                    <div
                      class="flex flex-wrap items-center gap-x-3 gap-y-2"
                      data-testid="credential-grant"
                      [attr.data-group]="grant.groupName"
                    >
                      <span class="flex min-w-0 flex-1 basis-40 items-center gap-2.5">
                        <span
                          class="bg-secondary text-muted-foreground flex size-7 shrink-0 items-center justify-center rounded-lg"
                          aria-hidden="true"
                        >
                          <ng-icon name="lucideUsers" size="15" />
                        </span>
                        <span class="flex min-w-0 flex-col">
                          <span class="min-w-0 truncate text-sm font-semibold">{{
                            grant.groupName
                          }}</span>
                          <span class="text-muted-foreground text-xs" data-testid="grant-since"
                            >Lent since <app-time [value]="grant.createdAt" mode="day"
                          /></span>
                        </span>
                      </span>
                      <app-segmented
                        #grantPills
                        class="order-last sm:order-none"
                        [inputId]="'grant-access-' + grant.id"
                        [label]="'What ' + grant.groupName + ' may do with ' + c.name"
                        [options]="accessOptions"
                        [value]="grant.access"
                        [disabled]="busy() === grant.id"
                        (valueChange)="changeGrant(c, grant, $event, grantPills)"
                      />
                      <button
                        hlmBtn
                        variant="ghost"
                        size="icon-sm"
                        type="button"
                        class="text-muted-foreground max-sm:size-9"
                        [attr.aria-label]="'Stop lending ' + c.name + ' to ' + grant.groupName"
                        [title]="'Stop lending ' + c.name + ' to ' + grant.groupName"
                        [disabled]="busy() === grant.id"
                        (click)="removeGrant(c, grant)"
                      >
                        <ng-icon name="lucideX" aria-hidden="true" />
                      </button>
                    </div>
                  }
                </div>

                <div
                  class="bg-muted/50 flex flex-col gap-2 rounded-[10px] px-3.5 py-2.5 sm:ms-[3.375rem]"
                  data-testid="credential-service-accounts"
                >
                  <div class="flex flex-wrap items-center justify-between gap-2">
                    <span class="text-muted-foreground text-xs font-semibold">{{
                      lendingsLine(c)
                    }}</span>
                    <button
                      hlmBtn
                      variant="ghost"
                      size="sm"
                      type="button"
                      class="text-primary hover:text-primary -me-2 h-7 max-sm:h-9"
                      data-testid="lend-service-account"
                      (click)="openLend(c, 'service-account')"
                    >
                      <ng-icon name="lucidePlus" aria-hidden="true" />
                      Lend to a service account
                    </button>
                  </div>
                  @for (lending of c.serviceAccounts; track lending.serviceIdentityId) {
                    @let key = c.id + ':' + lending.serviceIdentityId;
                    <div
                      class="flex flex-wrap items-center gap-x-3 gap-y-2"
                      data-testid="credential-lending"
                      [attr.data-service-account]="lending.name"
                    >
                      <span class="flex min-w-0 flex-1 basis-40 items-center gap-2.5">
                        <span
                          class="bg-secondary text-muted-foreground flex size-7 shrink-0 items-center justify-center rounded-lg"
                          aria-hidden="true"
                        >
                          <ng-icon name="lucideBot" size="15" />
                        </span>
                        <span class="flex min-w-0 flex-col">
                          <span class="flex min-w-0 items-center gap-2">
                            <a
                              class="hover:decoration-primary min-w-0 truncate text-sm font-semibold underline decoration-transparent underline-offset-2"
                              [routerLink]="[
                                '/c',
                                companyId(),
                                'service-identities',
                                lending.serviceIdentityId,
                              ]"
                              >{{ lending.name }}</a
                            >
                            @if (lending.status === 'SUSPENDED') {
                              <span [class]="chip + ' ' + tones.neutral">Suspended</span>
                            }
                          </span>
                          <span class="text-muted-foreground text-xs" data-testid="lending-since"
                            >Lent since <app-time [value]="lending.createdAt" mode="day" /> ·
                            {{ triggersActing(lending.triggers) }}</span
                          >
                        </span>
                      </span>
                      <app-segmented
                        #lendingPills
                        class="order-last sm:order-none"
                        [inputId]="'lending-access-' + key"
                        [label]="'What ' + lending.name + ' may do with ' + c.name"
                        [options]="accessOptions"
                        [value]="lending.access"
                        [disabled]="busy() === key"
                        (valueChange)="changeLending(c, lending, $event, lendingPills)"
                      />
                      <button
                        hlmBtn
                        variant="ghost"
                        size="icon-sm"
                        type="button"
                        class="text-muted-foreground max-sm:size-9"
                        [attr.aria-label]="'Withdraw ' + c.name + ' from ' + lending.name"
                        [title]="'Withdraw ' + c.name + ' from ' + lending.name"
                        [disabled]="busy() === key"
                        (click)="askWithdraw(c, lending)"
                      >
                        <ng-icon name="lucideX" aria-hidden="true" />
                      </button>
                    </div>
                  }
                  <app-credential-open-triggers
                    [companyId]="companyId()"
                    [triggers]="c.openTriggers ?? []"
                  />
                </div>
              }
            </li>
          }
        </ul>
      }

      @if (members() && memberAccounts(); as count) {
        <p
          class="text-muted-foreground border-t px-4 py-3.5 text-[0.8125rem] text-pretty sm:px-5"
          data-testid="credentials-members"
        >
          <span class="text-foreground" data-testid="credentials-member-count">{{
            membersLine(count)
          }}</span>
          A member connects one under Accounts, and agents use it only in that member’s
          conversations, before any credential lent to their groups.
          @if (count.ok + count.notOk > 0) {
            <a
              class="text-primary font-semibold underline-offset-2 hover:underline"
              [routerLink]="['/c', companyId(), 'members']"
              >Check each member’s accounts on Members</a
            >
          }
        </p>
      }
    </section>

    <ng-template #menu let-c>
      <hlm-dropdown-menu class="w-72">
        @if (canReconnect()) {
          <button
            hlmDropdownMenuItem
            type="button"
            class="items-start gap-2.5 px-2.5 py-2 text-start"
            data-testid="credential-reconnect"
            [disabled]="!!system().paused"
            (triggered)="openReconnect(c)"
          >
            <ng-icon
              [name]="reconnectsBySignIn(c) ? 'lucideLogIn' : 'lucideClipboardPaste'"
              class="mt-0.5"
              aria-hidden="true"
            />
            <span class="flex min-w-0 flex-col">
              <span class="font-semibold">{{ reconnectLabel(c) }}…</span>
              <span class="text-muted-foreground text-xs"
                >It keeps its name and what it is lent to</span
              >
            </span>
          </button>
        }
        <button
          hlmDropdownMenuItem
          type="button"
          class="items-start gap-2.5 px-2.5 py-2 text-start"
          data-testid="credential-rename"
          (triggered)="openRename(c)"
        >
          <ng-icon name="lucidePencil" class="mt-0.5" aria-hidden="true" />
          <span class="font-semibold">Rename…</span>
        </button>
        <button
          hlmDropdownMenuItem
          type="button"
          class="items-start gap-2.5 px-2.5 py-2 text-start"
          data-testid="credential-toggle"
          (triggered)="setEnabled(c, !c.enabled)"
        >
          <ng-icon
            [name]="c.enabled ? 'lucideBan' : 'lucideCirclePlay'"
            class="mt-0.5"
            aria-hidden="true"
          />
          <span class="flex min-w-0 flex-col">
            <span class="font-semibold">{{ c.enabled ? 'Disable' : 'Enable' }}</span>
            <span class="text-muted-foreground text-xs">{{
              c.enabled
                ? 'Nothing acts with it until you enable it again'
                : 'Groups and service accounts act with it again'
            }}</span>
          </span>
        </button>
        <hlm-dropdown-menu-separator />
        <button
          hlmDropdownMenuItem
          variant="destructive"
          type="button"
          class="items-start gap-2.5 px-2.5 py-2 text-start"
          data-testid="credential-remove"
          (triggered)="askRemove(c)"
        >
          <ng-icon name="lucideTrash2" class="mt-0.5" aria-hidden="true" />
          <span class="flex min-w-0 flex-col">
            <span class="font-semibold">Remove…</span>
            <span class="text-muted-foreground text-xs">With its grants and lendings</span>
          </span>
        </button>
      </hlm-dropdown-menu>
    </ng-template>

    <app-credential-dialog
      [(open)]="dialogOpen"
      [companyId]="companyId()"
      [system]="system()"
      [credential]="dialogCredential()"
      (saved)="onSaved($event)"
    />

    <app-credential-lend-dialog
      [(open)]="lendOpen"
      [companyId]="companyId()"
      [system]="system()"
      [credential]="lendCredential()"
      [to]="lendTo()"
      [credentials]="items()"
      (lent)="onLent($event)"
    />

    <hlm-dialog [state]="renameOpen() ? 'open' : 'closed'" (closed)="renameOpen.set(false)">
      <hlm-dialog-content
        *hlmDialogPortal="let ctx"
        class="sm:max-w-md"
        data-testid="rename-credential-dialog"
      >
        <hlm-dialog-header>
          <h2 hlmDialogTitle>Rename {{ renaming()?.name }}</h2>
          <p hlmDialogDescription>
            Its grants, lendings and the triggers that act with it stay as they are.
          </p>
        </hlm-dialog-header>
        <form id="rename-credential-form" novalidate (submit)="rename($event)">
          <div hlmField>
            <label hlmFieldLabel for="rename-credential-name">Name</label>
            <input
              hlmInput
              id="rename-credential-name"
              autocomplete="off"
              [attr.maxlength]="maxName"
              [value]="newName()"
              (input)="newName.set($any($event.target).value)"
            />
            @if (renameError(); as message) {
              <hlm-field-error forceShow data-testid="rename-credential-error">{{
                message
              }}</hlm-field-error>
            }
          </div>
        </form>
        <hlm-dialog-footer>
          <button hlmBtn variant="outline" type="button" hlmDialogClose>Cancel</button>
          <button
            hlmBtn
            type="submit"
            form="rename-credential-form"
            data-testid="rename-credential-save"
            [disabled]="renameBusy()"
          >
            @if (renameBusy()) {
              <hlm-spinner aria-hidden="true" />
            }
            Rename
          </button>
        </hlm-dialog-footer>
      </hlm-dialog-content>
    </hlm-dialog>

    <hlm-alert-dialog [state]="removeOpen() ? 'open' : 'closed'" (closed)="removeOpen.set(false)">
      <hlm-alert-dialog-content
        *hlmAlertDialogPortal="let ctx"
        data-testid="remove-credential-dialog"
      >
        <hlm-alert-dialog-header>
          <h2 hlmAlertDialogTitle>Remove {{ removing()?.name }}?</h2>
          <p hlmAlertDialogDescription>This cannot be undone.</p>
        </hlm-alert-dialog-header>
        <ul
          class="flex list-disc flex-col gap-1.5 rounded-lg border py-3 ps-8 pe-3.5 text-sm text-pretty"
          data-testid="remove-credential-effects"
        >
          @for (line of removeLines(); track $index) {
            <li>{{ line }}</li>
          }
        </ul>
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
            data-testid="confirm-remove-credential"
            [disabled]="removeBusy()"
            (click)="confirmRemove()"
          >
            @if (removeBusy()) {
              <hlm-spinner aria-hidden="true" />
            }
            Remove {{ removing()?.name }}
          </button>
        </hlm-alert-dialog-footer>
      </hlm-alert-dialog-content>
    </hlm-alert-dialog>

    <hlm-alert-dialog
      [state]="withdrawOpen() ? 'open' : 'closed'"
      (closed)="withdrawOpen.set(false)"
    >
      <hlm-alert-dialog-content *hlmAlertDialogPortal="let ctx" data-testid="withdraw-dialog">
        <hlm-alert-dialog-header>
          <h2 hlmAlertDialogTitle>
            Withdraw {{ withdrawing()?.credential?.name }} from {{ withdrawing()?.lending?.name }}?
          </h2>
          <p hlmAlertDialogDescription data-testid="withdraw-effects">{{ withdrawText() }}</p>
        </hlm-alert-dialog-header>
        @if (withdrawError(); as message) {
          <div hlmAlert variant="destructive">
            <ng-icon name="lucideCircleAlert" aria-hidden="true" />
            <p hlmAlertDescription>{{ message }}</p>
          </div>
        }
        <hlm-alert-dialog-footer>
          <button hlmAlertDialogCancel [disabled]="withdrawBusy()">Cancel</button>
          <button
            hlmAlertDialogAction
            variant="destructive"
            data-testid="confirm-withdraw"
            [disabled]="withdrawBusy()"
            (click)="confirmWithdraw()"
          >
            @if (withdrawBusy()) {
              <hlm-spinner aria-hidden="true" />
            }
            Withdraw
          </button>
        </hlm-alert-dialog-footer>
      </hlm-alert-dialog-content>
    </hlm-alert-dialog>
  `,
})
export class SystemCredentials {
  private readonly api = inject(Api);
  private readonly notify = inject(Notify);

  readonly companyId = input.required<string>();
  readonly system = input.required<ExternalSystem>();
  /** The credentials, each time they are read. */
  readonly credentials = output<readonly SystemCredential[]>();

  protected readonly chip = CHIP;
  protected readonly tones = TONES;
  protected readonly accessOptions = ACCESS_OPTIONS;
  protected readonly messages = CREDENTIAL_MESSAGES;
  protected readonly maxName = MAX_CREDENTIAL_NAME;
  /**
   * What a sign-in for a credential, started here, came back with: the page is loaded afresh, so
   * the credentials it reads already hold the one connected.
   */
  protected readonly signIn = signInReturn();

  private readonly key = computed(
    () => ({ companyId: this.companyId(), systemId: this.system().id }),
    { equal: sameParams },
  );
  protected readonly credentialsRes = resource({
    params: () => this.key(),
    loader: ({ params }) => this.api.invoke(listSystemCredentials, params),
  });
  protected readonly items = computed<readonly SystemCredential[]>(() =>
    this.credentialsRes.hasValue() ? this.credentialsRes.value().items : [],
  );
  /** How many members hold their own account here (ADR-0072, decision 11), once read. */
  protected readonly memberAccounts = computed<MemberAccountCount | null>(() =>
    this.credentialsRes.hasValue() ? this.credentialsRes.value().memberAccounts : null,
  );
  protected readonly triggersActing = triggersActing;

  /** A credential is added by a token or a sign-in, where the system takes either. */
  protected readonly canAdd = computed(
    () => takesToken(this.system()) || takesSignIn(this.system()),
  );
  protected readonly canReconnect = this.canAdd;
  /** Members' own accounts: on a kind members connect one on, not a chat. */
  protected readonly members = computed(() => systemKind(this.system().kind).accounts);

  /** The busy grant, lending (`credentialId:serviceIdentityId`) or credential. */
  protected readonly busy = signal<string | null>(null);

  constructor() {
    effect(() => {
      if (!this.credentialsRes.hasValue()) return;
      const items = this.items();
      untracked(() => this.credentials.emit(items));
    });
    // The inbox, a departure and a service account link to one credential (`#credential-…`).
    focusFragmentWhenReady(() => this.credentialsRes.hasValue());
  }

  /** Reads the credentials again: the page calls it when another section added one. */
  reload(): void {
    this.credentialsRes.reload();
  }

  /** Opens the dialog that adds a credential. */
  openAdd(): void {
    this.dialogCredential.set(null);
    this.dialogOpen.set(true);
  }

  protected stateOf(credential: SystemCredential): ReturnType<typeof credentialState> {
    return credentialState(credential);
  }

  protected kindLabel(credential: SystemCredential): string {
    return CREDENTIAL_KIND_LABELS[credential.kind];
  }

  /** A sign-in credential signs in again where the system's app allows; anything else a token. */
  protected reconnectsBySignIn(credential: SystemCredential): boolean {
    return (
      takesSignIn(this.system()) && (credential.kind === 'OAUTH' || !takesToken(this.system()))
    );
  }

  protected reconnectLabel(credential: SystemCredential): string {
    if (this.reconnectsBySignIn(credential)) return 'Sign in again';
    return credential.kind === 'CLIENT' && takesClient(this.system())
      ? 'Store new client credentials'
      : 'Store a new token';
  }

  /** "Lent to 2 groups", or that it is lent to none yet. */
  protected groupsLine(credential: SystemCredential): string {
    const n = credential.grants.length;
    return n ? `Lent to ${plural(n, 'group')}` : 'Lent to no group yet';
  }

  protected membersLine(count: MemberAccountCount): string {
    return memberAccountsLine(count, this.system().name);
  }

  /** "Borrowed by 1 service account", or that none borrows it. */
  protected lendingsLine(credential: SystemCredential): string {
    const n = credential.serviceAccounts.length;
    return n ? `Borrowed by ${plural(n, 'service account')}` : 'Borrowed by no service account';
  }

  // --- Add, store a new token, sign in again --------------------------------------------------

  protected readonly dialogOpen = signal(false);
  protected readonly dialogCredential = signal<SystemCredential | null>(null);

  protected openReconnect(credential: SystemCredential): void {
    this.dialogCredential.set(credential);
    this.dialogOpen.set(true);
  }

  protected onSaved(saved: SystemCredential): void {
    const again = this.dialogCredential();
    this.credentialsRes.reload();
    const login = saved.login ? `As ${saved.login}.` : '';
    if (again) {
      this.notify.success(`${saved.name} works again`, login || undefined);
    } else {
      this.notify.success(
        `${saved.name} is added to ${this.system().name}`,
        `${login ? `${login} ` : ''}Lend it to a group or a service account next.`,
      );
    }
  }

  // --- Rename, disable and enable -------------------------------------------------------------

  protected readonly renameOpen = signal(false);
  protected readonly renaming = signal<SystemCredential | null>(null);
  protected readonly newName = signal('');
  protected readonly renameError = signal<string | null>(null);
  protected readonly renameBusy = signal(false);

  protected openRename(credential: SystemCredential): void {
    this.renaming.set(credential);
    this.newName.set(credential.name);
    this.renameError.set(null);
    this.renameOpen.set(true);
  }

  protected async rename(event: Event): Promise<void> {
    event.preventDefault();
    const credential = this.renaming();
    const name = this.newName().trim();
    if (!credential || this.renameBusy()) return;
    if (!name) {
      this.renameError.set('Name the credential.');
      return;
    }
    if (name === credential.name) {
      this.renameOpen.set(false);
      return;
    }
    this.renameBusy.set(true);
    this.renameError.set(null);
    try {
      await this.api.invoke(updateSystemCredential, {
        ...this.key(),
        credentialId: credential.id,
        body: { name },
      });
      this.renameOpen.set(false);
      this.credentialsRes.reload();
      this.notify.success(`${credential.name} is renamed ${name}`);
    } catch (error) {
      this.renameError.set(credentialProblem(error, this.system().name));
    } finally {
      this.renameBusy.set(false);
    }
  }

  protected async setEnabled(credential: SystemCredential, enabled: boolean): Promise<void> {
    if (this.busy()) return;
    this.busy.set(credential.id);
    try {
      await this.api.invoke(updateSystemCredential, {
        ...this.key(),
        credentialId: credential.id,
        body: { enabled },
      });
      this.credentialsRes.reload();
      if (enabled) {
        this.notify.success(`${credential.name} is enabled again`);
      } else {
        this.notify.success(
          `${credential.name} is disabled`,
          'Nothing acts with it until you enable it again.',
        );
      }
    } catch (error) {
      this.notify.error(
        `Couldn't ${enabled ? 'enable' : 'disable'} ${credential.name}`,
        error,
        CREDENTIAL_MESSAGES,
      );
    } finally {
      this.busy.set(null);
    }
  }

  // --- Remove ---------------------------------------------------------------------------------

  protected readonly removeOpen = signal(false);
  protected readonly removing = signal<SystemCredential | null>(null);
  protected readonly removeBusy = signal(false);
  protected readonly removeError = signal<string | null>(null);
  protected readonly removeLines = computed(() => {
    const credential = this.removing();
    return credential ? removalLines(credential, this.system().name) : [];
  });

  protected askRemove(credential: SystemCredential): void {
    this.removing.set(credential);
    this.removeError.set(null);
    this.removeOpen.set(true);
  }

  protected async confirmRemove(): Promise<void> {
    const credential = this.removing();
    if (!credential || this.removeBusy()) return;
    this.removeBusy.set(true);
    this.removeError.set(null);
    try {
      await this.api.invoke(removeSystemCredential, {
        ...this.key(),
        credentialId: credential.id,
      });
      this.removeOpen.set(false);
      this.credentialsRes.reload();
      this.notify.success(`${credential.name} is removed`);
    } catch (error) {
      this.removeError.set(problemMessage(error, CREDENTIAL_MESSAGES));
    } finally {
      this.removeBusy.set(false);
    }
  }

  // --- Lend -----------------------------------------------------------------------------------

  protected readonly lendOpen = signal(false);
  protected readonly lendCredential = signal<SystemCredential | null>(null);
  protected readonly lendTo = signal<LendTo>('group');

  protected openLend(credential: SystemCredential, to: LendTo): void {
    this.lendCredential.set(credential);
    this.lendTo.set(to);
    this.lendOpen.set(true);
  }

  protected onLent(lent: Lent): void {
    const name = this.lendCredential()?.name ?? 'The credential';
    this.credentialsRes.reload();
    this.notify.success(
      lent.to === 'group' ? `${name} is lent to ${lent.name}` : `${lent.name} borrows ${name} now`,
      ACCESS_LABELS[lent.access],
    );
  }

  protected async changeGrant(
    credential: SystemCredential,
    grant: CredentialGrant,
    value: string | null | undefined,
    pills: ValueHolder,
  ): Promise<void> {
    if ((value !== 'READ' && value !== 'WRITE') || value === grant.access) return;
    if (this.busy()) {
      pills.value.set(grant.access);
      return;
    }
    this.busy.set(grant.id);
    try {
      await this.api.invoke(updateGrant, {
        companyId: this.companyId(),
        grantId: grant.id,
        body: { access: value },
      });
      this.credentialsRes.reload();
      this.notify.success(
        `${grant.groupName} now has ${ACCESS_LABELS[value].toLowerCase()} access to ${credential.name}`,
        'It applies at the credential’s next use.',
      );
    } catch (error) {
      pills.value.set(grant.access);
      this.notify.error("Couldn't change the grant", error, CREDENTIAL_MESSAGES);
    } finally {
      this.busy.set(null);
    }
  }

  protected async removeGrant(credential: SystemCredential, grant: CredentialGrant): Promise<void> {
    if (this.busy()) return;
    this.busy.set(grant.id);
    try {
      await this.api.invoke(deleteGrant, { companyId: this.companyId(), grantId: grant.id });
      this.credentialsRes.reload();
      this.notify.success(
        `${credential.name} is no longer lent to ${grant.groupName}`,
        'Conversations that act with it through the group stop at their next use of it.',
      );
    } catch (error) {
      this.notify.error("Couldn't withdraw the grant", error, CREDENTIAL_MESSAGES);
    } finally {
      this.busy.set(null);
    }
  }

  protected async changeLending(
    credential: SystemCredential,
    lending: CredentialLending,
    value: string | null | undefined,
    pills: ValueHolder,
  ): Promise<void> {
    if ((value !== 'READ' && value !== 'WRITE') || value === lending.access) return;
    const key = `${credential.id}:${lending.serviceIdentityId}`;
    if (this.busy()) {
      pills.value.set(lending.access);
      return;
    }
    this.busy.set(key);
    try {
      await this.api.invoke(lendSystemCredential, {
        ...this.key(),
        credentialId: credential.id,
        serviceIdentityId: lending.serviceIdentityId,
        body: { access: value as AccountAccess },
      });
      this.credentialsRes.reload();
      this.notify.success(
        `${lending.name} now has ${ACCESS_LABELS[value].toLowerCase()} access to ${credential.name}`,
        'It applies at the credential’s next use.',
      );
    } catch (error) {
      pills.value.set(lending.access);
      this.notify.error("Couldn't change the lending", error, CREDENTIAL_MESSAGES);
    } finally {
      this.busy.set(null);
    }
  }

  protected readonly withdrawOpen = signal(false);
  protected readonly withdrawing = signal<{
    credential: SystemCredential;
    lending: CredentialLending;
  } | null>(null);
  protected readonly withdrawBusy = signal(false);
  protected readonly withdrawError = signal<string | null>(null);
  protected readonly withdrawText = computed(() => {
    const target = this.withdrawing();
    return target ? withdrawalText(target.lending, this.system().name) : '';
  });

  protected askWithdraw(credential: SystemCredential, lending: CredentialLending): void {
    this.withdrawing.set({ credential, lending });
    this.withdrawError.set(null);
    this.withdrawOpen.set(true);
  }

  protected async confirmWithdraw(): Promise<void> {
    const target = this.withdrawing();
    if (!target || this.withdrawBusy()) return;
    this.withdrawBusy.set(true);
    this.withdrawError.set(null);
    try {
      await this.api.invoke(withdrawSystemCredential, {
        ...this.key(),
        credentialId: target.credential.id,
        serviceIdentityId: target.lending.serviceIdentityId,
      });
      this.withdrawOpen.set(false);
      this.credentialsRes.reload();
      this.notify.success(`${target.credential.name} is withdrawn from ${target.lending.name}`);
    } catch (error) {
      this.withdrawError.set(
        toProblem(error).status === 404
          ? `${target.lending.name} no longer borrows ${target.credential.name}. Reload the page.`
          : problemMessage(error, CREDENTIAL_MESSAGES),
      );
    } finally {
      this.withdrawBusy.set(false);
    }
  }
}
