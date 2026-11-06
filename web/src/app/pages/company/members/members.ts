import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  signal,
  viewChild,
} from '@angular/core';
import { email, form, FormField, maxLength, required, submit } from '@angular/forms/signals';
import { NgTemplateOutlet } from '@angular/common';
import { RouterLink } from '@angular/router';
import { NgIcon, provideIcons } from '@ng-icons/core';
import {
  lucideChevronRight,
  lucideCircleAlert,
  lucideCirclePause,
  lucidePlus,
  lucideSearch,
  lucideTriangleAlert,
  lucideUserPlus,
  lucideUsers,
} from '@ng-icons/lucide';
import { HlmAlertImports } from '@spartan-ng/helm/alert';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmDialogImports } from '@spartan-ng/helm/dialog';
import { HlmEmptyImports } from '@spartan-ng/helm/empty';
import { HlmFieldImports } from '@spartan-ng/helm/field';
import { HlmInput } from '@spartan-ng/helm/input';
import { HlmSkeleton } from '@spartan-ng/helm/skeleton';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { Api } from '../../../api/generated/api';
import { addMember } from '../../../api/generated/functions';
import type { Member, Role } from '../../../api/generated/models';
import { mapSubmissionError } from '../../../api/problem';
import { CurrentCompany } from '../../../core/current-company';
import { LicenceState } from '../../../core/licence';
import { Notify } from '../../../core/notify';
import { assignableRoles, can, ROLE_DESCRIPTIONS, ROLE_LABELS } from '../../../core/permissions';
import { Session } from '../../../core/session';
import { ErrorState } from '../../../shared/error-state';
import { firstError, initials } from '../../../shared/forms';
import { seatsFull, seatsLine } from '../../../shared/licence-labels';
import { ListSkeleton } from '../../../shared/list-skeleton';
import { PageHeader } from '../../../shared/page-header';
import { RoleBadge } from '../../../shared/role-badge';
import { Groups } from '../groups/groups';
import { MemberDialog, ROLE_CARD, ROLE_DOT } from './member-dialog';
import { MembersData } from './members-data';
import {
  accountsLine,
  addedOn,
  emptyTitle,
  filterCounts,
  isMemberFilter,
  MEMBER_FILTERS,
  type MemberFilter,
  matchesMember,
} from './members-view';
import { OpenDepartures } from './open-departures';

/**
 * The people of the company and their roles (CONVENTIONS §6), with the groups they form in the
 * `?tab=groups` tab (plan §3). A calm list: who, the role, the accounts' status in one line (I4),
 * suspended or not, when added; searched and filtered by role. A member opens in a dialog where
 * owners and admins change the role (asked first), see their accounts, put them in groups, read
 * their budget, and suspend, resume or remove them (the package 6 contract, §5.4 and §5.5). A
 * banner leads to the checklist of a member who left while it is open.
 */
