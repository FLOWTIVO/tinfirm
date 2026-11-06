import {
  afterRenderEffect,
  ChangeDetectionStrategy,
  Component,
  computed,
  DOCUMENT,
  effect,
  type ElementRef,
  inject,
  input,
  linkedSignal,
  resource,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { NgIcon, provideIcons } from '@ng-icons/core';
import {
  lucideCheck,
  lucideChevronLeft,
  lucideCircleAlert,
  lucideCirclePause,
  lucideCirclePlay,
  lucideEllipsis,
  lucideInbox,
  lucideKeyRound,
  lucidePause,
  lucidePencil,
  lucidePlugZap,
  lucideTrash2,
  lucideUser,
  lucideWebhook,
} from '@ng-icons/lucide';
import { HlmAlertImports } from '@spartan-ng/helm/alert';
import { HlmAlertDialogImports } from '@spartan-ng/helm/alert-dialog';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmDropdownMenuImports } from '@spartan-ng/helm/dropdown-menu';
import { HlmNativeSelectImports } from '@spartan-ng/helm/native-select';
import { HlmSheetImports } from '@spartan-ng/helm/sheet';
import { HlmSkeleton } from '@spartan-ng/helm/skeleton';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { Api } from '../../../api/generated/api';
import {
  deleteSystem,
  getSystem,
  pauseSystem,
  resumeSystem,
  testSystem,
} from '../../../api/generated/functions';
import type {
  ExternalSystem,
  KillSwitch,
  SystemCredential,
  SystemLastTest,
  SystemTestResult,
  WebhookDelivery,
} from '../../../api/generated/models';
import { problemMessage, toProblem } from '../../../api/problem';
import { CurrentCompany } from '../../../core/current-company';
import { KillSwitchState } from '../../../core/kill-switch-state';
import { mediaQuery } from '../../../core/media-query';
import { Notify } from '../../../core/notify';
import { sameParams } from '../../../core/paged-resource';
import { managesCompany } from '../../../core/permissions';
import { ErrorState } from '../../../shared/error-state';
import { plural } from '../../../shared/labels';
import { ReasonDialog } from '../../../shared/reason-dialog';
import { Time } from '../../../shared/time';
import { NotFound } from '../../not-found';
import { SettingRow } from '../settings/setting-row';
import { SystemTriggers } from '../triggers/system-triggers';
import { isTracker } from '../triggers/trigger-labels';
import { ChatBot } from './chat/chat-bot';
import { ChatChannels } from './chat/chat-channels';
import { ChatSystem } from './chat/chat-system';
import { holdsCredentials } from './credential-labels';
import { SystemApp } from './system-app';
import { SystemCredentials } from './system-credentials';
import { SystemDialog } from './system-dialog';
import {
  isChatKind,
  managesSystem,
  pollIntervalPhrase,
  receivesDataSentence,
  SYSTEM_KIND_ICONS,
  systemKind,
  systemKindIcon,
} from './system-kinds';
import { SystemRepositories } from './system-repositories';
import { SystemStatusChip } from './system-status-chip';
import { SystemTools, type ToolsSummary } from './system-tools';
import {
  type DeliveriesSummary,
  deliveryStatus,
  WebhookDeliveries,
  weekLine,
} from './webhook/webhook-deliveries';
import { WebhookMapping } from './webhook/webhook-mapping';
import { secretDue, WebhookSetup } from './webhook/webhook-setup';

/** Messages for a test, a pause or a removal, in the words of this page. */
export const SYSTEM_PAGE_MESSAGES: Record<string, string> = {
  SYSTEM_KIND_INVALID: 'A git host has nothing to reach: its repositories are cloned as they are.',
  'status:403': 'Only owners and admins can change the company’s systems.',
};

/** The sections of a system's page, each a tab; which a system has depends on its kind. */
export type SystemTab =
  | 'overview'
  | 'bot'
  | 'channels'
  | 'mapping'
  | 'deliveries'
  | 'repositories'
  | 'tools'
  | 'triggers';

/** The tones of a health strip's round icons (DESIGN.md: neutral, success, destructive). */
const DOT_TONES = {
  primary: 'bg-primary/10 text-primary',
  muted: 'bg-muted text-muted-foreground',
  success: 'bg-success/10 text-success',
  destructive: 'bg-destructive/10 text-destructive',
  warning: 'bg-warning/10 text-warning',
  dashed: 'border-input border-[1.5px] border-dashed',
} as const;

const TAB_LABELS: Record<SystemTab, string> = {
  overview: 'Overview',
  bot: 'Bot',
  channels: 'Channels',
  mapping: 'Mapping',
  deliveries: 'Deliveries',
  repositories: 'Repositories',
  tools: 'Tools',
  triggers: 'Triggers',
};

/**
 * The tabs a system has, in order: a tracker or code host Overview, Repositories (a
 * code host), Tools (a kind with tools), Triggers; an MCP server Overview and Tools; a chat
 * Overview, Bot, Channels, Tools (Slack's history) and Triggers; a webhook Overview, Mapping,
 * Deliveries (for whoever manages it) and Triggers. A personal server has no triggers.
 */
export function systemTabs(
  system: Pick<ExternalSystem, 'kind' | 'personal'>,
  canManage: boolean,
): SystemTab[] {
  const kind = systemKind(system.kind);
  const tabs: SystemTab[] = ['overview'];
  if (isChatKind(system.kind)) tabs.push('bot', 'channels');
  if (system.kind === 'WEBHOOK') {
    tabs.push('mapping');
    if (canManage) tabs.push('deliveries');
  }
  if (kind.repositories) tabs.push('repositories');
  if (kind.tools) tabs.push('tools');
  if (
    !system.personal &&
    (isTracker(system.kind) || isChatKind(system.kind) || system.kind === 'WEBHOOK')
  ) {
    tabs.push('triggers');
  }
  return tabs;
}

/** The id one arrow key, Home or End moves to in a row of tabs, or null for another key. */
function tabKeyTarget(key: string, ids: readonly string[], current: string): string | null {
  const i = Math.max(0, ids.indexOf(current));
  switch (key) {
    case 'ArrowRight':
      return ids[(i + 1) % ids.length];
    case 'ArrowLeft':
      return ids[(i - 1 + ids.length) % ids.length];
    case 'Home':
      return ids[0];
    case 'End':
      return ids[ids.length - 1];
    default:
      return null;
  }
}

/** "Its 2 webhook triggers are deleted; …": what a removal does to the triggers that use it. */
function triggersLine(n: number | null, what: string): string {
  if (n === null) return `Its ${what}s are deleted; their conversations end, readable in the app.`;
  if (n === 0) return `No ${what} uses it, so none is deleted.`;
  return n === 1
    ? `Its ${what} is deleted; its conversations end, readable in the app.`
    : `Its ${n} ${what}s are deleted; their conversations end, readable in the app.`;
}

/** What a removal stops at once, a line each, from what the page has read of the system. */
export function removalEffects(
  system: Pick<ExternalSystem, 'kind' | 'personal' | 'app'>,
  known: {
    credentials: number | null;
    repositories: string | null;
    tools: number | null;
    triggers: number | null;
  },
): string[] {
  const kind = systemKind(system.kind);
  if (system.kind === 'WEBHOOK') {
    return [
      'Its URL stops taking deliveries at once, and its secret is deleted.',
      triggersLine(known.triggers, 'webhook trigger'),
    ];
  }
  if (isChatKind(system.kind)) {
    const lines = [
      'Its bot and secrets are deleted, and every member’s link to it goes.',
      'Its open direct-message conversations end; nothing more arrives from the chat.',
    ];
    if (system.app) lines.push('Its sign-in app’s client secret is deleted.');
    lines.push(triggersLine(known.triggers, 'channel trigger'));
    return lines;
  }
  const lines: string[] = [];
  if (system.personal) {
    lines.push('Your account on it is disconnected.');
  } else {
    const n = known.credentials;
    lines.push(
      n === null
        ? 'Every account on it is disconnected: members’ own, and the company’s credentials with what they are lent to.'
        : n === 0
          ? 'Every member’s own account on it is disconnected.'
          : `Every account on it is disconnected: members’ own, and its ${plural(n, 'credential')} with what ${n === 1 ? 'it is' : 'they are'} lent to.`,
    );
  }
  const lost: string[] = [];
  if (kind.repositories) {
    const n = known.repositories;
    lost.push(
      n && n !== '0' ? `its ${n} ${n === '1' ? 'repository' : 'repositories'}` : 'its repositories',
    );
  }
  if (kind.tools) lost.push(known.tools ? `its ${plural(known.tools, 'tool')}` : 'its tools');
  if (lost.length) lines.push(`Agents lose ${lost.join(' and ')}.`);
  if (system.app) lines.push('The sign-in app’s client secret is deleted.');
  if (isTracker(system.kind)) {
    const n = known.triggers;
    lines.push(
      n === 0
        ? 'No trigger watches it, so none is deleted.'
        : n === 1
          ? 'The trigger that watches it is deleted; its conversations end, readable in the app.'
          : `${n === null ? 'The' : `The ${n}`} triggers that watch it are deleted; their conversations end, readable in the app.`,
    );
  }
  return lines;
}

