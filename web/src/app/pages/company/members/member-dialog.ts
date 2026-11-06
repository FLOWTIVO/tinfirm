import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  linkedSignal,
  output,
  resource,
  signal,
  untracked,
} from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { NgIcon, provideIcons } from '@ng-icons/core';
import {
  lucideCheck,
  lucideCircleAlert,
  lucideCirclePause,
  lucideLock,
  lucideMinus,
  lucideTrash2,
  lucideX,
} from '@ng-icons/lucide';
import { HlmAlertImports } from '@spartan-ng/helm/alert';
import { HlmAlertDialogImports } from '@spartan-ng/helm/alert-dialog';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmDialogImports } from '@spartan-ng/helm/dialog';
import { HlmSkeleton } from '@spartan-ng/helm/skeleton';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { Api } from '../../../api/generated/api';
import {
  getPrincipalModelAccess,
  previewDeparture,
  removeMember,
  resumeMember,
  suspendMember,
  updateMember,
} from '../../../api/generated/functions';
import type { DeparturePreview, Member, Role } from '../../../api/generated/models';
import { problemMessage } from '../../../api/problem';
import { CurrentCompany } from '../../../core/current-company';
import { LicenceState } from '../../../core/licence';
import { Notify } from '../../../core/notify';
import {
  can,
  canModifyMember,
  ROLE_DESCRIPTIONS,
  ROLE_LABELS,
  ROLES,
} from '../../../core/permissions';
import { Session } from '../../../core/session';
import { initials } from '../../../shared/forms';
import { departurePreviewLines } from '../../../shared/governance-labels';
import { seatsFull } from '../../../shared/licence-labels';
import { ReasonDialog } from '../../../shared/reason-dialog';
import { MemberAccounts } from './member-accounts';
import { MemberGroups } from './member-groups';
import { MembersData } from './members-data';
import { addedOn, budgetLine, type RoleChange, roleChange } from './members-view';

/** A role as a card to pick: a radio (visually hidden) in a white sheet, green while picked. */
export const ROLE_CARD =
  'group bg-card has-checked:border-primary has-focus-visible:ring-ring/50 has-disabled:bg-muted hover:bg-accent has-disabled:hover:bg-muted flex w-full cursor-pointer items-start gap-3 rounded-[14px] border px-3.5 py-3 text-start has-checked:shadow-[inset_0_0_0_1px_var(--primary)] has-focus-visible:ring-3 has-disabled:cursor-not-allowed has-disabled:has-checked:border-input has-disabled:has-checked:shadow-none';
/** The card's radio dot, drawn for the hidden radio. */
export const ROLE_DOT =
  "border-input group-has-checked:border-primary group-has-disabled:group-has-checked:border-faint group-has-checked:after:bg-primary group-has-disabled:group-has-checked:after:bg-faint mt-0.5 flex size-[18px] shrink-0 items-center justify-center rounded-full border-[1.5px] after:size-2 after:rounded-full after:content-['']";

/** A section of the dialog's body, with a hairline above all but the first. */
const SECTION = 'flex flex-col gap-3 px-4 pt-[18px] pb-5 sm:px-5';
const NEXT_SECTION = `${SECTION} border-t`;

/**
 * One member, opened from the list in a dialog (plan §5; the app opens dialogs, never side
 * sheets): who they are, their role as three cards, their accounts per system (status only, I4),
 * their groups, their budget, and Suspend, Resume and Remove at the foot, each saying what happens.
 * Picking a role only selects it; Save asks first, saying what the role grants or takes away. The
 * only owner keeps the role, and only an owner makes someone an owner. Full screen on a phone.
 */