@Component({
  selector: 'app-members',
  imports: [
    FormField,
    NgIcon,
    NgTemplateOutlet,
    RouterLink,
    HlmAlertImports,
    HlmButton,
    HlmDialogImports,
    HlmEmptyImports,
    HlmFieldImports,
    HlmInput,
    HlmSkeleton,
    HlmSpinner,
    ErrorState,
    ListSkeleton,
    PageHeader,
    RoleBadge,
    Groups,
    MemberDialog,
    OpenDepartures,
  ],
  providers: [
    MembersData,
    provideIcons({
      lucideChevronRight,
      lucideCircleAlert,
      lucideCirclePause,
      lucidePlus,
      lucideSearch,
      lucideTriangleAlert,
      lucideUserPlus,
      lucideUsers,
    }),
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './members.html',
})
export class Members {
  private readonly api = inject(Api);
  private readonly notify = inject(Notify);
  private readonly current = inject(CurrentCompany);
  private readonly session = inject(Session);
  private readonly licence = inject(LicenceState);
  protected readonly data = inject(MembersData);

  /** From the `tab` query parameter: the members, or their groups. */
  readonly tab = input<string | undefined>();
  protected readonly activeTab = computed(() => (this.tab() === 'groups' ? 'groups' : 'members'));

  protected readonly companyId = this.current.id;
  protected readonly companyName = this.current.name;
  protected readonly canManage = this.data.canManage;
  protected readonly canManageGroups = this.data.canManageGroups;
  protected readonly roleOptions = computed(() => assignableRoles(this.current.access()));
  protected readonly canAddOwner = computed(() => can.manageOwners(this.current.access()));
  protected readonly myId = computed(() => this.session.user()?.id);
  protected readonly roleLabels = ROLE_LABELS;
  protected readonly roleDescriptions = ROLE_DESCRIPTIONS;
  protected readonly firstError = firstError;

  protected readonly members = this.data.members;

  /** "100+" while more are to load. */
  protected readonly memberCount = computed(
    () => `${this.members.items().length}${this.members.hasMore() ? '+' : ''}`,
  );
  protected readonly groupCount = computed(() =>
    this.data.groups.failed()
      ? ''
      : `${this.data.groups.items().length}${this.data.groups.hasMore() ? '+' : ''}`,
  );

  // --- Seats (ADR-0077) ---------------------------------------------------------------------

  /** The instance's seats, counted across its companies; null until the licence is read. */
  private readonly seats = computed(() => this.licence.value()?.seats ?? null);
  /** "12 of 20 seats used", when seats are counted. */
  protected readonly seatsText = computed(() => {
    const seats = this.seats();
    return seats ? seatsLine(seats) : null;
  });
  /** Whether seats are counted at all: under Pro, and Enterprise with a count. */
  protected readonly seatsCounted = computed(() => this.seats()?.licensed != null);
  /** Every seat taken: an Owner, Admin or Member more is refused (`409 SEATS_EXHAUSTED`). */
  protected readonly noSeatLeft = computed(() => {
    const seats = this.seats();
    return !!seats && seatsFull(seats);
  });

  // --- Search and filters --------------------------------------------------------------------

  protected readonly filters = MEMBER_FILTERS;
  protected readonly filter = signal<MemberFilter>('all');
  protected readonly query = signal('');
  protected readonly counts = computed(() => filterCounts(this.members.items()));
  protected readonly shown = computed(() =>
    this.members.items().filter((m) => matchesMember(m, this.filter(), this.query())),
  );
  protected readonly emptyTitle = computed(() => emptyTitle(this.filter(), this.query()));

  protected setFilter(value: string): void {
    if (isMemberFilter(value)) this.filter.set(value);
  }

  protected onQuery(event: Event): void {
    this.query.set((event.target as HTMLInputElement).value);
  }

  protected showEveryone(): void {
    this.filter.set('all');
    this.query.set('');
  }

  // --- A row ---------------------------------------------------------------------------------

  /** One grid: on a narrow list the role and accounts go under the name; wide, one line. */
  protected readonly rowClass =
    'grid w-full grid-cols-[36px_minmax(0,1fr)_auto] items-start gap-x-3 gap-y-1.5 px-4 py-3 text-start @3xl:grid-cols-[36px_minmax(0,1.5fr)_6.5rem_minmax(0,1.4fr)_6.5rem_16px] @3xl:items-center @3xl:gap-x-4';
  protected readonly roleCard = ROLE_CARD;
  protected readonly roleDot = ROLE_DOT;

  protected initialsOf(name: string): string {
    return initials(name);
  }

  protected added(iso: string): string {
    return addedOn(iso);
  }

  /** The member's accounts in one line, or null while they load. */
  protected accountsOf(member: Member): { text: string; tone: string } | null {
    const state = this.data.accountsOf(member.userId);
    if (!state) return null;
    if (state.error) return { text: 'Couldn’t read the accounts', tone: 'muted' };
    return state.items ? accountsLine(state.items) : null;
  }

  // --- The member's dialog -------------------------------------------------------------------

  protected readonly openedId = signal<string | null>(null);
  /** The member opened, as the list holds them now (after a change of role or a suspension). */
  protected readonly opened = computed(
    () => this.members.items().find((m) => m.userId === this.openedId()) ?? null,
  );

  protected open(member: Member): void {
    this.openedId.set(member.userId);
  }

  // --- Groups --------------------------------------------------------------------------------

  private readonly groupsTab = viewChild(Groups);

  protected newGroup(): void {
    this.groupsTab()?.openCreate();
  }

  // --- Add member ----------------------------------------------------------------------------

  protected readonly addOpen = signal(false);
  protected readonly addModel = signal<{ email: string; role: Role }>({
    email: '',
    role: 'MEMBER',
  });
  protected readonly addForm = form(this.addModel, (path) => {
    required(path.email, { message: 'Enter the email of an existing user.' });
    email(path.email, { message: 'Enter a valid email address.' });
    maxLength(path.email, 320, { message: 'The email is too long.' });
    required(path.role);
  });
  protected readonly addError = signal<string | null>(null);
  /** Member first, the most common choice, then the roles with more rights; Viewer last. */
  protected readonly addRoles = computed(() => {
    const roles = this.roleOptions()
      .filter((r) => r !== 'VIEWER')
      .reverse();
    return this.roleOptions().includes('VIEWER') ? [...roles, 'VIEWER' as Role] : roles;
  });

  protected openAdd(): void {
    // With every seat taken only a Viewer can be added: offer that first.
    this.addModel.set({ email: '', role: this.noSeatLeft() ? 'VIEWER' : 'MEMBER' });
    this.addForm().reset();
    this.addError.set(null);
    this.addOpen.set(true);
  }

  protected setAddRole(role: Role): void {
    this.addModel.update((m) => ({ ...m, role }));
  }

  protected onAddSubmit(event: Event): void {
    event.preventDefault();
    const companyId = this.current.id();
    if (!companyId) return;
    this.addError.set(null);
    void submit(this.addForm, async () => {
      try {
        const member = await this.api.invoke(addMember, { companyId, body: this.addModel() });
        this.addOpen.set(false);
        this.members.refresh();
        this.notify.success(`${member.displayName} added as ${ROLE_LABELS[member.role]}`);
        return undefined;
      } catch (error) {
        const failure = mapSubmissionError(this.addForm, error, {
          codeFields: { USER_NOT_FOUND: 'email', DUPLICATE_MEMBER: 'email' },
          overrides: { 'status:403': 'Your role cannot add members with this role.' },
        });
        this.addError.set(failure.message);
        return failure.fieldErrors;
      }
    });
  }
}