/**
 * One system (the web plan §5, Systems): its name, kind, status and address, and for a system with
 * tools what agents send it (`receivesData`), with Test, Change and a menu of Pause or Resume and
 * Remove (a bottom sheet with all four on a phone); a health strip with the last test, the error or
 * the pause, and how accounts connect; then its sections as tabs by
 * kind (`?tab=`), each tab with its count. A company system's Overview holds its credentials
 * (ADR-0072), so that registering it, adding a credential, lending it and reading its tools need no
 * other page. A test reaches it with the credential the admin picks, else the caller's own account
 * when they hold one, else the system's only working credential. OWNER and ADMIN manage a company
 * system; a member reaches only their own MCP server's page, from Accounts, and manages it, and
 * reads a company system's. The server answers 404 for anything else, and the page says only that.
 */
@Component({
  selector: 'app-system-page',
  imports: [
    RouterLink,
    NgIcon,
    HlmAlertImports,
    HlmAlertDialogImports,
    HlmButton,
    HlmDropdownMenuImports,
    HlmNativeSelectImports,
    HlmSheetImports,
    HlmSkeleton,
    HlmSpinner,
    ErrorState,
    NotFound,
    ReasonDialog,
    SettingRow,
    Time,
    ChatBot,
    ChatChannels,
    ChatSystem,
    SystemApp,
    SystemCredentials,
    SystemDialog,
    SystemRepositories,
    SystemStatusChip,
    SystemTools,
    SystemTriggers,
    WebhookDeliveries,
    WebhookMapping,
    WebhookSetup,
  ],
  providers: [
    provideIcons({
      ...SYSTEM_KIND_ICONS,
      lucideCheck,
      lucideChevronLeft,
      lucideCircleAlert,
      lucideCirclePause,
      lucideCirclePlay,
      lucideEllipsis,
      lucideInbox,
      lucideKeyRound,
      lucidePause,
      lucidePencil,
      lucidePlugZap,
      lucideTrash2,
      lucideUser,
      lucideWebhook,
    }),
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (notFound()) {
      <app-not-found />
    } @else if (systemRes.error() && !system()) {
      <app-error-state
        [error]="systemRes.error()"
        heading="Couldn't open this system"
        (retry)="systemRes.reload()"
      />
    } @else if (system(); as s) {
      <div class="flex max-w-[70rem] flex-col gap-5">
        <header class="flex flex-col gap-3">
          <a
            class="text-muted-foreground hover:text-foreground inline-flex w-fit items-center gap-1 text-sm font-medium"
            data-testid="system-back"
            [routerLink]="back().link"
            [queryParams]="back().query"
          >
            <ng-icon name="lucideChevronLeft" aria-hidden="true" />
            {{ back().label }}
          </a>
          <div class="flex items-start gap-3 sm:flex-wrap sm:gap-4">
            <div class="flex min-w-0 flex-1 items-start gap-3 sm:basis-96 sm:gap-3.5">
              <span
                class="bg-card text-muted-foreground flex size-10 shrink-0 items-center justify-center rounded-xl border sm:size-11 sm:rounded-[14px]"
                aria-hidden="true"
              >
                <ng-icon [name]="kindIcon()" size="20" />
              </span>
              <div class="flex min-w-0 flex-col gap-1">
                <div class="flex flex-wrap items-center gap-x-2.5 gap-y-1.5">
                  <h1 class="text-xl font-bold tracking-tight text-balance sm:text-2xl">
                    {{ s.name }}
                  </h1>
                  <app-system-status-chip [system]="s" [page]="true" />
                </div>
                <p
                  class="text-muted-foreground flex flex-wrap items-baseline gap-x-2 gap-y-0.5 text-sm"
                >
                  <span data-testid="system-kind">{{ kind().label }}</span>
                  @if (s.baseUrl && !chat()) {
                    <span aria-hidden="true">·</span>
                    <code
                      class="text-foreground font-mono text-[0.8125rem] break-all"
                      data-testid="system-base-url"
                      >{{ s.baseUrl }}</code
                    >
                  }
                  @if (s.webhook; as w) {
                    <span aria-hidden="true">·</span>
                    <span>{{
                      w.scheme === 'SECRET_HEADER'
                        ? 'A secret in the ' + (w.secretHeader || 'X-Tinfirm-Secret') + ' header'
                        : 'Signed deliveries (Standard Webhooks)'
                    }}</span>
                    <span aria-hidden="true">·</span>
                    <span
                      ><span class="font-mono">{{ w.perMinute }}</span> a minute at most</span
                    >
                  }
                </p>
                @if (receivesData(); as sentence) {
                  <p
                    class="text-muted-foreground max-w-prose text-sm text-pretty"
                    data-testid="system-receives-data"
                  >
                    {{ sentence }}
                  </p>
                }
              </div>
            </div>
            @if (canManage()) {
              <div class="flex shrink-0 items-center gap-2">
                @if (wide()) {
                  @if (testable()) {
                    @if (testCredentials().length > 1) {
                      <label class="sr-only" for="test-credential">Test with</label>
                      <hlm-native-select
                        size="sm"
                        class="w-52"
                        selectId="test-credential"
                        data-testid="test-credential"
                        [value]="testCredentialId()"
                        (valueChange)="testCredentialId.set($event ?? '')"
                      >
                        <option value="" [title]="usualAccount">With the usual account</option>
                        @for (c of testCredentials(); track c.id) {
                          <option [value]="c.id">With {{ c.name }}</option>
                        }
                      </hlm-native-select>
                    }
                    <button
                      hlmBtn
                      variant="outline"
                      type="button"
                      data-testid="test-system"
                      [disabled]="testing() || !!s.paused"
                      (click)="test()"
                    >
                      @if (testing()) {
                        <hlm-spinner aria-hidden="true" />
                      } @else {
                        <ng-icon name="lucidePlugZap" aria-hidden="true" />
                      }
                      Test
                    </button>
                  }
                  <button
                    hlmBtn
                    variant="ghost"
                    type="button"
                    data-testid="edit-system"
                    (click)="editOpen.set(true)"
                  >
                    <ng-icon name="lucidePencil" aria-hidden="true" />
                    Change
                  </button>
                  <button
                    hlmBtn
                    variant="outline"
                    size="icon"
                    type="button"
                    data-testid="system-menu"
                    [attr.aria-label]="'More actions for ' + s.name"
                    [hlmDropdownMenuTrigger]="menu"
                    align="end"
                  >
                    <ng-icon name="lucideEllipsis" aria-hidden="true" />
                  </button>
                } @else {
                  <button
                    hlmBtn
                    variant="outline"
                    size="icon"
                    type="button"
                    class="size-11"
                    data-testid="system-menu"
                    aria-haspopup="dialog"
                    [attr.aria-expanded]="sheetOpen()"
                    [attr.aria-label]="'Actions for ' + s.name"
                    (click)="sheetOpen.set(true)"
                  >
                    <ng-icon name="lucideEllipsis" aria-hidden="true" />
                  </button>
                }
              </div>
            }
          </div>
        </header>

        @if (s.personal) {
          <p
            class="bg-card text-muted-foreground flex items-start gap-2 rounded-xl border px-3.5 py-2.5 text-sm"
            data-testid="system-personal"
          >
            <ng-icon name="lucideUser" class="mt-0.5 shrink-0" aria-hidden="true" />
            Your own MCP server: it acts for you only, with your own account, and nobody else sees
            it, admins included.
          </p>
        }

        @if (healthA() || healthB()) {
          <section
            class="bg-border grid gap-px overflow-hidden rounded-xl border sm:grid-cols-[repeat(auto-fit,minmax(min(24rem,100%),1fr))]"
            [attr.aria-label]="'Health of ' + s.name"
            data-testid="system-health"
          >
            @if (healthA()) {
              <div
                class="bg-card flex min-h-15 flex-wrap items-center gap-x-3 gap-y-2 py-2.5 ps-4 pe-3"
              >
                <div
                  class="flex min-w-0 flex-1 basis-56 items-center gap-3 text-sm"
                  role="status"
                  aria-live="polite"
                >
                  @if (testing()) {
                    <span [class]="dot('primary')" aria-hidden="true">
                      <hlm-spinner class="size-4" />
                    </span>
                    <p class="min-w-0 text-pretty">
                      <span class="font-semibold">Testing</span>
                      <span class="text-muted-foreground">
                        ·
                        {{
                          chat()
                            ? 'checking the bot and its secrets'
                            : 'reaching ' + s.name + ' ' + testingWith()
                        }}</span
                      >
                    </p>
                  } @else if (s.paused) {
                    <span [class]="dot('muted')" aria-hidden="true">
                      <ng-icon name="lucidePause" size="16" />
                    </span>
                    <p class="min-w-0 text-pretty" data-testid="system-paused-note">
                      <span class="font-semibold">
                        @if (pausedSwitch(); as kill) {
                          Paused by {{ kill.byName ?? 'an admin' }}
                          @if (kill.since; as since) {
                            <app-time [value]="since" mode="clock" />
                          }
                          @if (kill.reason) {
                            <span data-testid="system-paused-reason">: “{{ kill.reason }}”</span>
                          }
                        } @else {
                          Paused by an admin
                        }
                      </span>
                      <span class="text-muted-foreground block text-[0.8125rem]"
                        >Its accounts and credentials act for nothing, its tools are offered to no
                        agent and its triggers wait until it is resumed. Testing and connecting
                        accounts wait too.</span
                      >
                    </p>
                  } @else if (s.kind === 'WEBHOOK') {
                    <span [class]="dot('muted')" aria-hidden="true">
                      <ng-icon name="lucideInbox" size="15" />
                    </span>
                    <p class="min-w-0 text-pretty" data-testid="webhook-last-delivery">
                      @if (deliveries(); as d) {
                        @if (d.latest; as latest) {
                          <span class="font-semibold"
                            >Last delivery <app-time [value]="latest.receivedAt"
                          /></span>
                          <span class="text-muted-foreground"> · {{ deliveryLabel(latest) }}</span>
                        } @else {
                          <span class="font-semibold">No delivery yet</span>
                          <span class="text-muted-foreground">
                            · send one from the other software to see it here</span
                          >
                        }
                        @if (d.lastSevenDays; as week) {
                          <span class="text-muted-foreground" data-testid="webhook-week">
                            · {{ weekLine(week) }}</span
                          >
                        }
                      } @else {
                        <span class="text-muted-foreground">Reading the deliveries…</span>
                      }
                    </p>
                  } @else if (testResult(); as result) {
                    <span [class]="dot(result.ok ? 'success' : 'destructive')" aria-hidden="true">
                      <ng-icon [name]="result.ok ? 'lucideCheck' : 'lucideCircleAlert'" size="16" />
                    </span>
                    <p
                      class="min-w-0 text-pretty"
                      data-testid="test-result"
                      [attr.data-ok]="result.ok"
                    >
                      <span class="font-semibold" [class.text-destructive]="!result.ok"
                        >{{ result.ok ? 'Tested' : 'Test failed' }}
                        <app-time [value]="testedAt()" /> by you</span
                      >
                      <span class="text-muted-foreground"> · {{ testSentence(result) }}</span>
                    </p>
                  } @else if (storedTest(); as last) {
                    <span [class]="dot(last.ok ? 'success' : 'destructive')" aria-hidden="true">
                      <ng-icon [name]="last.ok ? 'lucideCheck' : 'lucideCircleAlert'" size="16" />
                    </span>
                    <p
                      class="min-w-0 text-pretty"
                      data-testid="system-last-test"
                      [attr.data-ok]="last.ok"
                    >
                      <span class="font-semibold" [class.text-destructive]="!last.ok"
                        >{{ last.ok ? 'Tested' : 'Test failed' }} <app-time [value]="last.at" />{{
                          last.byName ? ' by ' + last.byName : ''
                        }}</span
                      >
                      <span class="text-muted-foreground"> · {{ lastTestSentence(last) }}</span>
                    </p>
                  } @else if (s.status === 'ERROR') {
                    <span [class]="dot('destructive')" aria-hidden="true">
                      <ng-icon name="lucideCircleAlert" size="16" />
                    </span>
                    <p class="min-w-0 text-pretty" data-testid="system-last-error">
                      <span class="text-destructive font-semibold">{{
                        s.lastTest?.ok && kind().tools === 'catalogue'
                          ? 'Its tools could not be read'
                          : 'The last test failed'
                      }}</span>
                      <span class="text-muted-foreground">
                        · {{ s.lastError || 'no reason given' }}</span
                      >
                    </p>
                  } @else if (s.status === 'OK') {
                    <span [class]="dot('success')" aria-hidden="true">
                      <ng-icon name="lucideCheck" size="16" />
                    </span>
                    <p class="min-w-0 text-pretty" data-testid="system-reached">
                      <span class="font-semibold">{{ chat() ? 'Passed' : 'Reached' }}</span>
                      <span class="text-muted-foreground">
                        · at its last
                        {{ kind().tools === 'catalogue' ? 'test or refresh' : 'test' }}
                      </span>
                    </p>
                  } @else {
                    <span [class]="dot('dashed')" aria-hidden="true"></span>
                    <p class="min-w-0 text-pretty" data-testid="system-untested">
                      <span class="font-semibold">Not tested yet</span>
                      @if (canManage()) {
                        <span class="text-muted-foreground">
                          ·
                          @if (chat()) {
                            Test checks the bot, the stored secrets and, with Socket Mode, the
                            connection to Slack.
                          } @else {
                            Test reaches it with your own account when you hold one, else with its
                            only working credential, else without an account.
                          }
                        </span>
                      }
                    </p>
                  }
                </div>
                @if (!testing()) {
                  @if (s.paused) {
                    @if (canPause()) {
                      <button
                        hlmBtn
                        variant="outline"
                        size="sm"
                        type="button"
                        class="max-sm:h-11"
                        data-testid="health-resume"
                        (click)="openResume()"
                      >
                        <ng-icon name="lucideCirclePlay" aria-hidden="true" />
                        Resume
                      </button>
                    }
                  } @else if (
                    canManage() &&
                    testable() &&
                    (testResult()?.ok === false || (!testResult() && s.status === 'ERROR'))
                  ) {
                    <button
                      hlmBtn
                      variant="outline"
                      size="sm"
                      type="button"
                      class="max-sm:h-11"
                      data-testid="test-again"
                      (click)="test()"
                    >
                      Test again
                    </button>
                  } @else if (offerCredential()) {
                    <button
                      hlmBtn
                      variant="outline"
                      size="sm"
                      type="button"
                      class="max-sm:h-11"
                      data-testid="health-add-credential"
                      (click)="addCredential()"
                    >
                      Add a credential
                    </button>
                  }
                }
              </div>
            }
            @if (healthB()) {
              <div
                class="bg-card flex min-h-15 flex-wrap items-center gap-x-3 gap-y-2 py-2.5 ps-4 pe-3"
              >
                <div class="flex min-w-0 flex-1 basis-56 items-center gap-3 text-sm">
                  @if (chat()) {
                    @let bot = !!s.chat?.botSet;
                    <span [class]="dot(bot ? 'success' : 'muted')" aria-hidden="true">
                      <ng-icon name="lucideKeyRound" size="15" />
                    </span>
                    <p class="min-w-0 text-pretty" data-testid="health-bot">
                      @if (bot) {
                        <span class="font-semibold">Bot: set</span>
                        <span class="text-muted-foreground">
                          · lent to {{ s.chat?.botPrincipalName ?? 'a service account' }}</span
                        >
                      } @else {
                        <span class="font-semibold">Bot: not set</span>
                        <span class="text-muted-foreground">
                          — what people write is checked and dropped, unanswered</span
                        >
                      }
                    </p>
                  } @else if (s.webhook; as w) {
                    <span [class]="dot(secretOld() ? 'warning' : 'muted')" aria-hidden="true">
                      <ng-icon name="lucideKeyRound" size="15" />
                    </span>
                    <p class="min-w-0 text-pretty" data-testid="health-secret">
                      <span class="font-semibold"
                        >Secret set
                        @if (w.secretSetAt; as at) {
                          <app-time [value]="at" />
                        }
                      </span>
                      @if (secretOld()) {
                        <span class="text-warning"> · older than 90 days: rotate it</span>
                      } @else {
                        <span class="text-muted-foreground">
                          · rotate it before it is 90 days old</span
                        >
                      }
                    </p>
                  } @else {
                    @let app = !!s.app || s.oauthAvailable || !s.accountsRequired;
                    <span [class]="dot(app ? 'success' : 'muted')" aria-hidden="true">
                      <ng-icon name="lucideKeyRound" size="15" />
                    </span>
                    <p class="min-w-0 text-pretty" data-testid="health-app">
                      @if (!s.accountsRequired) {
                        <span class="font-semibold">No account needed</span>
                        <span class="text-muted-foreground">
                          · runs call it without a credential, and nobody connects one</span
                        >
                      } @else if (s.app; as a) {
                        <span class="font-semibold">Sign-in app: set up</span>
                        <span class="text-muted-foreground">
                          · client ID
                          <code class="text-foreground font-mono text-[0.8125rem] break-all">{{
                            a.clientId
                          }}</code></span
                        >
                      } @else if (s.oauthAvailable) {
                        <span class="font-semibold">Sign-in app: set up</span>
                        <span class="text-muted-foreground">
                          · accounts connect by signing in, or with a token</span
                        >
                      } @else {
                        <span class="font-semibold">Sign-in app: not set up</span>
                        <span class="text-muted-foreground">
                          @if (s.kind === 'SLACK') {
                            — nobody signs in, so agents cannot read its channels
                          } @else {
                            — accounts connect by token only
                          }
                        </span>
                      }
                    </p>
                  }
                </div>
                @if (canManage()) {
                  @if (chat()) {
                    @if (!s.chat?.botSet && !s.paused) {
                      <button
                        hlmBtn
                        variant="outline"
                        size="sm"
                        type="button"
                        class="max-sm:h-11"
                        data-testid="health-set-bot"
                        (click)="setBot()"
                      >
                        Set the bot
                      </button>
                    }
                  } @else if (s.webhook) {
                    <button
                      hlmBtn
                      variant="outline"
                      size="sm"
                      type="button"
                      class="max-sm:h-11"
                      data-testid="health-rotate"
                      (click)="rotateSecret()"
                    >
                      Rotate the secret
                    </button>
                  } @else if (!s.app && !s.oauthAvailable && s.accountsRequired) {
                    <button
                      hlmBtn
                      variant="outline"
                      size="sm"
                      type="button"
                      class="max-sm:h-11"
                      data-testid="health-set-app"
                      (click)="setUpApp()"
                    >
                      Set up the app
                    </button>
                  }
                }
              </div>
            }
          </section>
        }

        <div
          #tablist
          class="-mx-4 flex gap-1.5 overflow-x-auto px-4 pt-0.5 pb-1 [scrollbar-width:none] sm:mx-0 sm:gap-1 sm:px-0 [&::-webkit-scrollbar]:hidden"
          role="tablist"
          [attr.aria-label]="'Sections of ' + s.name"
          data-testid="system-tabs"
        >
          @for (t of tabRows(); track t.id) {
            @let selected = t.id === activeTab();
            <button
              type="button"
              role="tab"
              class="focus-visible:ring-ring/50 inline-flex h-11 shrink-0 items-center gap-2 rounded-full border px-4 text-sm font-semibold whitespace-nowrap outline-none focus-visible:ring-3 sm:h-10"
              [class]="
                selected
                  ? 'bg-card text-foreground border-border shadow-[0_1px_3px_rgb(0_0_0/0.08)]'
                  : 'text-muted-foreground hover:bg-accent hover:text-foreground border-transparent'
              "
              [id]="'system-tab-' + t.id"
              [attr.aria-selected]="selected"
              [attr.aria-controls]="'system-panel-' + t.id"
              [tabIndex]="selected ? 0 : -1"
              [attr.data-tab]="t.id"
              (click)="openTab(t.id)"
              (keydown)="onTabKeys($event)"
            >
              {{ t.label }}
              @if (t.count !== null) {
                <span class="text-muted-foreground font-mono text-xs font-medium">{{
                  t.count
                }}</span>
              }
              @if (t.toConfirm > 0) {
                <span
                  class="bg-human-soft text-human inline-flex h-5 items-center gap-1 rounded-full px-2 text-[0.6875rem] font-semibold"
                  data-testid="tab-to-confirm"
                  ><span class="font-mono font-medium">{{ t.toConfirm }}</span> to confirm</span
                >
              }
            </button>
          }
        </div>

        <!-- Every panel stays rendered, so that each tab's count is known; one shows at a time. -->
        <div
          class="focus-visible:ring-ring/50 flex flex-col gap-5 rounded-xl outline-none focus-visible:ring-3"
          role="tabpanel"
          id="system-panel-overview"
          aria-labelledby="system-tab-overview"
          tabindex="0"
          [hidden]="activeTab() !== 'overview'"
        >
          @if (chat()) {
            <app-chat-system
              [companyId]="companyId()!"
              [system]="s"
              [canManage]="canManage()"
              (saved)="system.set($event)"
              (changeTransport)="editOpen.set(true)"
            />
          }
          @if (s.kind === 'WEBHOOK' && s.webhook) {
            <app-webhook-setup
              [companyId]="companyId()!"
              [system]="s"
              [canManage]="canManage()"
              (changeSettings)="editOpen.set(true)"
              (rotatedSecret)="systemRes.reload()"
            />
          }
          @if (credentialsShown()) {
            <app-system-credentials
              [companyId]="companyId()!"
              [system]="s"
              (credentials)="credentials.set($event)"
            />
          }
          @if (kind().app) {
            <app-system-app
              [companyId]="companyId()!"
              [system]="s"
              [canManage]="canManage()"
              (changed)="system.set($event)"
            />
          }
          @if (s.pollIntervalSeconds; as seconds) {
            <section
              class="bg-card overflow-hidden rounded-xl border"
              aria-labelledby="reading-title"
              data-testid="system-reading"
            >
              <div class="flex flex-col gap-0.5 px-4 py-4 sm:px-5 sm:pt-5">
                <h2 id="reading-title" class="text-base font-bold">How Tinfirm reads it</h2>
                <p class="text-muted-foreground text-sm">
                  For the triggers that watch {{ s.name }}.
                </p>
              </div>
              <app-setting-row rowId="reading-interval" label="Checked for changes">
                <span help
                  >More often reacts sooner and uses more of {{ s.name }}’s rate limit.</span
                >
                <div class="flex w-full flex-wrap items-center gap-x-2.5 gap-y-1 text-sm">
                  <span class="font-semibold" data-testid="system-poll-interval">{{
                    pollPhrase(seconds)
                  }}</span>
                  @if (seconds === 60) {
                    <span class="text-muted-foreground"
                      >the default, from <span class="font-mono">30</span> seconds to an hour</span
                    >
                  }
                  @if (canManage()) {
                    <button
                      hlmBtn
                      variant="ghost"
                      size="sm"
                      type="button"
                      class="ms-auto max-sm:h-11"
                      data-testid="change-poll-interval"
                      (click)="editOpen.set(true)"
                    >
                      Change
                    </button>
                  }
                </div>
              </app-setting-row>
            </section>
          }
          @if (!chat() && s.kind !== 'WEBHOOK' && !kind().app && !credentialsShown()) {
            <p
              class="text-muted-foreground rounded-xl border border-dashed px-4 py-6 text-center text-sm"
            >
              {{ s.name }} has nothing more to show here.
            </p>
          }
        </div>

        @if (chat()) {
          <div
            class="focus-visible:ring-ring/50 rounded-xl outline-none focus-visible:ring-3"
            role="tabpanel"
            id="system-panel-bot"
            aria-labelledby="system-tab-bot"
            tabindex="0"
            [hidden]="activeTab() !== 'bot'"
          >
            <app-chat-bot
              [companyId]="companyId()!"
              [system]="s"
              [canManage]="canManage()"
              (changed)="systemRes.reload()"
            />
          </div>
          <div
            class="focus-visible:ring-ring/50 rounded-xl outline-none focus-visible:ring-3"
            role="tabpanel"
            id="system-panel-channels"
            aria-labelledby="system-tab-channels"
            tabindex="0"
            [hidden]="activeTab() !== 'channels'"
          >
            <app-chat-channels
              [companyId]="companyId()!"
              [system]="s"
              (count)="channelCount.set($event)"
            />
          </div>
        }
        @if (s.kind === 'WEBHOOK' && s.webhook) {
          <div
            class="focus-visible:ring-ring/50 rounded-xl outline-none focus-visible:ring-3"
            role="tabpanel"
            id="system-panel-mapping"
            aria-labelledby="system-tab-mapping"
            tabindex="0"
            [hidden]="activeTab() !== 'mapping'"
          >
            <app-webhook-mapping
              [companyId]="companyId()!"
              [system]="s"
              [canManage]="canManage()"
              (saved)="system.set($event)"
            />
          </div>
          @if (canManage()) {
            <div
              class="focus-visible:ring-ring/50 rounded-xl outline-none focus-visible:ring-3"
              role="tabpanel"
              id="system-panel-deliveries"
              aria-labelledby="system-tab-deliveries"
              tabindex="0"
              [hidden]="activeTab() !== 'deliveries'"
            >
              <app-webhook-deliveries
                [companyId]="companyId()!"
                [system]="s"
                (summary)="deliveries.set($event)"
              />
            </div>
          }
        }
        @if (kind().repositories) {
          <div
            class="focus-visible:ring-ring/50 rounded-xl outline-none focus-visible:ring-3"
            role="tabpanel"
            id="system-panel-repositories"
            aria-labelledby="system-tab-repositories"
            tabindex="0"
            [hidden]="activeTab() !== 'repositories'"
          >
            <app-system-repositories
              [companyId]="companyId()!"
              [system]="s"
              [canManage]="canManage() && !s.personal"
              (count)="repositoryCount.set($event)"
            />
          </div>
        }
        @if (kind().tools) {
          <div
            class="focus-visible:ring-ring/50 rounded-xl outline-none focus-visible:ring-3"
            role="tabpanel"
            id="system-panel-tools"
            aria-labelledby="system-tab-tools"
            tabindex="0"
            [hidden]="activeTab() !== 'tools'"
          >
            <app-system-tools
              [companyId]="companyId()!"
              [system]="s"
              [canManage]="canManage()"
              [credentials]="credentials() ?? []"
              (summary)="tools.set($event)"
              (credentialAdded)="credentialsCard()?.reload()"
            />
          </div>
        }
        @if (tabs().includes('triggers')) {
          <div
            class="focus-visible:ring-ring/50 rounded-xl outline-none focus-visible:ring-3"
            role="tabpanel"
            id="system-panel-triggers"
            aria-labelledby="system-tab-triggers"
            tabindex="0"
            [hidden]="activeTab() !== 'triggers'"
          >
            <app-system-triggers
              [companyId]="companyId()!"
              [systemId]="s.id"
              [name]="s.name"
              [source]="triggerSource()"
              [showPolls]="canManage() && isTracker(s.kind)"
              (count)="triggerCount.set($event)"
            />
          </div>
        }
      </div>

      <ng-template #menu>
        <hlm-dropdown-menu class="w-76">
          @if (canPause()) {
            @if (s.paused) {
              <button
                hlmDropdownMenuItem
                type="button"
                class="items-start gap-2.5 px-2.5 py-2 text-start"
                data-testid="resume-system"
                (triggered)="openResume()"
              >
                <ng-icon name="lucideCirclePlay" class="mt-0.5" aria-hidden="true" />
                <span class="flex min-w-0 flex-col">
                  <span class="font-semibold">Resume {{ s.name }}</span>
                  <span class="text-muted-foreground text-xs"
                    >Its accounts and credentials work again; waiting turns go on</span
                  >
                </span>
              </button>
            } @else {
              <button
                hlmDropdownMenuItem
                type="button"
                class="items-start gap-2.5 px-2.5 py-2 text-start"
                data-testid="pause-system"
                (triggered)="openPause()"
              >
                <ng-icon name="lucideCirclePause" class="mt-0.5" aria-hidden="true" />
                <span class="flex min-w-0 flex-col">
                  <span class="font-semibold">Pause {{ s.name }}…</span>
                  <span class="text-muted-foreground text-xs"
                    >Its accounts and credentials act for nothing until you resume it</span
                  >
                </span>
              </button>
            }
            <hlm-dropdown-menu-separator />
          }
          <button
            hlmDropdownMenuItem
            variant="destructive"
            type="button"
            class="items-start gap-2.5 px-2.5 py-2 text-start"
            data-testid="delete-system"
            (triggered)="openDelete()"
          >
            <ng-icon name="lucideTrash2" class="mt-0.5" aria-hidden="true" />
            <span class="flex min-w-0 flex-col">
              <span class="font-semibold">Remove {{ s.name }}…</span>
              <span class="text-muted-foreground text-xs">{{ removeHint() }}</span>
            </span>
          </button>
        </hlm-dropdown-menu>
      </ng-template>

      <hlm-sheet side="bottom" [state]="sheetOpen() ? 'open' : 'closed'" (closed)="closeSheet()">
        <hlm-sheet-content
          *hlmSheetPortal="let ctx"
          class="gap-0 rounded-t-[18px] px-4 pt-2 pb-6"
          data-testid="system-actions"
          [showCloseButton]="false"
        >
          <div class="bg-input mx-auto mb-3 h-1 w-9 rounded-full" aria-hidden="true"></div>
          <hlm-sheet-header class="gap-0.5 p-0 text-start">
            <h2 hlmSheetTitle class="text-base font-bold">{{ s.name }}</h2>
            <p hlmSheetDescription>{{ sheetLine() }}</p>
          </hlm-sheet-header>
          <div class="mt-4 flex flex-col gap-2">
            @if (testable()) {
              <button
                hlmBtn
                variant="outline"
                type="button"
                class="h-11 justify-start"
                data-testid="test-system"
                [disabled]="testing() || !!s.paused"
                (click)="fromSheet('test')"
              >
                <ng-icon name="lucidePlugZap" aria-hidden="true" />
                Test
              </button>
            }
            <button
              hlmBtn
              variant="outline"
              type="button"
              class="h-11 justify-start"
              data-testid="edit-system"
              (click)="fromSheet('edit')"
            >
              <ng-icon name="lucidePencil" aria-hidden="true" />
              Change
            </button>
            @if (canPause()) {
              @if (s.paused) {
                <button
                  hlmBtn
                  variant="outline"
                  type="button"
                  class="h-11 justify-start"
                  data-testid="resume-system"
                  (click)="fromSheet('resume')"
                >
                  <ng-icon name="lucideCirclePlay" aria-hidden="true" />
                  Resume {{ s.name }}
                </button>
              } @else {
                <button
                  hlmBtn
                  variant="outline"
                  type="button"
                  class="h-11 justify-start"
                  data-testid="pause-system"
                  (click)="fromSheet('pause')"
                >
                  <ng-icon name="lucideCirclePause" aria-hidden="true" />
                  Pause {{ s.name }}…
                </button>
              }
            }
            <button
              hlmBtn
              variant="destructive"
              type="button"
              class="h-11 justify-start"
              data-testid="delete-system"
              (click)="fromSheet('remove')"
            >
              <ng-icon name="lucideTrash2" aria-hidden="true" />
              Remove {{ s.name }}…
            </button>
            <button hlmBtn variant="ghost" type="button" class="h-11" (click)="closeSheet()">
              Close
            </button>
          </div>
        </hlm-sheet-content>
      </hlm-sheet>

      <app-system-dialog [(open)]="editOpen" [system]="s" (saved)="onChanged($event)" />

      <hlm-alert-dialog [state]="deleteOpen() ? 'open' : 'closed'" (closed)="deleteOpen.set(false)">
        <hlm-alert-dialog-content *hlmAlertDialogPortal="let ctx" class="sm:max-w-lg">
          <hlm-alert-dialog-header>
            <h2 hlmAlertDialogTitle>Remove {{ s.name }}?</h2>
            <p hlmAlertDialogDescription>This cannot be undone. What stops at once:</p>
          </hlm-alert-dialog-header>
          <ul
            class="flex list-disc flex-col gap-1.5 rounded-lg border py-3 ps-8 pe-3.5 text-sm text-pretty"
            data-testid="remove-effects"
          >
            @for (line of removeEffects(); track $index) {
              <li>{{ line }}</li>
            }
          </ul>
          <p class="text-muted-foreground text-[0.8125rem]">
            Conversations that used it stay, readable in the app.
          </p>
          @if (deleteError(); as message) {
            <div hlmAlert variant="destructive">
              <ng-icon name="lucideCircleAlert" aria-hidden="true" />
              <p hlmAlertDescription>{{ message }}</p>
            </div>
          }
          <hlm-alert-dialog-footer>
            <button hlmAlertDialogCancel [disabled]="deleteBusy()">Cancel</button>
            <button
              hlmAlertDialogAction
              variant="destructive"
              data-testid="confirm-delete-system"
              [disabled]="deleteBusy()"
              (click)="confirmDelete()"
            >
              @if (deleteBusy()) {
                <hlm-spinner aria-hidden="true" />
              } @else {
                <ng-icon name="lucideTrash2" aria-hidden="true" />
              }
              Remove {{ s.name }}
            </button>
          </hlm-alert-dialog-footer>
        </hlm-alert-dialog-content>
      </hlm-alert-dialog>

      <app-reason-dialog
        testId="pause-dialog"
        label="Why"
        note="Shown here and on Kill switches, with your name."
        [heading]="'Pause ' + s.name + '?'"
        [description]="pauseDescription"
        [confirmLabel]="'Pause ' + s.name"
        [destructive]="false"
        [open]="pauseOpen()"
        [busy]="pauseBusy()"
        [error]="pauseError()"
        (confirm)="pause($event)"
        (closed)="pauseOpen.set(false)"
      >
        <label class="flex items-start gap-2 text-sm">
          <input
            type="checkbox"
            class="accent-primary mt-0.5 size-4 shrink-0"
            data-testid="pause-stop-running"
            [checked]="pauseStopRunning()"
            (change)="pauseStopRunning.set($any($event.target).checked)"
          />
          <span>Also cancel the turns running now in conversations that use it</span>
        </label>
      </app-reason-dialog>

      <hlm-alert-dialog [state]="resumeOpen() ? 'open' : 'closed'" (closed)="resumeOpen.set(false)">
        <hlm-alert-dialog-content *hlmAlertDialogPortal="let ctx" data-testid="resume-dialog">
          @let paused = pausedSwitch()?.pausedTriggers ?? [];
          <hlm-alert-dialog-header>
            <h2 hlmAlertDialogTitle>Resume {{ s.name }}?</h2>
            <p hlmAlertDialogDescription>
              Its accounts and credentials work again and waiting turns go on.
              @if (!paused.length) {
                Its pause paused no trigger.
              }
            </p>
          </hlm-alert-dialog-header>
          @if (paused.length) {
            <div class="flex flex-col gap-2 text-sm">
              <label class="flex items-start gap-2">
                <input
                  type="checkbox"
                  class="accent-primary mt-0.5 size-4 shrink-0"
                  data-testid="resume-triggers"
                  [checked]="resumeTriggers()"
                  (change)="resumeTriggers.set($any($event.target).checked)"
                />
                <span>{{
                  paused.length === 1
                    ? 'Resume its trigger too'
                    : 'Resume its ' + paused.length + ' triggers too'
                }}</span>
              </label>
              <ul class="text-muted-foreground flex list-disc flex-col gap-0.5 ps-11">
                @for (trigger of paused; track trigger.id) {
                  <li>
                    {{ trigger.name }}
                    @if (trigger.authorName) {
                      · {{ trigger.authorName }}
                    }
                  </li>
                }
              </ul>
              <p class="text-muted-foreground text-xs text-pretty">
                Each resumes from now on: nothing of the pause fires. One left out stays paused
                until its author or an admin resumes it.
              </p>
            </div>
          }
          @if (resumeError(); as message) {
            <div hlmAlert variant="destructive">
              <ng-icon name="lucideCircleAlert" aria-hidden="true" />
              <p hlmAlertDescription>{{ message }}</p>
            </div>
          }
          <hlm-alert-dialog-footer>
            <button hlmAlertDialogCancel [disabled]="resumeBusy()">Cancel</button>
            <button
              hlmAlertDialogAction
              data-testid="confirm-resume"
              [disabled]="resumeBusy()"
              (click)="resume()"
            >
              @if (resumeBusy()) {
                <hlm-spinner aria-hidden="true" />
              }
              Resume
            </button>
          </hlm-alert-dialog-footer>
        </hlm-alert-dialog-content>
      </hlm-alert-dialog>
    } @else {
      <div class="flex flex-col gap-4" aria-busy="true">
        <span class="sr-only" role="status">Loading…</span>
        <hlm-skeleton class="h-4 w-24" />
        <hlm-skeleton class="h-8 w-64 max-w-full" />
        <hlm-skeleton class="h-16 w-full rounded-xl" />
        <hlm-skeleton class="h-40 w-full rounded-xl" />
      </div>
    }
  `,
})
export class SystemPage {
  private readonly api = inject(Api);
  private readonly router = inject(Router);
  private readonly notify = inject(Notify);
  private readonly current = inject(CurrentCompany);
  private readonly killSwitches = inject(KillSwitchState);
  private readonly document = inject(DOCUMENT);

  /** Wide enough for the header's buttons; narrower, they are a bottom sheet behind ⋯. */
  protected readonly wide = mediaQuery('(min-width: 40rem)', true);
  protected readonly isTracker = isTracker;

  /** The route's `:systemId`. */
  readonly systemId = input.required<string>();
  /** The `tab` query parameter; Overview without one. */
  readonly tab = input<string | undefined>();

  protected readonly companyId = this.current.id;
  private readonly key = computed(
    () => {
      const companyId = this.current.id();
      return companyId ? { companyId, systemId: this.systemId() } : undefined;
    },
    { equal: sameParams },
  );
  protected readonly systemRes = resource({
    params: () => this.key(),
    loader: ({ params }) => this.api.invoke(getSystem, params),
  });
  /** The system as last read or changed here. */
  protected readonly system = linkedSignal<ExternalSystem | undefined>(() => {
    const value = this.systemRes.hasValue() ? this.systemRes.value() : undefined;
    return value?.id === this.systemId() ? value : undefined;
  });
  protected readonly notFound = computed(() => toProblem(this.systemRes.error()).status === 404);

  protected readonly kind = computed(() => systemKind(this.system()?.kind ?? 'MCP'));
  protected readonly kindIcon = computed(() => {
    const kind = this.system()?.kind ?? 'MCP';
    return kind === 'WEBHOOK' ? 'lucideWebhook' : systemKindIcon(kind);
  });
  /** What the system receives from agents, under its name; null for one without tools. */
  protected readonly receivesData = computed(() => {
    const system = this.system();
    return system ? receivesDataSentence(system) : null;
  });
  /** A Slack or Teams system: its bot, app and secrets (package 5a). */
  protected readonly chat = computed(() => isChatKind(this.system()?.kind ?? 'MCP'));
  /** Something to reach and test: a base URL, and not a webhook, which is reached instead. */
  protected readonly testable = computed(() => {
    const system = this.system();
    return !!system?.baseUrl && system.kind !== 'WEBHOOK';
  });
  protected readonly canManage = computed(() => {
    const system = this.system();
    return !!system && managesSystem(system, managesCompany(this.current.access()));
  });
  /** A company system's kill switch, OWNER and ADMIN; a personal server is stopped otherwise. */
  protected readonly canPause = computed(
    () => this.canManage() && !this.system()?.personal && managesCompany(this.current.access()),
  );
  /** A company system's credentials (ADR-0072), for OWNER and ADMIN; a personal server holds none. */
  protected readonly credentialsShown = computed(() => {
    const system = this.system();
    return !!system && this.canPause() && holdsCredentials(system);
  });
  /** A personal server's page is reached from Accounts; a company system's from Systems. */
  protected readonly back = computed(() =>
    this.system()?.personal && !managesCompany(this.current.access())
      ? {
          label: 'Accounts',
          link: ['/c', this.companyId(), 'accounts'],
          query: { tab: 'systems' },
        }
      : { label: 'Systems', link: ['/c', this.companyId(), 'systems'], query: {} },
  );

  // --- Tabs -----------------------------------------------------------------------------------

  /** What each tab counts, as its section says once read; empty again for another system. */
  protected readonly repositoryCount = linkedSignal<string, string | null>({
    source: this.systemId,
    computation: () => null,
  });
  protected readonly tools = linkedSignal<string, ToolsSummary | null>({
    source: this.systemId,
    computation: () => null,
  });
  protected readonly triggerCount = linkedSignal<string, number | null>({
    source: this.systemId,
    computation: () => null,
  });
  protected readonly channelCount = linkedSignal<string, number | null>({
    source: this.systemId,
    computation: () => null,
  });
  protected readonly deliveries = linkedSignal<string, DeliveriesSummary | null>({
    source: this.systemId,
    computation: () => null,
  });
  /** The system's credentials, as its credentials section read them; null until then. */
  protected readonly credentials = linkedSignal<string, readonly SystemCredential[] | null>({
    source: this.systemId,
    computation: () => null,
  });

  protected readonly tabs = computed(() => {
    const system = this.system();
    return system ? systemTabs(system, this.canManage()) : [];
  });
  protected readonly tabRows = computed(() =>
    this.tabs().map((id) => ({
      id,
      label: TAB_LABELS[id],
      count: this.countOf(id),
      toConfirm: id === 'tools' && this.canManage() ? (this.tools()?.toConfirm ?? 0) : 0,
    })),
  );
  protected readonly activeTab = computed<SystemTab>(() => {
    const wanted = this.tab() as SystemTab | undefined;
    return wanted && this.tabs().includes(wanted) ? wanted : 'overview';
  });
  protected readonly triggerSource = computed(() =>
    this.chat() ? 'chat' : this.system()?.kind === 'WEBHOOK' ? 'webhook' : 'tracker',
  );

  private readonly tablist = viewChild<ElementRef<HTMLElement>>('tablist');
  private readonly appCard = viewChild(SystemApp);
  protected readonly credentialsCard = viewChild(SystemCredentials);
  private readonly botCard = viewChild(ChatBot);
  private readonly webhookCard = viewChild(WebhookSetup);

  constructor() {
    // On a phone the tabs scroll sideways: the open one is kept in view.
    afterRenderEffect(() => {
      this.activeTab();
      const row = this.tablist()?.nativeElement;
      const open = row?.querySelector<HTMLElement>('[aria-selected=true]');
      if (!row || !open || row.scrollWidth <= row.clientWidth) return;
      const box = row.getBoundingClientRect();
      const pill = open.getBoundingClientRect();
      if (pill.left < box.left || pill.right > box.right) {
        row.scrollLeft += pill.left - box.left - 16;
      }
    });
  }

  private countOf(id: SystemTab): string | null {
    switch (id) {
      case 'repositories':
        return this.repositoryCount();
      case 'tools':
        return this.tools() ? String(this.tools()!.count) : null;
      case 'triggers':
        return this.triggerCount() === null ? null : String(this.triggerCount());
      case 'channels':
        return this.channelCount() === null ? null : String(this.channelCount());
      case 'deliveries':
        return this.deliveries()?.count ?? null;
      default:
        return null;
    }
  }

  protected openTab(id: SystemTab): void {
    void this.router.navigate([], {
      queryParams: { tab: id === 'overview' ? null : id },
      queryParamsHandling: 'merge',
      replaceUrl: true,
    });
  }

  protected onTabKeys(event: KeyboardEvent): void {
    const to = tabKeyTarget(event.key, this.tabs(), this.activeTab()) as SystemTab | null;
    if (!to) return;
    event.preventDefault();
    this.openTab(to);
    this.document.getElementById(`system-tab-${to}`)?.focus();
  }

  // --- Health ---------------------------------------------------------------------------------

  /**
   * The strip's first cell: the last test as the server keeps it (when, by whom for whoever
   * manages the system, and how it ended), the error or the pause; a webhook's last delivery and
   * its last 7 days.
   */
  protected readonly healthA = computed(() => {
    const system = this.system();
    if (!system) return false;
    if (system.kind === 'WEBHOOK') return this.canManage() || !!system.paused;
    return this.testable() || !!system.paused;
  });
  /** Its second: how accounts connect (the app), a chat's bot, a webhook's secret. */
  protected readonly healthB = computed(() => {
    const system = this.system();
    if (!system) return false;
    if (system.kind === 'WEBHOOK') return !!system.webhook;
    return this.chat() || this.kind().app;
  });
  /** The paused system's switch: who paused it, when and why (admins read the switches). */
  protected readonly pausedSwitch = computed<KillSwitch | null>(() => {
    const system = this.system();
    const state = this.killSwitches.switches;
    if (!system?.paused || !state.hasValue()) return null;
    return (
      state.value()?.switches.find((k) => k.kind === 'SYSTEM_PAUSED' && k.targetId === system.id) ??
      null
    );
  });
  protected readonly secretOld = computed(() => secretDue(this.system()?.webhook?.secretSetAt));
  protected readonly weekLine = weekLine;
  /**
   * The system's stored last test (V043), while its status is still that test's outcome: a
   * catalogue's refresh since, which sets the status too, leaves the strip to the status.
   */
  protected readonly storedTest = computed<SystemLastTest | null>(() => {
    const system = this.system();
    const last = system?.lastTest;
    if (!system || !last) return null;
    return (system.status === 'ERROR') === !last.ok ? last : null;
  });

  /** The stored test's outcome after its time and tester: "passed with their own account". */
  protected lastTestSentence(last: SystemLastTest): string {
    return lastTestSentence(last, this.chat());
  }

  /** The round icon at the start of a health strip's cell, in its tone. */
  protected dot(tone: keyof typeof DOT_TONES): string {
    return `flex size-7 shrink-0 items-center justify-center rounded-full ${DOT_TONES[tone]}`;
  }

  protected deliveryLabel(delivery: WebhookDelivery): string {
    const label = deliveryStatus(delivery).label;
    return label.charAt(0).toLowerCase() + label.slice(1);
  }

  protected setUpApp(): void {
    this.openTab('overview');
    this.appCard()?.openEdit();
  }

  protected setBot(): void {
    this.openTab('bot');
    this.botCard()?.openSet();
  }

  protected rotateSecret(): void {
    this.webhookCard()?.openRotate();
  }

  /** "every minute" as the row's value: "Every minute". */
  protected pollPhrase(seconds: number): string {
    const phrase = pollIntervalPhrase(seconds);
    return phrase.charAt(0).toUpperCase() + phrase.slice(1);
  }

  // --- Test ---------------------------------------------------------------------------------

  protected readonly testing = signal(false);
  protected readonly testResult = linkedSignal<string, SystemTestResult | null>({
    source: this.systemId,
    computation: () => null,
  });
  protected readonly testedAt = signal('');
  protected readonly usualAccount = 'Your own account, else the only working credential, else none';
  /** The credentials a test may name, when there is a choice: not a chat's bot. */
  protected readonly testCredentials = computed(() =>
    this.chat() ? [] : (this.credentials() ?? []).filter((c) => !c.bot),
  );
  /**
   * A test that reached a system without any account, which asked for one (`credentialRequired`),
   * where none of the company's credentials works yet: the page offers to add one there (ADR-0072,
   * decision 10). With several working ones, the test picks one instead.
   */
  protected readonly offerCredential = computed(() => {
    const result = this.testResult();
    if (!result || result.withAccount || !result.credentialRequired) return false;
    if (!this.credentialsShown() || this.credentials() === null) return false;
    return !(this.credentials() ?? []).some((c) => !c.bot && c.enabled && c.status === 'OK');
  });

  /**
   * An Add dialog the health strip asked for, which opens once the Overview's credentials are
   * rendered: the strip shows above every tab.
   */
  private readonly addRequested = signal(false);

  private readonly openRequestedAdd = effect(() => {
    const card = this.credentialsCard();
    if (!this.addRequested() || !card) return;
    untracked(() => {
      this.addRequested.set(false);
      card.openAdd();
    });
  });

  /** Opens the credentials' Add dialog, from the health strip, whichever tab is open. */
  protected addCredential(): void {
    this.addRequested.set(true);
    this.openTab('overview');
  }

  /** The credential the next test names; empty: the server's choice. */
  protected readonly testCredentialId = linkedSignal<readonly SystemCredential[], string>({
    source: this.testCredentials,
    computation: (credentials, previous) => {
      const kept = previous?.value ?? '';
      return credentials.some((c) => c.id === kept) ? kept : '';
    },
  });
  /** "with your own account", "with Server token": what the running test reaches it with. */
  protected readonly testingWith = computed(() => {
    const id = this.testCredentialId();
    const named = id ? this.testCredentials().find((c) => c.id === id) : undefined;
    return named ? `with ${named.name}` : 'with your own account or its credential';
  });

  /** The test's outcome in one sentence: who it reached it as, or why not. */
  protected testSentence(result: SystemTestResult): string {
    return testSentence(result, this.chat());
  }

  protected async test(): Promise<void> {
    const key = this.key();
    if (!key || this.testing()) return;
    const credentialId = this.testCredentialId();
    this.testing.set(true);
    this.testResult.set(null);
    try {
      const result = await this.api.invoke(testSystem, {
        ...key,
        ...(credentialId ? { credentialId } : {}),
      });
      this.testedAt.set(new Date().toISOString());
      this.testResult.set(result);
      // The outcome is the system's status now.
      this.systemRes.reload();
    } catch (error) {
      this.notify.error("Couldn't test the system", error, SYSTEM_PAGE_MESSAGES);
    } finally {
      this.testing.set(false);
    }
  }

  // --- The phone's sheet ----------------------------------------------------------------------

  protected readonly sheetOpen = signal(false);
  /** "Ready · tested 14:12", what the sheet says under the name. */
  protected readonly sheetLine = computed(() => {
    const system = this.system();
    if (!system) return '';
    if (system.paused) return 'Paused';
    if (!this.testable()) return this.kind().label;
    return { OK: 'Ready', ERROR: 'Error', NEW: 'Not tested yet' }[system.status];
  });

  protected closeSheet(): void {
    this.sheetOpen.set(false);
  }

  /** An action of the sheet, once the sheet is out of the way. */
  protected fromSheet(action: 'test' | 'edit' | 'pause' | 'resume' | 'remove'): void {
    this.sheetOpen.set(false);
    switch (action) {
      case 'test':
        void this.test();
        return;
      case 'edit':
        this.editOpen.set(true);
        return;
      case 'pause':
        this.openPause();
        return;
      case 'resume':
        this.openResume();
        return;
      default:
        this.openDelete();
    }
  }

  // --- Change -------------------------------------------------------------------------------

  protected readonly editOpen = signal(false);

  protected onChanged(system: ExternalSystem): void {
    this.system.set(system);
    this.notify.success(`${system.name} is saved`);
  }

  // --- Pause and resume ---------------------------------------------------------------------

  protected readonly pauseOpen = signal(false);
  protected readonly pauseBusy = signal(false);
  protected readonly pauseError = signal<string | null>(null);
  protected readonly pauseStopRunning = signal(false);
  protected readonly pauseDescription =
    'Its accounts and credentials act for nothing until you resume it: agents get none of its tools, turns that need it wait, and every trigger that uses it pauses. Testing and connecting accounts wait too.';

  protected openPause(): void {
    this.pauseError.set(null);
    this.pauseStopRunning.set(false);
    this.pauseOpen.set(true);
  }

  protected async pause(reason: string): Promise<void> {
    const key = this.key();
    const system = this.system();
    if (!key || !system || this.pauseBusy()) return;
    this.pauseBusy.set(true);
    this.pauseError.set(null);
    try {
      const result = await this.api.invoke(pauseSystem, {
        ...key,
        body: { reason, stopRunning: this.pauseStopRunning() },
      });
      this.pauseOpen.set(false);
      const parts = [];
      if (result.runsCancelled) parts.push(`${plural(result.runsCancelled, 'turn')} cancelled`);
      if (result.triggersPaused) parts.push(`${plural(result.triggersPaused, 'trigger')} paused`);
      this.notify.success(
        `${system.name} is paused`,
        parts.length ? `${parts.join(', ')}.` : undefined,
      );
      this.systemRes.reload();
      this.killSwitches.reload();
    } catch (error) {
      this.pauseError.set(problemMessage(error, SYSTEM_PAGE_MESSAGES));
    } finally {
      this.pauseBusy.set(false);
    }
  }

  protected readonly resumeOpen = signal(false);
  protected readonly resumeBusy = signal(false);
  protected readonly resumeError = signal<string | null>(null);
  protected readonly resumeTriggers = signal(true);

  protected openResume(): void {
    this.resumeTriggers.set(true);
    this.resumeError.set(null);
    this.resumeOpen.set(true);
  }

  protected async resume(): Promise<void> {
    const key = this.key();
    const system = this.system();
    if (!key || !system || this.resumeBusy()) return;
    this.resumeBusy.set(true);
    this.resumeError.set(null);
    try {
      const paused = this.pausedSwitch()?.pausedTriggers?.length ?? 0;
      const result = await this.api.invoke(resumeSystem, {
        ...key,
        body: { resumeTriggers: paused ? this.resumeTriggers() : true },
      });
      this.resumeOpen.set(false);
      const parts = [];
      if (result.triggersResumed) {
        parts.push(`${plural(result.triggersResumed, 'trigger')} resumed`);
      }
      if (result.triggersStillPaused) {
        parts.push(
          `${plural(result.triggersStillPaused, 'trigger')} ${result.triggersStillPaused === 1 ? 'stays' : 'stay'} paused`,
        );
      }
      this.notify.success(
        `${system.name} is resumed`,
        parts.length ? `${parts.join(', ')}.` : undefined,
      );
      this.systemRes.reload();
      this.killSwitches.reload();
    } catch (error) {
      this.resumeError.set(problemMessage(error, SYSTEM_PAGE_MESSAGES));
    } finally {
      this.resumeBusy.set(false);
    }
  }

  // --- Remove -------------------------------------------------------------------------------

  protected readonly deleteOpen = signal(false);
  protected readonly deleteBusy = signal(false);
  protected readonly deleteError = signal<string | null>(null);
  /** The menu item's one line: what a removal does first. */
  protected readonly removeHint = computed(() => {
    const system = this.system();
    if (system?.kind === 'WEBHOOK') return 'Its URL stops taking deliveries';
    if (this.chat()) return 'Deletes its bot and secrets';
    return 'Disconnects every account and credential on it';
  });
  protected readonly removeEffects = computed(() => {
    const system = this.system();
    return system
      ? removalEffects(system, {
          credentials: this.credentials()?.length ?? null,
          repositories: this.repositoryCount(),
          tools: this.tools()?.count ?? null,
          triggers: this.triggerCount(),
        })
      : [];
  });

  protected openDelete(): void {
    this.deleteError.set(null);
    this.deleteOpen.set(true);
  }

  protected async confirmDelete(): Promise<void> {
    const key = this.key();
    const system = this.system();
    if (!key || !system || this.deleteBusy()) return;
    this.deleteBusy.set(true);
    this.deleteError.set(null);
    try {
      await this.api.invoke(deleteSystem, key);
      this.deleteOpen.set(false);
      this.notify.success(`${system.name} is removed`);
      const back = this.back();
      await this.router.navigate(back.link, { queryParams: back.query });
    } catch (error) {
      this.deleteError.set(problemMessage(error, SYSTEM_PAGE_MESSAGES));
    } finally {
      this.deleteBusy.set(false);
    }
  }
}

/**
 * "the credential CI bot": the system's credential a test used, by the name the server gives; null
 * for the tester's own account or none.
 */
function credentialPhrase(test: {
  credentialId?: string | null;
  credentialName?: string | null;
}): string | null {
  if (!test.credentialId) return null;
  return test.credentialName ? `the credential ${test.credentialName}` : 'one of its credentials';
}

/**
 * A stored test's outcome after "Tested 2 hours ago by Alex Rivera" (`SystemLastTest`): passed, and
 * whether with one of the system's credentials (by its name) or the tester's own account, or why it
 * failed and with which credential. A chat system's test checks its bot.
 */
export function lastTestSentence(last: SystemLastTest, chat = false): string {
  const credential = credentialPhrase(last);
  if (!last.ok) {
    const error = last.error?.trim() || 'no reason given';
    return credential && !chat ? `${error} (with ${credential})` : error;
  }
  if (chat) return 'passed';
  if (!last.withAccount) return 'passed without an account';
  if (credential) return `passed with ${credential}`;
  return last.byName ? 'passed with their own account' : 'passed with the tester’s own account';
}

/**
 * A test's outcome in one sentence (`SystemTestResult`): with whose account, as whom, or why not;
 * a credential by the name the server gives. A chat system's test checks its bot and secrets, and
 * the server's sentence says each (§15.16).
 */
export function testSentence(result: SystemTestResult, chat = false): string {
  const message = result.message.trim().replace(/\.?$/, '.');
  if (chat) return message;
  const credential = credentialPhrase(result);
  if (result.ok && result.withAccount) {
    const how = credential ?? 'your own account';
    return result.login ? `Reached with ${how}, as ${result.login}.` : `Reached with ${how}.`;
  }
  if (result.ok) {
    return result.credentialRequired
      ? `${message} Tested without an account, and it asks for one: you hold no working one on it, and it has no single working credential to use.`
      : `${message} Tested without an account: you hold no working one on it, and it has no single working credential to use.`;
  }
  if (!result.withAccount && credential) {
    // Nothing was sent: the credential named cannot be used.
    return `Not tested: ${credential} is disabled, or must be signed in again.`;
  }
  if (result.withAccount && result.credentialRequired) {
    // The system refused the account used: it is signed in again, or given a new token.
    return credential
      ? `${message} Store a new token for ${credential}, or sign it in again.`
      : `${message} Sign in again, or store a new token, on Accounts.`;
  }
  return result.withAccount ? message : `Not reached: ${message}`;
}
