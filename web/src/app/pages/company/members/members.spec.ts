import { HttpTestingController } from '@angular/common/http/testing';
import { type ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import type {
  DeparturePreview,
  DepartureSummaryList,
  Group,
  GroupSummary,
  Member,
  MemberAccount,
  Licence,
  MemberPage,
  Role,
} from '../../../api/generated/models';
import { Session } from '../../../core/session';
import {
  currentUser,
  fakeCurrentCompany,
  fakeLicence,
  fakeNotify,
  problem,
  provideTestHttp,
  settle,
  submitForm,
  typeInto,
} from '../../../testing/test-helpers';
import {
  aCommunityLicence,
  aLicence,
  aMemberAccount,
  aModelAccess,
  IDS,
} from '../../../testing/fixtures';
import { words } from '../../../testing/words';
import { Members } from './members';
import { MembersData } from './members-data';

const OWNER: Member = {
  userId: 'u-me',
  email: 'me@example.com',
  displayName: 'Me Myself',
  role: 'OWNER',
  addedAt: '2026-09-01T10:00:00Z',
  suspended: false,
};
const OWNER_2: Member = {
  userId: 'u-olga',
  email: 'olga@example.com',
  displayName: 'Olga Owner',
  role: 'OWNER',
  addedAt: '2026-09-02T10:00:00Z',
  suspended: false,
};
const MEMBER: Member = {
  userId: 'u-oscar',
  email: 'oscar@example.com',
  displayName: 'Oscar Member',
  role: 'MEMBER',
  addedAt: '2026-09-03T10:00:00Z',
  suspended: false,
};

const ANALYSTS: GroupSummary = { id: 'g1', name: 'Analysts', description: null, memberCount: 1 };
const QA: GroupSummary = { id: 'g2', name: 'QA', description: null, memberCount: 0 };
const ANALYSTS_GROUP: Group = {
  id: 'g1',
  name: 'Analysts',
  description: 'Read the shop',
  members: [{ userId: 'u-oscar', displayName: 'Oscar Member', email: 'oscar@example.com' }],
  createdAt: '2026-10-01T10:00:00Z',
  updatedAt: '2026-10-01T10:00:00Z',
};
const QA_GROUP: Group = { ...ANALYSTS_GROUP, id: 'g2', name: 'QA', description: null, members: [] };

interface Setup {
  instanceAdmin?: boolean;
  departures?: DepartureSummaryList;
  accounts?: Record<string, MemberAccount[]>;
  groups?: GroupSummary[];
  licence?: Licence;
}

describe('Members', () => {
  let fixture: ComponentFixture<Members>;
  let http: HttpTestingController;
  let el: HTMLElement;
  let notify: ReturnType<typeof fakeNotify>['notify'];
  let navigate: ReturnType<typeof vi.spyOn>;
  let groupDetails: Record<string, Group>;

  async function stable() {
    await settle();
    await fixture.whenStable();
  }

  /** Answers every pending read of a member's accounts. */
  function flushAccounts(accounts: Record<string, MemberAccount[]> = {}) {
    for (const req of http.match((r) => /\/members\/[^/]+\/accounts$/.test(r.url))) {
      req.flush({ items: accounts[req.request.url.split('/')[5]] ?? [] });
    }
  }

  /** Answers every pending read of a group's members. */
  function flushGroups() {
    for (const req of http.match((r) => /\/api\/companies\/c1\/groups\/[^/]+$/.test(r.url))) {
      if (req.request.method !== 'GET') continue;
      req.flush(groupDetails[req.request.url.split('/').pop()!]);
    }
  }

  async function create(role: Role | null, page: MemberPage, setup: Setup = {}) {
    const current = fakeCurrentCompany(role, setup.instanceAdmin ?? false);
    const notifier = fakeNotify();
    notify = notifier.notify;
    groupDetails = { g1: ANALYSTS_GROUP, g2: QA_GROUP };
    TestBed.configureTestingModule({
      imports: [Members],
      providers: [
        provideRouter([]),
        ...provideTestHttp(),
        current.provider,
        notifier.provider,
        fakeLicence(setup.licence ?? aLicence()).provider,
      ],
    });
    http = TestBed.inject(HttpTestingController);
    TestBed.inject(Session).setUser(currentUser());
    navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);

    fixture = TestBed.createComponent(Members);
    el = fixture.nativeElement;
    await settle();
    const req = http.expectOne((r) => r.url === '/api/companies/c1/members');
    expect(req.request.params.get('limit')).toBe('100');
    req.flush(page);
    http
      .expectOne((r) => r.url === '/api/companies/c1/groups')
      .flush({ items: setup.groups ?? [] });
    // Owners and admins also see the members who left whose checklist is open.
    for (const open of http.match((r) => r.url === '/api/companies/c1/departures')) {
      open.flush(setup.departures ?? { items: [] });
    }
    await settle();
    flushAccounts(setup.accounts);
    await stable();
  }

  const button = (label: string) =>
    Array.from(document.querySelectorAll('button')).find((b) =>
      b.textContent?.trim().includes(label),
    );
  const overlay = () => document.querySelector('.cdk-overlay-container') as HTMLElement;
  /** What the page holds of members and groups (the page provides it). */
  const membersData = () => fixture.debugElement.injector.get(MembersData);
  const dialog = () => document.querySelector('[data-testid=member-dialog]') as HTMLElement;
  const row = (email: string) => el.querySelector(`li[data-testid="member-${email}"]`)!;
  const radio = (role: Role) =>
    dialog().querySelector<HTMLInputElement>(`[data-testid=role-card-${role}] input`)!;

  /** Opens a member's dialog and answers what it reads again. */
  async function open(email: string, accounts: Record<string, MemberAccount[]> = {}) {
    row(email).querySelector<HTMLButtonElement>('button')!.click();
    await settle();
    flushAccounts(accounts);
    flushGroups();
    await stable();
  }

  /** Answers the departure preview the removal dialog asks for. */
  async function answerPreview(userId: string, preview: Partial<DeparturePreview> = {}) {
    http.expectOne(`/api/companies/c1/members/${userId}/departure`).flush({
      userId,
      principalId: `p-${userId}`,
      triggersPausing: 0,
      conversationsEnding: 0,
      agentsPassing: 0,
      runnerHostsRemoved: 0,
      accountsRemoved: 0,
      groupsLeft: 0,
      ...preview,
    } satisfies DeparturePreview);
    await stable();
  }

  afterEach(() => {
    http.verify();
    overlay()?.replaceChildren();
  });

  // --- The list ---------------------------------------------------------------------------------

  it('lists members calmly: who, the role, the accounts’ status, when added', async () => {
    await create(
      'OWNER',
      { items: [OWNER, MEMBER] },
      {
        accounts: {
          'u-oscar': [
            aMemberAccount(),
            aMemberAccount({ systemName: 'Jira', status: 'RECONNECT' }),
          ],
        },
      },
    );

    const rows = el.querySelectorAll('li[data-testid^=member-]');
    expect(rows).toHaveLength(2);
    expect(words(rows[0])).toContain('Me Myself You');
    expect(words(rows[0].querySelector('[data-testid=accounts-summary]'))).toBe('No account yet');
    expect(words(rows[1])).toContain('oscar@example.com Member');
    const summary = rows[1].querySelector('[data-testid=accounts-summary]')!;
    expect(words(summary)).toBe('Jira must sign in again · GitHub connected');
    expect(summary.className).toContain('text-warning');
    expect(rows[1].querySelector('time')?.getAttribute('datetime')).toBe(MEMBER.addedAt);
    // No select and no icon buttons in a row: the whole row opens the member.
    expect(rows[1].querySelectorAll('button')).toHaveLength(1);
    expect(rows[1].querySelector('select')).toBeNull();
    expect(words(el.querySelector('[data-tab=members]'))).toBe('Members 2');
  });

  it('hides every change action from a MEMBER', async () => {
    await create('MEMBER', { items: [OWNER, MEMBER] });

    expect(button('Add member')).toBeUndefined();
    expect(el.querySelector('[data-testid=open-member]')).toBeNull();
    expect(el.textContent).toContain('Owner');
    expect(el.textContent).toContain('Only owners and admins can add or change members.');
  });

  it('filters by role, searches, and shows everyone again', async () => {
    await create('ADMIN', {
      items: [
        OWNER,
        OWNER_2,
        MEMBER,
        {
          ...MEMBER,
          userId: 'u-sue',
          email: 'sue@example.com',
          displayName: 'Sue Spended',
          suspended: true,
        },
      ],
    });
    const shown = () => el.querySelectorAll('li[data-testid^=member-]').length;
    const filters = el.querySelector('[data-testid=member-filters]')!;
    expect(words(filters)).toBe('All 4 Owners 2 Admins 0 Members 2 Viewers 0 Suspended 1');

    el.querySelector<HTMLInputElement>('#member-filter-OWNER')!.click();
    await stable();
    expect(shown()).toBe(2);

    el.querySelector<HTMLInputElement>('#member-filter-all')!.click();
    typeInto(el.querySelector('#member-search'), 'OSCAR');
    await stable();
    expect(shown()).toBe(1);

    el.querySelector<HTMLInputElement>('#member-filter-ADMIN')!.click();
    typeInto(el.querySelector('#member-search'), '');
    await stable();
    expect(shown()).toBe(0);
    expect(words(el.querySelector('[data-testid=members-filtered-empty]'))).toContain(
      'No member matches.',
    );
    button('Show everyone')!.click();
    await stable();
    expect(shown()).toBe(4);
  });

  it('marks a suspended member', async () => {
    await create('ADMIN', { items: [OWNER, { ...MEMBER, suspended: true }] });
    const rows = el.querySelectorAll('li[data-testid^=member-]');
    expect(rows[0].querySelector('[data-testid=member-suspended]')).toBeNull();
    expect(words(rows[1].querySelector('[data-testid=member-suspended]'))).toBe('Suspended');
  });

  it('leads to the checklist of the one member who left', async () => {
    await create(
      'ADMIN',
      { items: [OWNER, MEMBER] },
      {
        departures: {
          items: [
            {
              principalId: 'p-dora',
              userId: 'u-dora',
              name: 'Dora Departed',
              leftAt: '2026-10-10T10:00:00Z',
            },
          ],
        },
      },
    );
    const banner = el.querySelector('[data-testid=open-departures]')!;
    expect(words(banner)).toContain('Dora Departed left');
    expect(words(banner)).toContain('what they left behind needs someone.');
    const link = banner.querySelector('a')!;
    expect(link.getAttribute('href')).toBe('/c/c1/members/departures/p-dora');
    expect(words(link)).toBe('Open the checklist of Dora Departed');
  });

  it('names each member who left when there are several', async () => {
    await create(
      'ADMIN',
      { items: [OWNER, MEMBER] },
      {
        departures: {
          items: [
            {
              principalId: 'p-dora',
              userId: 'u-dora',
              name: 'Dora Departed',
              leftAt: '2026-10-10T10:00:00Z',
            },
            {
              principalId: 'p-eve',
              userId: 'u-eve',
              name: 'Eve Exit',
              leftAt: '2026-10-11T10:00:00Z',
            },
          ],
        },
      },
    );
    const banner = el.querySelector('[data-testid=open-departures]')!;
    expect(words(banner)).toContain('2 members left:');
    expect(Array.from(banner.querySelectorAll('a'), (a) => a.getAttribute('href'))).toEqual([
      '/c/c1/members/departures/p-dora',
      '/c/c1/members/departures/p-eve',
    ]);
  });

  it('shows an error state with retry when the list fails', async () => {
    const current = fakeCurrentCompany('OWNER');
    TestBed.configureTestingModule({
      imports: [Members],
      providers: [
        provideRouter([]),
        ...provideTestHttp(),
        current.provider,
        fakeNotify().provider,
        fakeLicence().provider,
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(Members);
    el = fixture.nativeElement;
    await settle();
    http
      .expectOne((r) => r.url === '/api/companies/c1/members')
      .flush(problem(500), { status: 500, statusText: 'Server Error' });
    http.expectOne((r) => r.url === '/api/companies/c1/groups').flush({ items: [] });
    http.expectOne('/api/companies/c1/departures').flush({ items: [] });
    await stable();

    expect(el.textContent).toContain("Couldn't load members");
    expect(el.textContent).toContain('Something went wrong on the server.');
  });

  it('shows the groups in the Groups tab, with New group in the header', async () => {
    await create('OWNER', { items: [OWNER, MEMBER] }, { groups: [ANALYSTS] });
    expect(words(el.querySelector('[data-tab=groups]'))).toBe('Groups 1');
    fixture.componentRef.setInput('tab', 'groups');
    await settle();
    // The tab reads each group's members, and what each group may use.
    flushGroups();
    http
      .expectOne({ method: 'GET', url: '/api/companies/c1/groups/g1/usage' })
      .flush({ agents: [], grants: [], serviceIdentities: [] });
    await stable();

    expect(el.querySelector('app-groups')).not.toBeNull();
    expect(el.querySelector('[data-testid=member-list]')).toBeNull();
    expect(button('Add member')).toBeUndefined();
    expect(el.querySelector('[data-testid=new-group]')).not.toBeNull();
    expect(el.querySelector('[data-tab=groups]')?.getAttribute('aria-current')).toBe('page');
    expect(el.querySelector('[data-tab=members]')?.getAttribute('aria-current')).toBeNull();
  });

  // --- Add ----------------------------------------------------------------------------------------

  it('adds a member by email and role, then reloads the list', async () => {
    await create('OWNER', { items: [OWNER] });

    button('Add member')!.click();
    await stable();
    expect(
      Array.from(
        overlay().querySelectorAll<HTMLInputElement>('[name=add-member-role]'),
        (r) => r.value,
      ),
    ).toEqual(['MEMBER', 'ADMIN', 'OWNER', 'VIEWER']);

    typeInto(document.querySelector('#add-member-email'), 'oscar@example.com');
    document.querySelector<HTMLInputElement>('#add-member-role-ADMIN')!.click();
    submitForm(document.querySelector('#add-member-form'));
    await settle();

    const req = http.expectOne({ method: 'POST', url: '/api/companies/c1/members' });
    expect(req.request.body).toEqual({ email: 'oscar@example.com', role: 'ADMIN' });
    req.flush({ ...MEMBER, role: 'ADMIN' }, { status: 201, statusText: 'Created' });
    await settle();

    http
      .expectOne((r) => r.url === '/api/companies/c1/members')
      .flush({ items: [OWNER, { ...MEMBER, role: 'ADMIN' }] });
    await settle();
    // Only the new member's accounts are read.
    http.expectOne('/api/companies/c1/members/u-oscar/accounts').flush({ items: [] });
    await stable();

    expect(notify.success).toHaveBeenCalledWith('Oscar Member added as Admin');
    expect(el.querySelectorAll('li[data-testid^=member-]')).toHaveLength(2);
  });

  // --- Seats (ADR-0077) ----------------------------------------------------------------------

  it('counts the instance’s seats, and says that a viewer holds none', async () => {
    await create('OWNER', { items: [OWNER, MEMBER] });
    expect(words(el.querySelector('[data-testid=seat-count]'))).toBe(
      '12 of 20 seats used on this instance. Owners, Admins and Members each hold one; Viewers hold none.',
    );
  });

  it('counts people without a licence’s limit under Community', async () => {
    await create('OWNER', { items: [OWNER, MEMBER] }, { licence: aCommunityLicence() });
    expect(words(el.querySelector('[data-testid=seat-count]'))).toBe(
      '12 seats used on this instance: Owners, Admins and Members. Nothing is counted against a licence.',
    );
  });

  it('with every seat taken, offers a viewer first and words SEATS_EXHAUSTED', async () => {
    await create(
      'OWNER',
      { items: [OWNER] },
      { licence: aLicence({ seats: { licensed: 20, used: 20 } }) },
    );
    expect(el.querySelector('[data-testid=seat-count]')!.textContent).toContain(
      'Every seat is taken',
    );

    button('Add member')!.click();
    await stable();
    expect(document.querySelector<HTMLInputElement>('#add-member-role-VIEWER')!.checked).toBe(true);
    expect(overlay().querySelector('[data-testid=add-no-seat]')).not.toBeNull();

    typeInto(document.querySelector('#add-member-email'), 'oscar@example.com');
    document.querySelector<HTMLInputElement>('#add-member-role-MEMBER')!.click();
    submitForm(document.querySelector('#add-member-form'));
    await settle();
    http
      .expectOne({ method: 'POST', url: '/api/companies/c1/members' })
      .flush(
        { ...problem(409, 'SEATS_EXHAUSTED'), details: { licensed: 20, used: 20 } },
        { status: 409, statusText: 'Conflict' },
      );
    await stable();

    expect(overlay().textContent).toContain(
      'All 20 seats of the licence are taken. Owners, Admins and Members each hold a seat; a Viewer holds none.',
    );
  });

  it('says why a viewer cannot be given a seat while every seat is taken', async () => {
    const viewer: Member = { ...MEMBER, role: 'VIEWER' };
    await create(
      'OWNER',
      { items: [OWNER, viewer] },
      { licence: aLicence({ seats: { licensed: 20, used: 20 } }) },
    );
    await open(viewer.email);
    expect(radio('VIEWER').checked).toBe(true);
    expect(words(dialog().querySelector('[data-testid=role-no-seat]'))).toContain(
      'Every seat of the licence is taken: Oscar Member can become an Owner, Admin or Member once',
    );
  });

  it('lets an admin add members and admins, never owners', async () => {
    await create('ADMIN', { items: [OWNER] });
    button('Add member')!.click();
    await stable();
    expect(
      Array.from(
        overlay().querySelectorAll<HTMLInputElement>('[name=add-member-role]'),
        (r) => r.value,
      ),
    ).toEqual(['MEMBER', 'ADMIN', 'VIEWER']);
    expect(overlay().textContent).toContain('Only an owner adds an owner.');
  });

  it('shows USER_NOT_FOUND on the email field', async () => {
    await create('OWNER', { items: [OWNER] });
    button('Add member')!.click();
    await stable();

    typeInto(document.querySelector('#add-member-email'), 'ghost@example.com');
    submitForm(document.querySelector('#add-member-form'));
    await settle();
    http
      .expectOne({ method: 'POST', url: '/api/companies/c1/members' })
      .flush(problem(404, 'USER_NOT_FOUND'), { status: 404, statusText: 'Not Found' });
    await stable();

    expect(overlay().textContent).toContain('No user has this email.');
  });

  // --- The member's dialog --------------------------------------------------------------------

  it('opens a member in a dialog: role cards, accounts, groups, budget, and what ends it', async () => {
    const oscar = { ...MEMBER, principalId: IDS.alice };
    await create('OWNER', { items: [OWNER, oscar] }, { groups: [ANALYSTS, QA] });
    row(MEMBER.email).querySelector('button')!.click();
    await settle();
    flushAccounts({ 'u-oscar': [aMemberAccount()] });
    flushGroups();
    http
      .expectOne(`/api/companies/c1/principals/${IDS.alice}/model-access`)
      .flush(aModelAccess({ budgetResetsAt: '2026-11-12T00:00:00Z' }));
    await stable();

    const d = dialog();
    expect(words(d.querySelector('h2'))).toBe('Oscar Member');
    expect(d.textContent).toContain('oscar@example.com · added');
    expect(
      Array.from(d.querySelectorAll('[data-testid^=role-card-]'), (c) => words(c)?.split(' ')[0]),
    ).toEqual(['Owner', 'Admin', 'Member', 'Viewer']);
    expect(radio('MEMBER').checked).toBe(true);
    expect(words(d.querySelector('[data-testid=role-card-ADMIN]'))).toContain(
      'Sets the company up: systems and their credentials, service accounts',
    );
    expect(words(d.querySelector('[data-testid="member-account-GitHub"]'))).toBe(
      'GitHub Connected',
    );
    expect(words(d.querySelector('[data-testid=member-group-list]'))).toContain('Analysts');
    expect(words(d.querySelector('[data-testid=member-budget]'))).toBe(
      'The default, $50.00 per 30 days · resets 12 Nov Change under Model access',
    );
    expect(d.querySelector('[data-testid=suspend-member]')).not.toBeNull();
    expect(d.querySelector('[data-testid=remove-member]')).not.toBeNull();
    expect(d.querySelector('[data-testid=role-footer]')).toBeNull();
  });

  it('changes a role: the card only selects, Save asks, the confirmation applies', async () => {
    await create('OWNER', { items: [OWNER, MEMBER] });
    await open(MEMBER.email);

    radio('ADMIN').click();
    await stable();
    expect(words(dialog().querySelector('[data-testid=role-footer]'))).toBe(
      'Role changes to Admin Cancel Save',
    );
    expect(words(dialog().querySelector('[data-testid=role-was]'))).toBe('was Member');
    http.expectNone({ method: 'PATCH' });

    dialog().querySelector<HTMLButtonElement>('[data-testid=save-role]')!.click();
    await stable();
    const confirm = document.querySelector('[data-testid=role-dialog]')!;
    expect(words(confirm.querySelector('h2'))).toBe('Make Oscar Member an admin?');
    expect(confirm.textContent).toContain('What it grants Oscar Member');
    expect(confirm.querySelectorAll('[data-testid=role-lines] li')).toHaveLength(3);
    const apply = confirm.querySelector<HTMLButtonElement>('[data-testid=confirm-role]')!;
    expect(words(apply)).toBe('Make admin');
    expect(apply.className).not.toContain('text-destructive');

    apply.click();
    await settle();
    const req = http.expectOne({ method: 'PATCH', url: '/api/companies/c1/members/u-oscar' });
    expect(req.request.body).toEqual({ role: 'ADMIN' });
    req.flush({ ...MEMBER, role: 'ADMIN' });
    await stable();

    expect(notify.success).toHaveBeenCalledWith('Oscar Member is now Admin');
    expect(radio('ADMIN').checked).toBe(true);
    expect(dialog().querySelector('[data-testid=role-footer]')).toBeNull();
    expect(words(row(MEMBER.email))).toContain('Admin');
  });

  it('puts the role back on Cancel, without asking', async () => {
    await create('OWNER', { items: [OWNER, MEMBER] });
    await open(MEMBER.email);
    radio('ADMIN').click();
    await stable();
    button('Cancel')!.click();
    await stable();
    expect(radio('MEMBER').checked).toBe(true);
    expect(dialog().querySelector('[data-testid=role-footer]')).toBeNull();
  });

  it('asks “Make yourself a member?” with the destructive tint', async () => {
    await create('OWNER', { items: [OWNER, OWNER_2] });
    await open(OWNER.email);
    radio('MEMBER').click();
    await stable();
    dialog().querySelector<HTMLButtonElement>('[data-testid=save-role]')!.click();
    await stable();
    const confirm = document.querySelector('[data-testid=role-dialog]')!;
    expect(words(confirm.querySelector('h2'))).toBe('Make yourself a member?');
    expect(confirm.textContent).toContain('the Company pages close for you at once');
    const apply = confirm.querySelector<HTMLButtonElement>('[data-testid=confirm-role]')!;
    expect(words(apply)).toBe('Make me a member');
    expect(apply.className).toContain('text-destructive');
  });

  it('explains LAST_OWNER and puts the old role back', async () => {
    // Not every member is loaded, so the page cannot tell the last owner; the server can.
    await create('OWNER', { items: [OWNER, MEMBER], nextCursor: 'next' });
    await open(OWNER.email);
    radio('ADMIN').click();
    await stable();
    dialog().querySelector<HTMLButtonElement>('[data-testid=save-role]')!.click();
    await stable();
    document.querySelector<HTMLButtonElement>('[data-testid=confirm-role]')!.click();
    await settle();
    http
      .expectOne({ method: 'PATCH', url: '/api/companies/c1/members/u-me' })
      .flush(problem(409, 'LAST_OWNER'), { status: 409, statusText: 'Conflict' });
    await stable();

    expect(notify.error).toHaveBeenCalledWith(
      "Couldn't change the role of Me Myself",
      expect.anything(),
      expect.objectContaining({ LAST_OWNER: expect.stringContaining('last owner') }),
    );
    expect(radio('OWNER').checked).toBe(true);
    expect(dialog().querySelector('[data-testid=role-footer]')).toBeNull();
  });

  it.each<[string, Role | null, boolean]>([
    ['the owner', 'OWNER', false],
    ['an admin', 'ADMIN', false],
    ['an instance admin', null, true],
  ])(
    'keeps the only owner’s role and membership, as %s reads it',
    async (_, role, instanceAdmin) => {
      await create(role, { items: [OWNER, MEMBER] }, { instanceAdmin });
      await open(OWNER.email);
      const d = dialog();
      expect(words(d.querySelector('[data-testid=only-owner]'))).toBe(
        'The only owner: make someone else an owner first.',
      );
      expect((['OWNER', 'ADMIN', 'MEMBER'] as Role[]).map((r) => radio(r).disabled)).toEqual([
        true,
        true,
        true,
      ]);
      expect(d.querySelector('[data-testid=remove-member]')).toBeNull();
      expect(d.querySelector('[data-testid=suspend-member]')).toBeNull();
      expect(words(d.querySelector('[data-testid=member-end]'))).toContain(
        'make someone else an owner',
      );
    },
  );

  it('shows a role the identity provider manages, and lets an owner only make them an owner', async () => {
    const managed = {
      ...MEMBER,
      roleManagedBy: { source: 'IDP' as const, provider: 'entra', name: 'Entra ID' },
    };
    await create('OWNER', { items: [OWNER, managed] });
    expect(words(row(MEMBER.email).querySelector('[data-testid=member-role-managed]'))).toBe(
      'managed by Entra ID',
    );

    await open(MEMBER.email);
    expect(words(dialog().querySelector('[data-testid=role-managed]'))).toContain(
      'Managed by Entra ID: the role changes there',
    );
    expect(radio('ADMIN').disabled).toBe(true);
    expect(radio('MEMBER').disabled).toBe(true);
    expect(radio('VIEWER').disabled).toBe(true);
    expect(radio('OWNER').disabled).toBe(false);
  });

  it('lets an admin change members and admins, never make an owner or touch one', async () => {
    await create('ADMIN', { items: [OWNER, OWNER_2, MEMBER] });
    await open(MEMBER.email);
    expect(radio('OWNER').disabled).toBe(true);
    expect(radio('ADMIN').disabled).toBe(false);
    expect(words(dialog().querySelector('[data-testid=role-locked]'))).toBe(
      'Only an owner makes someone an owner.',
    );
    dialog().querySelector<HTMLButtonElement>('button[aria-label=Close]')!.click();
    await stable();

    await open(OWNER_2.email);
    expect(radio('ADMIN').disabled).toBe(true);
    expect(words(dialog().querySelector('[data-testid=role-locked]'))).toBe(
      'Only an owner changes an owner’s role.',
    );
    expect(dialog().querySelector('[data-testid=remove-member]')).toBeNull();
    expect(words(dialog().querySelector('[data-testid=member-end]'))).toBe(
      'Suspend or remove Only an owner suspends or removes an owner.',
    );
  });

  it('lets an instance admin who is not a member appoint an owner, never change one', async () => {
    await create(null, { items: [OWNER, OWNER_2, MEMBER] }, { instanceAdmin: true });
    await open(MEMBER.email);
    expect(radio('OWNER').disabled).toBe(false);
    expect(radio('ADMIN').disabled).toBe(false);
    dialog().querySelector<HTMLButtonElement>('button[aria-label=Close]')!.click();
    await stable();

    await open(OWNER_2.email);
    expect((['OWNER', 'ADMIN', 'MEMBER'] as Role[]).map((r) => radio(r).disabled)).toEqual([
      true,
      true,
      true,
    ]);
    expect(words(dialog().querySelector('[data-testid=role-locked]'))).toBe(
      'As an instance admin you appoint owners, never change an owner’s role.',
    );
    expect(dialog().querySelector('[data-testid=remove-member]')).toBeNull();
    expect(dialog().querySelector('[data-testid=suspend-member]')).toBeNull();
    expect(words(dialog().querySelector('[data-testid=member-end]'))).toBe(
      'Suspend or remove As an instance admin you never suspend or remove an owner.',
    );
  });

  // --- Suspend, resume, remove ----------------------------------------------------------------

  it('suspends a member with a reason, and resumes them', async () => {
    await create('ADMIN', { items: [OWNER, MEMBER] });
    await open(MEMBER.email);

    dialog().querySelector<HTMLButtonElement>('[data-testid=suspend-member]')!.click();
    await stable();
    expect(overlay().textContent).toContain('Suspend Oscar Member?');
    const confirm = overlay().querySelector<HTMLButtonElement>(
      '[data-testid=suspend-member-dialog-confirm]',
    )!;
    expect(confirm.disabled).toBe(true);
    typeInto(
      overlay().querySelector('[data-testid=suspend-member-dialog] textarea'),
      'A laptop was stolen',
    );
    await settle();
    confirm.click();
    await settle();
    const req = http.expectOne({
      method: 'POST',
      url: '/api/companies/c1/members/u-oscar/suspend',
    });
    expect(req.request.body).toEqual({ reason: 'A laptop was stolen' });
    req.flush({ userId: 'u-oscar', principalId: 'p-oscar', status: 'SUSPENDED' });
    await stable();

    expect(notify.success).toHaveBeenCalledWith(
      'Oscar Member is suspended',
      'Their running turns are cancelled.',
    );
    expect(row(MEMBER.email).querySelector('[data-testid=member-suspended]')).not.toBeNull();
    expect(dialog().querySelector('[data-testid=member-dialog-suspended]')).not.toBeNull();

    dialog().querySelector<HTMLButtonElement>('[data-testid=resume-member]')!.click();
    await settle();
    http
      .expectOne({ method: 'POST', url: '/api/companies/c1/members/u-oscar/resume' })
      .flush({ userId: 'u-oscar', principalId: 'p-oscar', status: 'ACTIVE' });
    await stable();
    expect(row(MEMBER.email).querySelector('[data-testid=member-suspended]')).toBeNull();
  });

  it('never offers to suspend yourself', async () => {
    await create('OWNER', { items: [OWNER, OWNER_2] });
    await open(OWNER.email);
    expect(dialog().querySelector('[data-testid=suspend-member]')).toBeNull();
    expect(dialog().textContent).toContain('You cannot suspend yourself. Another admin can.');
    expect(dialog().querySelector('[data-testid=remove-member]')).not.toBeNull();
  });

  it.each<[string, string]>([
    ['LAST_OWNER', 'Olga Owner is the last active owner. Make someone else an owner first.'],
    ['CANNOT_SUSPEND_SELF', 'You cannot suspend yourself. Another admin can.'],
  ])('explains a refused suspension: %s', async (code, message) => {
    await create('OWNER', { items: [OWNER, OWNER_2] });
    await open(OWNER_2.email);
    dialog().querySelector<HTMLButtonElement>('[data-testid=suspend-member]')!.click();
    await stable();
    typeInto(overlay().querySelector('[data-testid=suspend-member-dialog] textarea'), 'Why');
    await settle();
    overlay()
      .querySelector<HTMLButtonElement>('[data-testid=suspend-member-dialog-confirm]')!
      .click();
    await settle();
    http
      .expectOne('/api/companies/c1/members/u-olga/suspend')
      .flush(problem(409, code), { status: 409, statusText: 'Conflict' });
    await stable();
    expect(overlay().textContent).toContain(message);
  });

  it('removes a member after confirmation and opens the checklist', async () => {
    await create('OWNER', { items: [OWNER, MEMBER] });
    await open(MEMBER.email);

    dialog().querySelector<HTMLButtonElement>('[data-testid=remove-member]')!.click();
    await stable();
    expect(overlay().textContent).toContain('Remove Oscar Member?');
    await answerPreview('u-oscar');
    expect(overlay().textContent).toContain('Nothing else of theirs needs someone.');

    button('Remove member')!.click();
    await settle();
    http
      .expectOne({ method: 'DELETE', url: '/api/companies/c1/members/u-oscar' })
      .flush(null, { status: 204, statusText: 'No Content' });
    await stable();

    expect(notify.success).toHaveBeenCalledWith('Oscar Member removed');
    expect(el.querySelectorAll('li[data-testid^=member-]')).toHaveLength(1);
    expect(navigate).toHaveBeenCalledWith(['/c', 'c1', 'members', 'departures', 'p-u-oscar']);
  });

  it('opens the checklist even when removed before the preview answers', async () => {
    await create('OWNER', { items: [OWNER, { ...MEMBER, principalId: 'p-oscar' }] });
    row(MEMBER.email).querySelector<HTMLButtonElement>('button')!.click();
    await settle();
    flushAccounts();
    flushGroups();
    http.expectOne('/api/companies/c1/principals/p-oscar/model-access').flush(aModelAccess());
    await stable();

    // The preview stays unanswered, so the app never settles until the end: settle() only.
    dialog().querySelector<HTMLButtonElement>('[data-testid=remove-member]')!.click();
    await settle();
    button('Remove member')!.click();
    await settle();
    http
      .expectOne({ method: 'DELETE', url: '/api/companies/c1/members/u-oscar' })
      .flush(null, { status: 204, statusText: 'No Content' });
    await settle();

    expect(navigate).toHaveBeenCalledWith(['/c', 'c1', 'members', 'departures', 'p-oscar']);
    await answerPreview('u-oscar');
  });

  it.each<[string, Partial<DeparturePreview>, string[]]>([
    ['nothing', {}, []],
    [
      'one of each',
      {
        conversationsEnding: 1,
        triggersPausing: 1,
        agentsPassing: 1,
        accountsRemoved: 1,
        runnerHostsRemoved: 1,
        groupsLeft: 1,
      },
      [
        '1 open conversation of theirs ends.',
        '1 trigger they wrote pauses until an admin reassigns it.',
        '1 agent they own passes to the admins.',
        '1 account of theirs on systems is removed.',
        '1 personal runner host is removed.',
        'They leave 1 group.',
      ],
    ],
    [
      'several',
      { conversationsEnding: 3, triggersPausing: 2, groupsLeft: 2 },
      [
        '3 open conversations of theirs end.',
        '2 triggers they wrote pause until an admin reassigns them.',
        'They leave 2 groups.',
      ],
    ],
  ])('previews what removing a member changes: %s', async (_, preview, lines) => {
    await create('OWNER', { items: [OWNER, MEMBER] });
    await open(MEMBER.email);
    dialog().querySelector<HTMLButtonElement>('[data-testid=remove-member]')!.click();
    await settle();
    await answerPreview('u-oscar', preview);
    const items = Array.from(
      overlay().querySelectorAll('[data-testid=departure-preview] li'),
      (li) => li.textContent?.trim(),
    );
    expect(items).toEqual(lines);
  });

  it('keeps the confirmation open with the LAST_OWNER explanation when removal is refused', async () => {
    await create('OWNER', { items: [OWNER, OWNER_2] });
    await open(OWNER_2.email);
    dialog().querySelector<HTMLButtonElement>('[data-testid=remove-member]')!.click();
    await stable();
    await answerPreview('u-olga');
    button('Remove member')!.click();
    await settle();
    http
      .expectOne({ method: 'DELETE', url: '/api/companies/c1/members/u-olga' })
      .flush(problem(409, 'LAST_OWNER'), { status: 409, statusText: 'Conflict' });
    await stable();

    expect(overlay().textContent).toContain(
      'Olga Owner is the last owner. Make someone else an owner before removing them.',
    );
    expect(el.querySelectorAll('li[data-testid^=member-]')).toHaveLength(2);
  });

  it('still handles a 403 the UI did not predict', async () => {
    await create('ADMIN', { items: [OWNER, MEMBER] });
    await open(MEMBER.email);
    dialog().querySelector<HTMLButtonElement>('[data-testid=remove-member]')!.click();
    await stable();
    await answerPreview('u-oscar');
    button('Remove member')!.click();
    await settle();
    http
      .expectOne({ method: 'DELETE', url: '/api/companies/c1/members/u-oscar' })
      .flush(problem(403), { status: 403, statusText: 'Forbidden' });
    await stable();

    expect(overlay().textContent).toContain('Your role cannot remove this member.');
  });

  // --- Accounts and groups in the dialog --------------------------------------------------------

  it('shows a member’s accounts: which system and its status, never more', async () => {
    await create('ADMIN', { items: [OWNER, MEMBER] });
    await open(MEMBER.email, {
      'u-oscar': [
        aMemberAccount(),
        aMemberAccount({ systemId: IDS.jira, systemName: 'Jira', status: 'RECONNECT' }),
        aMemberAccount({ systemId: IDS.crm, systemName: 'CRM', status: 'ERROR' }),
      ],
    });
    const d = dialog();
    expect(words(d.querySelector('[data-testid="member-account-GitHub"]'))).toBe(
      'GitHub Connected',
    );
    expect(words(d.querySelector('[data-testid="member-account-Jira"]'))).toBe(
      'Jira Must sign in again',
    );
    expect(words(d.querySelector('[data-testid="member-account-CRM"]'))).toBe('CRM Not working');
    expect(words(d)).toContain('Only Oscar Member connects or disconnects these accounts.');
    expect(words(d)).not.toContain('You can remove a chat link');
  });

  it('says when a member holds no account', async () => {
    await create('ADMIN', { items: [OWNER, MEMBER] });
    await open(MEMBER.email);
    expect(words(dialog().querySelector('[data-testid=no-member-accounts]'))).toBe(
      'No account on a company system yet.',
    );
  });

  it('lets an admin remove a member’s chat link, and only that', async () => {
    const accounts = {
      'u-oscar': [
        aMemberAccount(),
        aMemberAccount({ systemId: IDS.slack, systemName: 'Acme Slack', systemKind: 'SLACK' }),
      ],
    };
    await create('ADMIN', { items: [OWNER, MEMBER] }, { accounts });
    expect(words(row(MEMBER.email).querySelector('[data-testid=accounts-summary]'))).toBe(
      'GitHub connected · Acme Slack linked',
    );
    await open(MEMBER.email, accounts);
    const d = dialog();
    expect(
      d.querySelector(
        '[data-testid="member-account-GitHub"] [data-testid=remove-member-chat-account]',
      ),
    ).toBeNull();
    const remove = d.querySelector<HTMLButtonElement>(
      '[data-testid="member-account-Acme Slack"] [data-testid=remove-member-chat-account]',
    )!;
    expect(words(remove)).toBe('Remove link on Acme Slack');
    expect(words(d)).toContain('You can remove a chat link that is no longer theirs.');
    remove.click();
    await stable();
    document
      .querySelector<HTMLButtonElement>('[data-testid=confirm-remove-member-chat-account]')!
      .click();
    await settle();
    http
      .expectOne({
        method: 'DELETE',
        url: `/api/companies/c1/members/u-oscar/accounts/${IDS.slack}`,
      })
      .flush(null, { status: 204, statusText: 'No Content' });
    await settle();
    http
      .expectOne('/api/companies/c1/members/u-oscar/accounts')
      .flush({ items: [aMemberAccount()] });
    await stable();
    expect(d.querySelector('[data-testid="member-account-Acme Slack"]')).toBeNull();
  });

  it('puts a member in a group by themselves, and shows what another admin changed meanwhile', async () => {
    await create('OWNER', { items: [OWNER, MEMBER] }, { groups: [ANALYSTS, QA] });
    await open(MEMBER.email);
    const d = dialog();
    expect(words(d.querySelector('[data-testid=member-group-list]'))).toBe('Analysts');

    d.querySelector<HTMLButtonElement>('[data-testid=add-to-group]')!.click();
    await stable();
    const options = d.querySelector('[data-testid=member-group-options]')!;
    expect(words(options)).toBe('QA 0 members');
    options.querySelector<HTMLButtonElement>('[data-group=QA]')!.click();
    await settle();
    // One call for this member alone, without reading the group first.
    http.expectNone({ method: 'GET', url: '/api/companies/c1/groups/g2' });
    const put = http.expectOne({
      method: 'PUT',
      url: '/api/companies/c1/groups/g2/members/u-oscar',
    });
    expect(put.request.body).toBeNull();
    // Someone added Me Myself to QA meanwhile: the answer has them too.
    put.flush({
      ...QA_GROUP,
      members: [
        { userId: 'u-me', displayName: 'Me Myself', email: 'me@example.com' },
        { userId: 'u-oscar', displayName: 'Oscar Member', email: 'oscar@example.com' },
      ],
    });
    await stable();

    expect(notify.success).toHaveBeenCalledWith('Oscar Member is in QA now');
    expect(words(d.querySelector('[data-testid=member-group-list]'))).toBe('Analysts QA');
    expect(d.querySelector('[data-testid=member-group-options]')).toBeNull();
    expect(words(el.querySelector('[data-tab=groups]'))).toBe('Groups 2');
    const rows = membersData().groups.items();
    expect(rows.find((g) => g.id === 'g2')).toEqual({
      ...QA,
      memberCount: 2,
    });
  });

  it('takes a member out of a group', async () => {
    await create('OWNER', { items: [OWNER, MEMBER] }, { groups: [ANALYSTS] });
    await open(MEMBER.email);
    dialog()
      .querySelector<HTMLButtonElement>('button[aria-label="Take Oscar Member out of Analysts"]')!
      .click();
    await settle();
    http
      .expectOne({ method: 'DELETE', url: '/api/companies/c1/groups/g1/members/u-oscar' })
      .flush({ ...ANALYSTS_GROUP, members: [] });
    await stable();
    expect(notify.success).toHaveBeenCalledWith('Oscar Member is out of Analysts');
    expect(dialog().querySelector('[data-testid=member-group-list] li')).toBeNull();
    expect(dialog().textContent).toContain('In no group yet.');
  });

  it('takes out a member another admin took out already: the same answer, no error', async () => {
    await create('OWNER', { items: [OWNER, MEMBER] }, { groups: [ANALYSTS] });
    await open(MEMBER.email);
    dialog()
      .querySelector<HTMLButtonElement>('button[aria-label="Take Oscar Member out of Analysts"]')!
      .click();
    await settle();
    // The server changed nothing (idempotent) and answers the group as it is: Me Myself is in it now.
    http.expectOne({ method: 'DELETE', url: '/api/companies/c1/groups/g1/members/u-oscar' }).flush({
      ...ANALYSTS_GROUP,
      members: [{ userId: 'u-me', displayName: 'Me Myself', email: 'me@example.com' }],
    });
    await stable();
    expect(notify.error).not.toHaveBeenCalled();
    expect(notify.success).toHaveBeenCalledWith('Oscar Member is out of Analysts');
    expect(dialog().textContent).toContain('In no group yet.');
    const analysts = membersData().groupOf('g1');
    expect(analysts?.members.map((m) => m.userId)).toEqual(['u-me']);
  });

  it('explains a refused group change and keeps the member where they were', async () => {
    await create('OWNER', { items: [OWNER, MEMBER] }, { groups: [ANALYSTS] });
    await open(MEMBER.email);
    dialog()
      .querySelector<HTMLButtonElement>('button[aria-label="Take Oscar Member out of Analysts"]')!
      .click();
    await settle();
    http
      .expectOne({ method: 'DELETE', url: '/api/companies/c1/groups/g1/members/u-oscar' })
      .flush(problem(403), { status: 403, statusText: 'Forbidden' });
    await stable();
    expect(notify.error).toHaveBeenCalledWith(
      "Couldn't take Oscar Member out of Analysts",
      expect.anything(),
      { 'status:403': 'Only owners and admins can change groups.' },
    );
    expect(words(dialog().querySelector('[data-testid=member-group-list]'))).toBe('Analysts');
  });

  it('explains a member who can no longer be added, and leaves the choice open', async () => {
    await create('OWNER', { items: [OWNER, MEMBER] }, { groups: [ANALYSTS, QA] });
    await open(MEMBER.email);
    const d = dialog();
    d.querySelector<HTMLButtonElement>('[data-testid=add-to-group]')!.click();
    await stable();
    d.querySelector<HTMLButtonElement>(
      '[data-testid=member-group-options] [data-group=QA]',
    )!.click();
    await settle();
    http
      .expectOne({ method: 'PUT', url: '/api/companies/c1/groups/g2/members/u-oscar' })
      .flush(problem(400, 'VALIDATION_FAILED'), { status: 400, statusText: 'Bad Request' });
    await stable();
    expect(notify.error).toHaveBeenCalledWith(
      "Couldn't add Oscar Member to QA",
      expect.anything(),
      expect.anything(),
    );
    expect(words(d.querySelector('[data-testid=member-group-list]'))).toBe('Analysts');
    expect(d.querySelector('[data-testid=member-group-options] [data-group=QA]')).not.toBeNull();
  });
});