@Component({
  selector: 'app-member-dialog',
  imports: [
    RouterLink,
    NgIcon,
    HlmAlertImports,
    HlmAlertDialogImports,
    HlmButton,
    HlmDialogImports,
    HlmSkeleton,
    HlmSpinner,
    MemberAccounts,
    MemberGroups,
    ReasonDialog,
  ],
  providers: [
    provideIcons({
      lucideCheck,
      lucideCircleAlert,
      lucideCirclePause,
      lucideLock,
      lucideMinus,
      lucideTrash2,
      lucideX,
    }),
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <!-- A click outside a confirmation over it would close this one too: Close and Escape do. -->
    <hlm-dialog
      [state]="member() ? 'open' : 'closed'"
      [closeOnOutsidePointerEvents]="false"
      (closed)="closed.emit()"
    >
      <hlm-dialog-content
        *hlmDialogPortal="let ctx"
        [showCloseButton]="false"
        class="flex max-h-[min(88svh,56rem)] flex-col gap-0 overflow-hidden p-0 sm:max-w-[40rem] max-sm:h-svh max-sm:max-h-none max-sm:w-screen max-sm:max-w-none max-sm:rounded-none max-sm:ring-0"
        data-testid="member-dialog"
      >
        @if (shown(); as m) {
          @let self = m.userId === myId();
          <header class="flex shrink-0 items-start gap-3 border-b py-4 ps-4 pe-3 sm:ps-5">
            <span
              class="flex size-11 shrink-0 items-center justify-center rounded-[10px] text-sm font-bold"
              [class]="m.suspended ? 'bg-muted text-faint' : 'bg-secondary text-foreground'"
              aria-hidden="true"
              >{{ initialsOf(m.displayName) }}</span
            >
            <div class="min-w-0 flex-1">
              <h2
                hlmDialogTitle
                class="flex flex-wrap items-center gap-x-2 gap-y-1 text-[1.0625rem] leading-snug font-bold"
              >
                <span class="min-w-0 break-words">{{ m.displayName }}</span>
                @if (self) {
                  <span
                    class="bg-secondary text-muted-foreground inline-flex h-[22px] items-center rounded-full px-2.5 text-xs font-semibold"
                    >You</span
                  >
                }
                @if (m.suspended) {
                  <span
                    class="bg-warning/10 text-warning inline-flex h-[22px] items-center gap-1 rounded-full px-2.5 text-xs font-semibold"
                    data-testid="member-dialog-suspended"
                    ><ng-icon
                      name="lucideCirclePause"
                      size="12"
                      aria-hidden="true"
                    />Suspended</span
                  >
                }
              </h2>
              <p class="text-muted-foreground m-0 truncate text-[0.8125rem]">
                {{ m.email }} · added {{ added(m.addedAt) }}
              </p>
            </div>
            <button
              hlmBtn
              variant="ghost"
              size="icon"
              type="button"
              class="text-muted-foreground shrink-0 max-sm:size-11"
              aria-label="Close"
              hlmDialogClose
            >
              <ng-icon name="lucideX" aria-hidden="true" />
            </button>
          </header>

          <div class="flex min-h-0 flex-1 flex-col overflow-y-auto">
            <section [class]="sectionClass" aria-labelledby="member-role-title">
              <div>
                <h3 id="member-role-title" class="text-sm font-bold">Role</h3>
                <p class="text-muted-foreground mt-0.5 text-[0.8125rem] text-pretty">
                  What {{ m.displayName }} may do in {{ companyName() ?? 'the company' }}.
                  @if (editable()) {
                    A change asks you first.
                  }
                </p>
              </div>
              <div
                class="flex flex-col gap-2"
                role="radiogroup"
                aria-labelledby="member-role-title"
                data-testid="role-cards"
              >
                @for (card of roleCards(); track card.role) {
                  <label [class]="roleCard" [attr.data-testid]="'role-card-' + card.role">
                    <input
                      type="radio"
                      class="sr-only"
                      [name]="'member-role-' + m.userId"
                      [value]="card.role"
                      [checked]="card.checked"
                      [disabled]="card.locked"
                      [attr.aria-labelledby]="'member-role-' + card.role + '-name'"
                      [attr.aria-describedby]="
                        'member-role-' +
                        card.role +
                        '-line' +
                        (card.reason ? ' member-role-' + card.role + '-reason' : '')
                      "
                      (change)="pick(card.role)"
                    />
                    <span [class]="roleDot" aria-hidden="true"></span>
                    <span class="flex min-w-0 flex-1 flex-col">
                      <span
                        class="flex flex-wrap items-baseline gap-x-2 text-sm font-semibold"
                        [class.text-muted-foreground]="card.locked && !card.checked"
                        [id]="'member-role-' + card.role + '-name'"
                      >
                        {{ card.label }}
                        @if (card.was) {
                          <span
                            class="text-muted-foreground text-xs font-medium"
                            data-testid="role-was"
                            >was {{ card.label }}</span
                          >
                        }
                      </span>
                      <span
                        class="text-muted-foreground text-[0.8125rem] leading-normal text-pretty"
                        [id]="'member-role-' + card.role + '-line'"
                        >{{ card.description }}</span
                      >
                      @if (card.reason) {
                        <span
                          class="mt-1.5 flex items-center gap-1.5 text-xs font-semibold"
                          [id]="'member-role-' + card.role + '-reason'"
                          [attr.data-testid]="card.onlyOwner ? 'only-owner' : 'role-locked'"
                        >
                          <ng-icon name="lucideLock" size="12" aria-hidden="true" />{{
                            card.reason
                          }}
                        </span>
                      }
                    </span>
                  </label>
                }
              </div>
              @if (m.roleManagedBy; as manager) {
                <p
                  class="text-muted-foreground m-0 flex items-center gap-1.5 text-xs font-semibold text-pretty"
                  data-testid="role-managed"
                >
                  <ng-icon name="lucideLock" size="12" aria-hidden="true" />Managed by
                  {{ manager.name }}: the role changes there, at {{ m.displayName }}’s next
                  {{ manager.source === 'SCIM' ? 'update' : 'sign-in' }}. Making them an owner makes
                  it Tinfirm’s.
                </p>
              }
              @if (editable() && m.role === 'VIEWER' && noSeatLeft() && !m.roleManagedBy) {
                <p class="text-warning m-0 text-xs text-pretty" data-testid="role-no-seat">
                  Every seat of the licence is taken: {{ m.displayName }} can become an Owner, Admin
                  or Member once someone else becomes a Viewer, or a key with more seats is entered.
                </p>
              }
            </section>

            @if (canManage()) {
              <section [class]="nextSectionClass" aria-labelledby="member-accounts-title">
                <div>
                  <h3 id="member-accounts-title" class="text-sm font-bold">Accounts</h3>
                  <p class="text-muted-foreground mt-0.5 text-[0.8125rem] text-pretty">
                    Which company systems {{ m.displayName }} holds an account on, and its status;
                    nothing more.
                  </p>
                </div>
                <app-member-accounts [userId]="m.userId" [name]="m.displayName" />
              </section>
            }

            @if (canManageGroups()) {
              <section [class]="nextSectionClass" aria-labelledby="member-groups-title">
                <div>
                  <h3 id="member-groups-title" class="text-sm font-bold">Groups</h3>
                  <p class="text-muted-foreground mt-0.5 text-[0.8125rem] text-pretty">
                    What else {{ m.displayName }} may use comes from groups: the agents shared with
                    them and the accounts lent to them. Changes save at once.
                  </p>
                </div>
                <app-member-groups [userId]="m.userId" [name]="m.displayName" />
              </section>
            }

            @if (canManage()) {
              <section [class]="nextSectionClass" aria-labelledby="member-budget-title">
                <div>
                  <h3 id="member-budget-title" class="text-sm font-bold">Budget</h3>
                  <p class="text-muted-foreground mt-0.5 text-[0.8125rem]">
                    What the conversations {{ m.displayName }} starts may spend on models.
                  </p>
                </div>
                <div
                  class="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-1 rounded-xl border px-3.5 py-3"
                  data-testid="member-budget"
                >
                  @if (budget.error()) {
                    <span class="text-muted-foreground text-sm">Couldn’t read the budget.</span>
                    <button
                      type="button"
                      class="text-primary text-[0.8125rem] font-semibold underline-offset-2 hover:underline"
                      (click)="budget.reload()"
                    >
                      Try again
                    </button>
                  } @else if (budget.isLoading() && !budget.hasValue()) {
                    <hlm-skeleton class="h-4 w-56" />
                  } @else if (budgetText(); as b) {
                    <span class="text-sm"
                      >{{ b.whose }},
                      <span class="font-mono text-[0.8125rem]">{{ b.amount }}</span> per
                      {{ b.period }}
                      @if (b.resets) {
                        <span class="text-muted-foreground"> · resets {{ b.resets }}</span>
                      }
                    </span>
                  } @else {
                    <span class="text-muted-foreground text-sm">Set under Model access.</span>
                  }
                  <a
                    class="text-primary inline-flex items-center text-[0.8125rem] font-semibold underline-offset-2 hover:underline max-sm:min-h-11"
                    [routerLink]="['/c', companyId(), 'model-access']"
                    >Change under Model access</a
                  >
                </div>
              </section>
            }

            @if (canManage()) {
              <section
                class="bg-muted/70 mt-auto flex flex-col border-t px-4 pt-1.5 pb-2.5 sm:px-5"
                aria-labelledby="member-end-title"
                data-testid="member-end"
              >
                <h3 id="member-end-title" class="sr-only">Suspend or remove</h3>
                @if (endLock(); as text) {
                  <p class="text-muted-foreground m-0 flex gap-2 py-3 text-[0.8125rem]">
                    <ng-icon
                      name="lucideLock"
                      size="14"
                      class="mt-[3px] shrink-0"
                      aria-hidden="true"
                    />
                    <span>{{ text }}</span>
                  </p>
                }
                @if (editable() && !self) {
                  <div class="flex flex-col gap-2 py-3 sm:flex-row sm:items-start sm:gap-4">
                    <div class="min-w-0 flex-1">
                      <div class="text-sm font-semibold">
                        {{ m.suspended ? 'Resume' : 'Suspend' }}
                      </div>
                      <p class="text-muted-foreground mt-0.5 text-[0.8125rem] text-pretty">
                        @if (m.suspended) {
                          {{ m.displayName }} is suspended. Resumed, their waiting turns go on; the
                          triggers they wrote stay paused until resumed.
                        } @else {
                          Until resumed, nothing acts as {{ m.displayName }} or with their accounts
                          and budget. Their running turns are cancelled and their triggers pause.
                        }
                      </p>
                    </div>
                    @if (m.suspended) {
                      <button
                        hlmBtn
                        variant="outline"
                        size="sm"
                        type="button"
                        class="self-start max-sm:h-11 max-sm:px-4"
                        data-testid="resume-member"
                        [disabled]="statusBusy()"
                        (click)="resume(m)"
                      >
                        @if (statusBusy()) {
                          <hlm-spinner aria-hidden="true" />
                        }
                        Resume
                      </button>
                    } @else {
                      <button
                        hlmBtn
                        variant="outline"
                        size="sm"
                        type="button"
                        class="self-start max-sm:h-11 max-sm:px-4"
                        aria-haspopup="dialog"
                        data-testid="suspend-member"
                        (click)="askSuspend()"
                      >
                        Suspend…
                      </button>
                    }
                  </div>
                } @else if (editable() && self) {
                  <div class="py-3">
                    <div class="text-sm font-semibold">Suspend</div>
                    <p class="text-muted-foreground mt-0.5 text-[0.8125rem]">
                      You cannot suspend yourself. Another admin can.
                    </p>
                  </div>
                }
                @if (editable()) {
                  <div
                    class="flex flex-col gap-2 border-t py-3 sm:flex-row sm:items-start sm:gap-4"
                  >
                    <div class="min-w-0 flex-1">
                      <div class="text-sm font-semibold">
                        Remove from {{ companyName() ?? 'the company' }}
                      </div>
                      <p class="text-muted-foreground mt-0.5 text-[0.8125rem] text-pretty">
                        @if (self) {
                          You lose access to {{ companyName() ?? 'the company' }} right away.
                        } @else {
                          {{ m.displayName }} loses access right away; added again later, they start
                          afresh. A checklist of what is left behind opens next.
                        }
                      </p>
                    </div>
                    <button
                      hlmBtn
                      variant="destructive"
                      size="sm"
                      type="button"
                      class="self-start max-sm:h-11 max-sm:px-4"
                      aria-haspopup="dialog"
                      data-testid="remove-member"
                      (click)="askRemove(m)"
                    >
                      <ng-icon name="lucideTrash2" aria-hidden="true" />
                      Remove…
                    </button>
                  </div>
                }
              </section>
            }
          </div>

          @if (pending(); as to) {
            <div
              class="bg-card flex shrink-0 flex-wrap items-center gap-x-3 gap-y-2 border-t py-3 ps-4 pe-3 sm:ps-5"
              role="region"
              aria-label="Unsaved changes"
              data-testid="role-footer"
            >
              <p class="m-0 min-w-0 flex-1 text-sm font-semibold">
                Role changes to {{ roleLabels[to] }}
              </p>
              <button
                hlmBtn
                variant="ghost"
                type="button"
                class="max-sm:h-11"
                (click)="pending.set(null)"
              >
                Cancel
              </button>
              <button
                hlmBtn
                type="button"
                class="max-sm:h-11"
                aria-haspopup="dialog"
                data-testid="save-role"
                (click)="askRole(m, to)"
              >
                Save
              </button>
            </div>
          }
        }
      </hlm-dialog-content>
    </hlm-dialog>

    <hlm-alert-dialog [state]="roleOpen() ? 'open' : 'closed'" (closed)="roleOpen.set(false)">
      <hlm-alert-dialog-content
        *hlmAlertDialogPortal="let ctx"
        class="sm:max-w-md"
        data-testid="role-dialog"
      >
        @if (confirming(); as c) {
          <hlm-alert-dialog-header>
            <h2 hlmAlertDialogTitle>{{ c.title }}</h2>
            <p hlmAlertDialogDescription>It applies at once.</p>
          </hlm-alert-dialog-header>
          <div class="flex flex-col gap-1.5 text-sm">
            <p class="m-0 font-semibold">{{ c.lead }}</p>
            <ul class="m-0 flex list-none flex-col gap-1.5 p-0" data-testid="role-lines">
              @for (line of c.lines; track line.text) {
                <li class="flex gap-2 leading-normal">
                  <ng-icon
                    [name]="line.keep ? 'lucideCheck' : 'lucideMinus'"
                    size="14"
                    class="mt-1 shrink-0"
                    [class]="line.keep ? 'text-primary' : 'text-destructive'"
                    aria-hidden="true"
                  />
                  <span
                    ><span class="sr-only">{{ line.keep ? 'Keeps or gains: ' : 'Loses: ' }}</span
                    >{{ line.text }}</span
                  >
                </li>
              }
            </ul>
          </div>
          <hlm-alert-dialog-footer>
            <button hlmAlertDialogCancel [disabled]="roleBusy()">Cancel</button>
            <button
              hlmAlertDialogAction
              [variant]="c.lowering ? 'destructive' : 'default'"
              data-testid="confirm-role"
              [disabled]="roleBusy()"
              (click)="applyRole()"
            >
              @if (roleBusy()) {
                <hlm-spinner aria-hidden="true" />
              }
              {{ c.confirm }}
            </button>
          </hlm-alert-dialog-footer>
        }
      </hlm-alert-dialog-content>
    </hlm-alert-dialog>

    <app-reason-dialog
      testId="suspend-member-dialog"
      [heading]="'Suspend ' + (suspending()?.displayName ?? '') + '?'"
      description="Nothing acts as them, with their accounts or on their budget, and they write, decide and read nothing in this company until you resume them; their running turns are cancelled and their tokens stop working here. The triggers they wrote pause."
      confirmLabel="Suspend"
      note="They see it while suspended. It is kept with the switch, never in the audit log."
      [open]="suspendOpen()"
      [busy]="suspendBusy()"
      [error]="suspendError()"
      (confirm)="suspend($event)"
      (closed)="suspendOpen.set(false)"
    />

    <hlm-alert-dialog [state]="removeOpen() ? 'open' : 'closed'" (closed)="removeOpen.set(false)">
      <hlm-alert-dialog-content *hlmAlertDialogPortal="let ctx">
        <hlm-alert-dialog-header>
          <h2 hlmAlertDialogTitle>Remove {{ removing()?.displayName }}?</h2>
          <p hlmAlertDialogDescription>
            @if (removing()?.userId === myId()) {
              You will lose access to {{ companyName() ?? 'this company' }} right away.
            } @else {
              They lose access to {{ companyName() ?? 'this company' }} right away. Added again
              later, they start afresh: nothing of before comes back.
            }
          </p>
        </hlm-alert-dialog-header>
        @if (removing()?.userId !== myId()) {
          <div
            class="flex flex-col gap-1.5 text-sm"
            aria-live="polite"
            data-testid="departure-preview"
          >
            @if (previewLoading()) {
              <p class="text-muted-foreground flex items-center gap-2">
                <hlm-spinner aria-hidden="true" class="size-4" /> Checking what this changes…
              </p>
            } @else if (previewError(); as message) {
              <p class="text-muted-foreground">{{ message }}</p>
            } @else if (previewLines().length) {
              <p class="font-medium">What else happens</p>
              <ul class="text-muted-foreground list-disc ps-5">
                @for (line of previewLines(); track line) {
                  <li>{{ line }}</li>
                }
              </ul>
              <p class="text-muted-foreground text-xs">
                A checklist of what is left to take over opens next.
              </p>
            } @else if (preview()) {
              <p class="text-muted-foreground">Nothing else of theirs needs someone.</p>
            }
          </div>
        }
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
            [disabled]="removeBusy()"
            (click)="confirmRemove()"
          >
            @if (removeBusy()) {
              <hlm-spinner aria-hidden="true" />
            }
            Remove member
          </button>
        </hlm-alert-dialog-footer>
      </hlm-alert-dialog-content>
    </hlm-alert-dialog>
  `,
})
export class MemberDialog {
  private readonly api = inject(Api);
  private readonly router = inject(Router);
  private readonly notify = inject(Notify);
  private readonly current = inject(CurrentCompany);
  private readonly session = inject(Session);
  private readonly data = inject(MembersData);

  /** The member opened; null closes the dialog. */
  readonly member = input<Member | null>(null);
  readonly closed = output<void>();

  protected readonly sectionClass = SECTION;
  protected readonly roleCard = ROLE_CARD;
  protected readonly roleDot = ROLE_DOT;
  protected readonly nextSectionClass = NEXT_SECTION;
  protected readonly roleLabels = ROLE_LABELS;
  protected readonly companyId = this.current.id;
  protected readonly companyName = this.current.name;
  protected readonly myId = computed(() => this.session.user()?.id);
  protected readonly canManage = this.data.canManage;
  protected readonly canManageGroups = this.data.canManageGroups;

  /** The member shown: kept while the dialog closes, so its text does not blank out. */
  protected readonly shown = linkedSignal<Member | null, Member | null>({
    source: this.member,
    computation: (member, previous) => member ?? previous?.value ?? null,
  });

  private readonly openedId = computed(() => this.member()?.userId ?? null);
  private readonly principalId = computed(() => this.member()?.principalId ?? null);

  constructor() {
    // Each opening reads the member's accounts and every group's members again.
    effect(() => {
      const userId = this.openedId();
      if (!userId) return;
      untracked(() => {
        this.pending.set(null);
        if (this.data.accountsOf(userId)) this.data.reloadAccounts(userId);
        if (this.data.canManageGroups()) this.data.wantGroupDetails();
      });
    });
  }

  protected initialsOf(name: string): string {
    return initials(name);
  }

  protected added(iso: string): string {
    return addedOn(iso);
  }

  // --- Role -----------------------------------------------------------------------------------

  /**
   * An instance admin who is not a member (break-glass): they appoint an owner, but never demote,
   * suspend or remove one, which the server refuses (CONVENTIONS §6).
   */
  private readonly breakGlass = computed(() => {
    const access = this.current.access();
    return access.instanceAdmin && access.role === null;
  });

  /** Whether the caller may change this member's role or remove them. */
  protected readonly editable = computed(() => {
    const m = this.shown();
    return (
      !!m &&
      canModifyMember(this.current.access(), m) &&
      m.userId !== this.data.onlyOwner() &&
      !(this.breakGlass() && m.role === 'OWNER')
    );
  });

  private readonly licence = inject(LicenceState);
  /** Every seat taken: a Viewer cannot be given a seat role now (`409 SEATS_EXHAUSTED`). */
  protected readonly noSeatLeft = computed(() => {
    const seats = this.licence.value()?.seats;
    return !!seats && seatsFull(seats);
  });

  /** The role picked and not saved yet. */
  protected readonly pending = signal<Role | null>(null);

  protected readonly roleCards = computed(() => {
    const m = this.shown();
    if (!m) return [];
    const access = this.current.access();
    const manage = can.manageMembers(access);
    const owners = can.manageOwners(access);
    const onlyOwner = m.userId === this.data.onlyOwner();
    const editable = this.editable();
    const selected = this.pending() ?? m.role;
    return ROLES.map((role) => {
      let reason = '';
      if (role === 'OWNER' && manage) {
        if (onlyOwner) reason = 'The only owner: make someone else an owner first.';
        else if (m.role === 'OWNER' && this.breakGlass())
          reason = 'As an instance admin you appoint owners, never change an owner’s role.';
        else if (m.role === 'OWNER' && !owners) reason = 'Only an owner changes an owner’s role.';
        else if (!owners) reason = 'Only an owner makes someone an owner.';
      }
      return {
        role,
        label: ROLE_LABELS[role],
        description: ROLE_DESCRIPTIONS[role],
        checked: selected === role,
        // A role the identity provider or SCIM decides changes only to OWNER here (ADR-0088).
        locked:
          !editable || (role === 'OWNER' && !owners) || (!!m.roleManagedBy && role !== 'OWNER'),
        was: this.pending() !== null && m.role === role,
        reason,
        onlyOwner,
      };
    });
  });

  protected pick(role: Role): void {
    const m = this.shown();
    if (m) this.pending.set(role === m.role ? null : role);
  }

  protected readonly roleOpen = signal(false);
  protected readonly roleBusy = signal(false);
  /** The change being confirmed: kept while the confirmation closes. */
  protected readonly confirming = signal<(RoleChange & { member: Member; to: Role }) | null>(null);

  protected askRole(member: Member, to: Role): void {
    const self = member.userId === this.myId();
    this.confirming.set({ ...roleChange(member.role, to, member.displayName, self), member, to });
    this.roleOpen.set(true);
  }

  protected async applyRole(): Promise<void> {
    const change = this.confirming();
    const companyId = this.current.id();
    if (!change || !companyId || this.roleBusy()) return;
    const { member, to } = change;
    this.roleBusy.set(true);
    try {
      const updated = await this.api.invoke(updateMember, {
        companyId,
        userId: member.userId,
        body: { role: to },
      });
      this.data.members.update((items) =>
        items.map((m) => (m.userId === updated.userId ? updated : m)),
      );
      this.notify.success(`${updated.displayName} is now ${ROLE_LABELS[updated.role]}`);
      if (updated.userId === this.myId()) {
        await this.session.refresh();
        this.current.company.reload();
      }
    } catch (error) {
      this.notify.error(`Couldn't change the role of ${member.displayName}`, error, {
        LAST_OWNER: `${member.displayName} is the last owner. Make someone else an owner first.`,
        ROLE_MANAGED: `${member.displayName}’s role is managed by their identity provider. Change it there.`,
        'status:403': 'Your role cannot make this change.',
      });
    } finally {
      // Either way the cards show the role the member has now.
      this.pending.set(null);
      this.roleOpen.set(false);
      this.roleBusy.set(false);
    }
  }

  // --- Suspend, resume and remove ---------------------------------------------------------------

  /** Why the foot offers nothing: the only owner, or an owner an admin may not touch. */
  protected readonly endLock = computed(() => {
    const m = this.shown();
    if (!m || !this.canManage()) return '';
    if (m.userId === this.data.onlyOwner()) {
      return m.userId === this.myId()
        ? 'You are the only owner: make someone else an owner before you leave.'
        : `Only an owner suspends or removes an owner, and ${m.displayName} is the only one: make someone else an owner first.`;
    }
    if (!this.editable()) {
      return this.breakGlass() && m.role === 'OWNER'
        ? 'As an instance admin you never suspend or remove an owner.'
        : 'Only an owner suspends or removes an owner.';
    }
    return '';
  });

  protected readonly suspending = signal<Member | null>(null);
  protected readonly suspendOpen = signal(false);
  protected readonly suspendBusy = signal(false);
  protected readonly suspendError = signal<string | null>(null);
  protected readonly statusBusy = signal(false);

  protected askSuspend(): void {
    this.suspending.set(this.shown());
    this.suspendError.set(null);
    this.suspendOpen.set(true);
  }

  private suspensionMessages(member: Member): Record<string, string> {
    return {
      CANNOT_SUSPEND_SELF: 'You cannot suspend yourself. Another admin can.',
      LAST_OWNER: `${member.displayName} is the last active owner. Make someone else an owner first.`,
      PRINCIPAL_SUSPENDED: `${member.displayName}'s Tinfirm account was deactivated: reactivating the user resumes them.`,
      'status:403': 'Your role cannot suspend this member.',
    };
  }

  protected async suspend(reason: string): Promise<void> {
    const member = this.suspending();
    const companyId = this.current.id();
    if (!member || !companyId || this.suspendBusy()) return;
    this.suspendBusy.set(true);
    this.suspendError.set(null);
    try {
      await this.api.invoke(suspendMember, { companyId, userId: member.userId, body: { reason } });
      this.suspendOpen.set(false);
      this.setSuspended(member.userId, true);
      this.notify.success(
        `${member.displayName} is suspended`,
        'Their running turns are cancelled.',
      );
    } catch (error) {
      this.suspendError.set(problemMessage(error, this.suspensionMessages(member)));
    } finally {
      this.suspendBusy.set(false);
    }
  }

  protected async resume(member: Member): Promise<void> {
    const companyId = this.current.id();
    if (!companyId || this.statusBusy()) return;
    this.statusBusy.set(true);
    try {
      await this.api.invoke(resumeMember, { companyId, userId: member.userId });
      this.setSuspended(member.userId, false);
      this.notify.success(
        `${member.displayName} is resumed`,
        'Their waiting turns go on; triggers they wrote stay paused until resumed.',
      );
    } catch (error) {
      this.notify.error(
        `Couldn't resume ${member.displayName}`,
        error,
        this.suspensionMessages(member),
      );
    } finally {
      this.statusBusy.set(false);
    }
  }

  private setSuspended(userId: string, suspended: boolean): void {
    this.data.members.update((items) =>
      items.map((m) => (m.userId === userId ? { ...m, suspended } : m)),
    );
  }

  /** The member in the confirmation; kept after closing so the text doesn't blank out. */
  protected readonly removing = signal<Member | null>(null);
  protected readonly removeOpen = signal(false);
  protected readonly removeBusy = signal(false);
  protected readonly removeError = signal<string | null>(null);

  /** What removing the member will do (the package 6 contract, §5.5), shown in the confirmation. */
  protected readonly preview = signal<DeparturePreview | null>(null);
  protected readonly previewLoading = signal(false);
  protected readonly previewError = signal<string | null>(null);
  protected readonly previewLines = computed(() => {
    const preview = this.preview();
    return preview ? departurePreviewLines(preview) : [];
  });

  protected askRemove(member: Member): void {
    this.removeError.set(null);
    this.removing.set(member);
    this.removeOpen.set(true);
    if (member.userId !== this.myId()) void this.loadPreview(member);
  }

  private async loadPreview(member: Member): Promise<void> {
    const companyId = this.current.id();
    if (!companyId) return;
    this.preview.set(null);
    this.previewError.set(null);
    this.previewLoading.set(true);
    try {
      const preview = await this.api.invoke(previewDeparture, {
        companyId,
        userId: member.userId,
      });
      if (this.removing()?.userId === member.userId) this.preview.set(preview);
    } catch (error) {
      this.previewError.set(
        `Couldn't tell what else this changes: ${problemMessage(error).replace(/\.$/, '')}.`,
      );
    } finally {
      this.previewLoading.set(false);
    }
  }

  protected async confirmRemove(): Promise<void> {
    const member = this.removing();
    const companyId = this.current.id();
    if (!member || !companyId || this.removeBusy()) return;
    this.removeBusy.set(true);
    this.removeError.set(null);
    // The member's own principal when the preview has not answered yet: the checklist still opens.
    const principalId = this.preview()?.principalId ?? member.principalId;
    try {
      await this.api.invoke(removeMember, { companyId, userId: member.userId });
      this.removeOpen.set(false);
      this.closed.emit();
      this.data.members.update((items) => items.filter((m) => m.userId !== member.userId));
      this.notify.success(`${member.displayName} removed`);
      if (member.userId === this.myId()) {
        await this.session.refresh();
        await this.router.navigate(['/']);
      } else if (principalId) {
        await this.router.navigate(['/c', companyId, 'members', 'departures', principalId]);
      }
    } catch (error) {
      this.removeError.set(
        problemMessage(error, {
          LAST_OWNER: `${member.displayName} is the last owner. Make someone else an owner before removing them.`,
          'status:403': 'Your role cannot remove this member.',
        }),
      );
    } finally {
      this.removeBusy.set(false);
    }
  }

  // --- Budget ---------------------------------------------------------------------------------

  /** The member's own budget, or the company's default (`getPrincipalModelAccess`). */
  protected readonly budget = resource({
    params: () => {
      const companyId = this.current.id();
      const principalId = this.principalId();
      return companyId && principalId && this.canManage() ? { companyId, principalId } : undefined;
    },
    loader: ({ params }) => this.api.invoke(getPrincipalModelAccess, params),
  });
  protected readonly budgetText = computed(() => {
    const value = this.budget.hasValue() ? this.budget.value() : undefined;
    return value ? budgetLine(value) : null;
  });
}
