import { HttpTestingController } from '@angular/common/http/testing';
import { type ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import type { Group, GroupSummary, GroupUsage, Member, Role } from '../../../api/generated/models';
import {
  fakeCurrentCompany,
  fakeNotify,
  problem,
  provideTestHttp,
  settle,
  submitForm,
  typeInto,
} from '../../../testing/test-helpers';
import { aGrant, IDS } from '../../../testing/fixtures';
import { words } from '../../../testing/words';
import { MembersData } from '../members/members-data';
import { Groups } from './groups';
import { matchesFilter, pickablePeople, stackOf } from './groups-view';

const RELEASE: GroupSummary = {
  id: 'g1',
  name: 'Release managers',
  description: 'Ship and decide releases.',
  memberCount: 2,
};
const QA: GroupSummary = { id: 'g2', name: 'QA', description: null, memberCount: 0 };

function member(userId: string, displayName: string, email: string): Member {
  return {
    userId,
    displayName,
    email,
    role: 'MEMBER',
    addedAt: '2026-09-01T10:00:00Z',
    suspended: false,
  };
}

const ANA = member('u-ana', 'Ana Novak', 'ana@example.com');
const BOB = member('u-bob', 'Bob Brown', 'bob@example.com');
const CID = member('u-cid', 'Cid Clark', 'cid@example.com');

const RELEASE_GROUP: Group = {
  id: 'g1',
  name: 'Release managers',
  description: 'Ship and decide releases.',
  members: [
    { userId: 'u-ana', displayName: 'Ana Novak', email: 'ana@example.com' },
    { userId: 'u-bob', displayName: 'Bob Brown', email: 'bob@example.com' },
  ],
  createdAt: '2026-10-01T10:00:00Z',
  updatedAt: '2026-10-01T10:00:00Z',
};
const QA_GROUP: Group = { ...RELEASE_GROUP, id: 'g2', name: 'QA', description: null, members: [] };
const [ANA_IN, BOB_IN] = RELEASE_GROUP.members;
const CID_IN = { userId: 'u-cid', displayName: 'Cid Clark', email: 'cid@example.com' };

/** What each group may use: Release managers an agent, two credentials and a service account; QA nothing. */
const USAGES: Record<string, GroupUsage | null> = {
  g1: {
    agents: [{ id: 'a-1', name: 'Business analyst' }],
    grants: [
      aGrant({ groupId: 'g1', groupName: 'Release managers' }),
      aGrant({
        id: 'grant-jira',
        groupId: 'g1',
        groupName: 'Release managers',
        systemName: 'Jira',
      }),
    ],
    serviceIdentities: [{ id: IDS.svcReader, name: 'svc-reader' }],
  },
  g2: { agents: [], grants: [], serviceIdentities: [] },
};
const DAN_IN = { userId: 'u-dan', displayName: 'Dan Dorn', email: 'dan@example.com' };

describe('group member picker', () => {
  it('offers the company members, then a group member the list lacks, by name', () => {
    const people = pickablePeople(
      [BOB, ANA],
      [{ userId: 'u-gone', displayName: 'Aaron Gone', email: 'aaron@example.com' }],
    );
    expect(people.map((p) => p.userId)).toEqual(['u-gone', 'u-ana', 'u-bob']);
    expect(matchesFilter(ANA, 'NOVAK')).toBe(true);
    expect(matchesFilter(ANA, 'bob@')).toBe(false);
    expect(matchesFilter(ANA, '  ')).toBe(true);
  });

  it('stacks a few initials and says how many more', () => {
    const six = [ANA, BOB, CID, ANA, BOB, CID];
    expect(stackOf(six)).toEqual({ initials: ['AN', 'BB', 'CC', 'AN'], more: 2 });
    expect(stackOf([])).toEqual({ initials: [], more: 0 });
  });
});

describe('Groups', () => {
  let fixture: ComponentFixture<Groups>;
  let http: HttpTestingController;
  let el: HTMLElement;
  let notify: ReturnType<typeof fakeNotify>['notify'];
  let details: Record<string, Group>;

  const overlay = () => document.querySelector('.cdk-overlay-container') as HTMLElement;
  const dialog = () => document.querySelector('[data-testid=group-dialog]') as HTMLElement;
  const button = (label: string) =>
    Array.from(document.querySelectorAll('button')).find((b) =>
      b.textContent?.trim().includes(label),
    );
  const rowOf = (name: string) => el.querySelector(`li[data-group="${name}"]`)!;

  async function stable() {
    await settle();
    await fixture.whenStable();
  }

  /** Answers every pending read of a group's members. */
  function flushDetails() {
    for (const req of http.match((r) => /\/api\/companies\/c1\/groups\/[^/]+$/.test(r.url))) {
      if (req.request.method === 'GET') req.flush(details[req.request.url.split('/').pop()!]);
    }
  }

  /** Answers every pending read of what a group may use; a null answer fails with a 500. */
  function flushUsages(answers: Record<string, GroupUsage | null> = USAGES) {
    const reads = http.match((r) => /\/api\/companies\/c1\/groups\/[^/]+\/usage$/.test(r.url));
    for (const req of reads) {
      const usage = answers[req.request.url.split('/')[5]];
      if (usage) req.flush(usage);
      else req.flush(problem(500), { status: 500, statusText: 'Server Error' });
    }
    return reads.map((r) => r.request.url);
  }

  async function create(
    role: Role | null,
    items: GroupSummary[],
    usages: Record<string, GroupUsage | null> = USAGES,
  ) {
    const current = fakeCurrentCompany(role);
    const notifier = fakeNotify();
    notify = notifier.notify;
    details = { g1: RELEASE_GROUP, g2: QA_GROUP };
    TestBed.configureTestingModule({
      imports: [Groups],
      providers: [
        provideRouter([]),
        ...provideTestHttp(),
        current.provider,
        notifier.provider,
        MembersData,
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(Groups);
    el = fixture.nativeElement;
    await settle();
    const req = http.expectOne((r) => r.url === '/api/companies/c1/groups');
    expect(req.request.params.get('limit')).toBe('100');
    req.flush({ items });
    // The members, as the page holds them for both tabs.
    http.expectOne((r) => r.url === '/api/companies/c1/members').flush({ items: [] });
    for (const r of http.match('/api/companies/c1/departures')) r.flush({ items: [] });
    await settle();
    flushDetails();
    // Owners and admins read what each group may use, group by group; other roles do not.
    const reads = flushUsages(usages);
    expect(reads).toEqual(
      role === 'MEMBER' ? [] : items.map((g) => `/api/companies/c1/groups/${g.id}/usage`),
    );
    http.expectNone((r) => /\/(agents|grants|service-identities)$/.test(r.url));
    await stable();
  }

  /** Opens a group's dialog and answers its reads: the group, and the company's members. */
  async function open(name: string, group: Group, members: Member[] = [ANA, BOB, CID]) {
    rowOf(name).querySelector<HTMLButtonElement>('button')!.click();
    await settle();
    http.expectOne({ method: 'GET', url: `/api/companies/c1/groups/${group.id}` }).flush(group);
    http.expectOne((r) => r.url === '/api/companies/c1/members').flush({ items: members });
    await stable();
  }

  afterEach(() => {
    http.verify();
    overlay()?.replaceChildren();
  });

  it('lists each group: its name, its members’ initials, and what it may use', async () => {
    await create('OWNER', [RELEASE, QA]);

    const release = rowOf('Release managers');
    expect(release.getAttribute('data-testid')).toBe('group-g1');
    expect(words(release.querySelector('[data-testid=group-name]'))).toBe('Release managers');
    expect(release.querySelector('[data-testid=group-description]')?.textContent).toBe(
      'Ship and decide releases.',
    );
    expect(words(release.querySelector('[data-testid=group-stack]'))).toBe('AN BB');
    expect(words(release.querySelector('[data-testid=group-members]'))).toBe('2 members');
    expect(words(release.querySelector('[data-testid=group-uses]'))).toBe(
      'BA Business analyst shared agent GitHub and Jira with svc-reader, read-only',
    );
    const qa = rowOf('QA');
    expect(qa.querySelector('[data-testid=group-stack]')).toBeNull();
    expect(words(qa.querySelector('[data-testid=group-members]'))).toBe('0 members');
    expect(words(qa.querySelector('[data-testid=group-uses]'))).toBe(
      'Nothing shared with it or lent to it yet',
    );
  });

  it('shows other roles the names only, without any change action', async () => {
    await create('MEMBER', [RELEASE]);

    expect(el.querySelector('[data-testid=open-group]')).toBeNull();
    expect(el.querySelector('[data-testid=group-stack]')).toBeNull();
    expect(el.querySelector('[data-testid=group-uses]')).toBeNull();
    expect(el.textContent).toContain('Release managers');
    expect(el.querySelector('[data-testid=groups-read-only]')?.textContent).toContain(
      'Only owners and admins can create or change groups',
    );
  });

  it('says when there are no groups yet', async () => {
    await create('ADMIN', []);
    expect(el.querySelector('[data-testid=groups-empty]')?.textContent).toContain('No groups yet');
  });

  it('opens a group: its members and what they may use through it', async () => {
    await create('ADMIN', [RELEASE, QA]);
    await open('Release managers', RELEASE_GROUP);

    const d = dialog();
    expect(words(d.querySelector('h2'))).toBe('Release managers');
    expect(words(d.querySelector('[data-testid=group-member-list]'))).toBe(
      'AN Ana Novak ana@example.com BB Bob Brown bob@example.com',
    );
    expect(words(d.querySelector('[data-testid=group-usage-summary]'))).toBe(
      '1 agent, 2 credentials, 1 service account',
    );
    expect(words(d.querySelector('[data-testid=group-agents]'))).toBe(
      'Business analyst · shared with it',
    );
    expect(words(d.querySelector('[data-testid=group-grants]'))).toBe(
      'GitHub with svc-reader, read-only Jira with svc-reader, read-only',
    );
    expect(words(d.querySelector('[data-testid=group-identities]'))).toBe('svc-reader');
    // Nothing changed yet: no unsaved-changes bar.
    expect(d.querySelector('[data-testid=group-changes]')).toBeNull();
  });

  it('creates a group with a description and the members picked', async () => {
    await create('ADMIN', [QA]);

    fixture.componentInstance.openCreate();
    await settle();
    http.expectOne((r) => r.url === '/api/companies/c1/members').flush({ items: [ANA, BOB, CID] });
    await stable();
    expect(words(dialog().querySelector('h2'))).toBe('New group');
    expect(words(dialog().querySelector('[data-testid=group-changes]'))).toBe(
      'A new group Name it, then add its members. Cancel Create group',
    );

    typeInto(overlay().querySelector('#group-name'), '  Release managers ');
    typeInto(overlay().querySelector('#group-description'), 'Ship and decide releases.');
    dialog().querySelector<HTMLButtonElement>('[data-testid=add-group-member]')!.click();
    await stable();
    overlay().querySelector<HTMLButtonElement>('#group-member-u-ana')!.click();
    await stable();
    overlay().querySelector<HTMLButtonElement>('#group-member-u-cid')!.click();
    await stable();
    expect(words(overlay().querySelector('[data-testid=picked-count]'))).toBe('2 members');
    // The filter narrows who may be added; who was picked stays picked.
    typeInto(overlay().querySelector('#group-member-filter'), 'novak');
    await stable();
    expect(overlay().querySelector('#group-member-u-bob')).toBeNull();
    expect(words(overlay().querySelector('[data-testid=group-member-list]'))).toContain(
      'Cid Clark',
    );

    submitForm(overlay().querySelector('#group-form'));
    await settle();
    const req = http.expectOne({ method: 'POST', url: '/api/companies/c1/groups' });
    expect(req.request.body).toEqual({
      name: 'Release managers',
      description: 'Ship and decide releases.',
      memberIds: ['u-ana', 'u-cid'],
    });
    req.flush(
      { ...RELEASE_GROUP, members: [RELEASE_GROUP.members[0]] },
      { status: 201, statusText: 'Created' },
    );
    await settle();
    http
      .expectOne((r) => r.url === '/api/companies/c1/groups')
      .flush({ items: [{ ...RELEASE, memberCount: 2 }, QA] });
    await settle();
    flushDetails();
    // Only the new group's usage is read; QA's was read already.
    expect(flushUsages()).toEqual(['/api/companies/c1/groups/g1/usage']);
    await stable();

    expect(notify.success).toHaveBeenCalledWith('Release managers created', expect.any(String));
    expect(el.querySelectorAll('li[data-group]')).toHaveLength(2);
    expect(dialog()).toBeNull();
  });

  it('adds and takes out members one at a time, then renames the group, and stays open', async () => {
    await create('OWNER', [RELEASE, QA]);
    await open('Release managers', RELEASE_GROUP);

    typeInto(overlay().querySelector('#group-name'), 'Releases');
    typeInto(overlay().querySelector('#group-description'), '');
    overlay()
      .querySelector<HTMLButtonElement>('button[aria-label="Take Bob Brown out of the group"]')!
      .click();
    dialog().querySelector<HTMLButtonElement>('[data-testid=add-group-member]')!.click();
    await stable();
    overlay().querySelector<HTMLButtonElement>('#group-member-u-cid')!.click();
    await stable();
    expect(words(dialog().querySelector('[data-testid=group-changes]'))).toBe(
      '3 unsaved changes Name · Description · Members Discard Save',
    );

    submitForm(overlay().querySelector('#group-form'));
    await settle();
    http
      .expectOne({ method: 'PUT', url: '/api/companies/c1/groups/g1/members/u-cid' })
      .flush({ ...RELEASE_GROUP, members: [ANA_IN, BOB_IN, CID_IN] });
    await settle();
    http
      .expectOne({ method: 'DELETE', url: '/api/companies/c1/groups/g1/members/u-bob' })
      .flush({ ...RELEASE_GROUP, members: [ANA_IN, CID_IN] });
    await settle();
    // The name and description replace the group, with its members as the last answer gave them.
    const req = http.expectOne({ method: 'PUT', url: '/api/companies/c1/groups/g1' });
    expect(req.request.body).toEqual({
      name: 'Releases',
      description: null,
      memberIds: ['u-ana', 'u-cid'],
    });
    req.flush({ ...RELEASE_GROUP, name: 'Releases', description: null, members: [ANA_IN, CID_IN] });
    await stable();

    expect(notify.success).toHaveBeenCalledWith('Releases saved');
    const row = el.querySelector('[data-testid=group-g1]')!;
    expect(words(row.querySelector('[data-testid=group-name]'))).toBe('Releases');
    expect(row.querySelector('[data-testid=group-description]')).toBeNull();
    expect(words(row.querySelector('[data-testid=group-stack]'))).toBe('AN CC');
    expect(words(dialog().querySelector('h2'))).toBe('Releases');
    expect(dialog().querySelector('[data-testid=group-changes]')).toBeNull();
  });

  it('saves only the members it changed, and keeps what another admin changed meanwhile', async () => {
    await create('OWNER', [RELEASE, QA]);
    await open('Release managers', RELEASE_GROUP);
    overlay()
      .querySelector<HTMLButtonElement>('button[aria-label="Take Bob Brown out of the group"]')!
      .click();
    await stable();
    expect(words(dialog().querySelector('[data-testid=group-changes]'))).toBe(
      '1 unsaved change Members Discard Save',
    );

    submitForm(overlay().querySelector('#group-form'));
    await settle();
    // Another admin added Cid meanwhile: the answer has him, and nothing replaces the group.
    http
      .expectOne({ method: 'DELETE', url: '/api/companies/c1/groups/g1/members/u-bob' })
      .flush({ ...RELEASE_GROUP, members: [ANA_IN, CID_IN] });
    await stable();
    http.expectNone({ method: 'PUT', url: '/api/companies/c1/groups/g1' });

    expect(notify.success).toHaveBeenCalledWith('Release managers saved');
    expect(words(dialog().querySelector('[data-testid=group-member-list]'))).toBe(
      'AN Ana Novak ana@example.com CC Cid Clark cid@example.com',
    );
    expect(words(el.querySelector('[data-testid=group-g1] [data-testid=group-stack]'))).toBe(
      'AN CC',
    );
    expect(dialog().querySelector('[data-testid=group-changes]')).toBeNull();
  });

  it('renames a group with its members as they are now, not as they were read', async () => {
    await create('OWNER', [RELEASE, QA]);
    await open('Release managers', RELEASE_GROUP);
    typeInto(overlay().querySelector('#group-name'), 'Releases');
    await stable();

    submitForm(overlay().querySelector('#group-form'));
    await settle();
    // Another admin added Cid since the dialog opened: the group is read again first.
    http
      .expectOne({ method: 'GET', url: '/api/companies/c1/groups/g1' })
      .flush({ ...RELEASE_GROUP, members: [ANA_IN, BOB_IN, CID_IN] });
    await settle();
    const req = http.expectOne({ method: 'PUT', url: '/api/companies/c1/groups/g1' });
    expect(req.request.body).toEqual({
      name: 'Releases',
      description: 'Ship and decide releases.',
      memberIds: ['u-ana', 'u-bob', 'u-cid'],
    });
    req.flush({ ...RELEASE_GROUP, name: 'Releases', members: [ANA_IN, BOB_IN, CID_IN] });
    await stable();
    expect(notify.success).toHaveBeenCalledWith('Releases saved');
  });

  it('keeps what was saved when a later member change is refused, and shows what is not', async () => {
    await create('OWNER', [RELEASE, QA]);
    await open('Release managers', RELEASE_GROUP);
    overlay()
      .querySelector<HTMLButtonElement>('button[aria-label="Take Bob Brown out of the group"]')!
      .click();
    dialog().querySelector<HTMLButtonElement>('[data-testid=add-group-member]')!.click();
    await stable();
    overlay().querySelector<HTMLButtonElement>('#group-member-u-cid')!.click();
    await stable();

    submitForm(overlay().querySelector('#group-form'));
    await settle();
    // Another admin added Dan meanwhile: the answer has him.
    http
      .expectOne({ method: 'PUT', url: '/api/companies/c1/groups/g1/members/u-cid' })
      .flush({ ...RELEASE_GROUP, members: [ANA_IN, BOB_IN, CID_IN, DAN_IN] });
    await settle();
    http
      .expectOne({ method: 'DELETE', url: '/api/companies/c1/groups/g1/members/u-bob' })
      .flush(problem(403), { status: 403, statusText: 'Forbidden' });
    await stable();

    expect(notify.success).not.toHaveBeenCalled();
    expect(words(overlay().querySelector('[data-testid=group-form-error]'))).toBe(
      'Only owners and admins can change groups.',
    );
    // Cid is in the group now, and Dan stays; taking Bob out is all that is still unsaved.
    expect(words(el.querySelector('[data-testid=group-g1] [data-testid=group-members]'))).toBe(
      '4 members',
    );
    expect(words(dialog().querySelector('[data-testid=group-changes]'))).toBe(
      '1 unsaved change Members Discard Save',
    );
    expect(words(dialog().querySelector('[data-testid=group-member-list]'))).toBe(
      'AN Ana Novak ana@example.com CC Cid Clark cid@example.com DD Dan Dorn dan@example.com',
    );

    // Saving again takes Bob out, and only him.
    submitForm(overlay().querySelector('#group-form'));
    await settle();
    http
      .expectOne({ method: 'DELETE', url: '/api/companies/c1/groups/g1/members/u-bob' })
      .flush({ ...RELEASE_GROUP, members: [ANA_IN, CID_IN, DAN_IN] });
    await stable();
    expect(notify.success).toHaveBeenCalledWith('Release managers saved');
    expect(dialog().querySelector('[data-testid=group-changes]')).toBeNull();
  });

  it('says when what a group may use cannot be read, and reads only that group again', async () => {
    await create('OWNER', [RELEASE, QA], { ...USAGES, g1: null });
    expect(words(el.querySelector('app-error-state'))).toContain(
      'Couldn’t read what the groups may use',
    );
    expect(words(rowOf('QA').querySelector('[data-testid=group-uses]'))).toBe(
      'Nothing shared with it or lent to it yet',
    );
    expect(
      rowOf('Release managers').querySelector('[data-testid=group-uses] hlm-skeleton'),
    ).toBeNull();

    // The dialog says so too, and its retry reads that group again.
    await open('Release managers', RELEASE_GROUP);
    const failed = dialog().querySelector('app-error-state')!;
    expect(words(failed)).toContain('Couldn’t read what it may use');
    failed.querySelector<HTMLButtonElement>('button')!.click();
    await settle();
    expect(flushUsages()).toEqual(['/api/companies/c1/groups/g1/usage']);
    await stable();
    expect(words(dialog().querySelector('[data-testid=group-usage-summary]'))).toBe(
      '1 agent, 2 credentials, 1 service account',
    );
    expect(el.querySelector('app-error-state')).toBeNull();
    expect(words(rowOf('Release managers').querySelector('[data-testid=group-uses]'))).toBe(
      'BA Business analyst shared agent GitHub and Jira with svc-reader, read-only',
    );
  });

  it('discards the changes back to the group as read', async () => {
    await create('OWNER', [RELEASE]);
    await open('Release managers', RELEASE_GROUP);
    typeInto(overlay().querySelector('#group-name'), 'Releases');
    await stable();
    button('Discard')!.click();
    await stable();
    expect(overlay().querySelector<HTMLInputElement>('#group-name')!.value).toBe(
      'Release managers',
    );
    expect(dialog().querySelector('[data-testid=group-changes]')).toBeNull();
  });

  describe('a stale answer of an earlier opening', () => {
    const GROUP_URL = '/api/companies/c1/groups/g1';
    const STALE: Group = {
      ...RELEASE_GROUP,
      name: 'Old name',
      members: [{ userId: 'u-cid', displayName: 'Cid Clark', email: 'cid@example.com' }],
    };

    /** Opens the group, closes the dialog, opens it again: two reads of it are under way. */
    async function reopen() {
      await create('OWNER', [RELEASE, QA]);
      rowOf('Release managers').querySelector<HTMLButtonElement>('button')!.click();
      await settle();
      dialog().querySelector<HTMLButtonElement>('button[aria-label=Close]')!.click();
      await settle();
      rowOf('Release managers').querySelector<HTMLButtonElement>('button')!.click();
      await settle();
      http
        .match((r) => r.url === '/api/companies/c1/members')
        .filter((r) => !r.cancelled)
        .forEach((r) => r.flush({ items: [ANA, BOB, CID] }));
      const reads = http.match({ method: 'GET', url: GROUP_URL });
      expect(reads).toHaveLength(2);
      return { older: reads[0], current: reads[1] };
    }

    const name = () => overlay().querySelector<HTMLInputElement>('#group-name')!;
    const listed = () => words(dialog().querySelector('[data-testid=group-member-list]'));

    it('neither fills the form nor ends the current load', async () => {
      const { older, current } = await reopen();
      older.flush(STALE);
      await stable();
      // Still waiting for the current read: nothing of the stale one.
      expect(dialog().querySelector('[data-testid=member-picker] [aria-busy=true]')).not.toBeNull();
      expect(name().value).toBe('Release managers');

      current.flush(RELEASE_GROUP);
      await stable();
      expect(listed()).toContain('Ana Novak');
      expect(listed()).not.toContain('Cid Clark');
    });

    it('does not overwrite the edits made after the current answer', async () => {
      const { older, current } = await reopen();
      current.flush(RELEASE_GROUP);
      await stable();
      typeInto(name(), 'Releases');
      overlay()
        .querySelector<HTMLButtonElement>('button[aria-label="Take Bob Brown out of the group"]')!
        .click();
      await stable();

      older.flush(STALE);
      await stable();
      expect(name().value).toBe('Releases');
      expect(listed()).toBe('AN Ana Novak ana@example.com');

      submitForm(overlay().querySelector('#group-form'));
      await settle();
      http
        .expectOne({ method: 'DELETE', url: `${GROUP_URL}/members/u-bob` })
        .flush({ ...RELEASE_GROUP, members: [RELEASE_GROUP.members[0]] });
      await settle();
      const req = http.expectOne({ method: 'PUT', url: GROUP_URL });
      expect(req.request.body).toEqual({
        name: 'Releases',
        description: 'Ship and decide releases.',
        memberIds: ['u-ana'],
      });
      req.flush({ ...RELEASE_GROUP, name: 'Releases', members: [RELEASE_GROUP.members[0]] });
      await stable();
    });
  });

  it('puts DUPLICATE_GROUP_NAME on the name and keeps the dialog open', async () => {
    await create('OWNER', [QA]);
    fixture.componentInstance.openCreate();
    await settle();
    http.expectOne((r) => r.url === '/api/companies/c1/members').flush({ items: [ANA] });
    await stable();

    typeInto(overlay().querySelector('#group-name'), 'qa');
    submitForm(overlay().querySelector('#group-form'));
    await settle();
    http
      .expectOne({ method: 'POST', url: '/api/companies/c1/groups' })
      .flush(problem(409, 'DUPLICATE_GROUP_NAME'), { status: 409, statusText: 'Conflict' });
    await stable();

    expect(overlay().textContent).toContain('A group with this name already exists.');
    expect(overlay().querySelector('#group-form')).not.toBeNull();
  });

  it('asks for a name before it calls the server', async () => {
    await create('OWNER', []);
    fixture.componentInstance.openCreate();
    await settle();
    http.expectOne((r) => r.url === '/api/companies/c1/members').flush({ items: [ANA] });
    await stable();

    typeInto(overlay().querySelector('#group-name'), '   ');
    submitForm(overlay().querySelector('#group-form'));
    await stable();
    expect(overlay().textContent).toContain('Enter a name.');
    http.expectNone({ method: 'POST', url: '/api/companies/c1/groups' });
  });

  it('deletes a group after confirmation', async () => {
    await create('ADMIN', [RELEASE, QA]);
    await open('QA', QA_GROUP);

    dialog().querySelector<HTMLButtonElement>('[data-testid=delete-group]')!.click();
    await stable();
    expect(overlay().textContent).toContain('Delete QA?');
    expect(overlay().textContent).toContain('What it was given goes with it');

    button('Delete group')!.click();
    await settle();
    http
      .expectOne({ method: 'DELETE', url: '/api/companies/c1/groups/g2' })
      .flush(null, { status: 204, statusText: 'No Content' });
    await settle();
    flushDetails();
    await stable();

    expect(notify.success).toHaveBeenCalledWith('QA deleted');
    expect(el.querySelectorAll('li[data-group]')).toHaveLength(1);
    expect(dialog()).toBeNull();
  });

  it('keeps the confirmation open with the reason when the delete is refused', async () => {
    await create('ADMIN', [QA]);
    await open('QA', QA_GROUP);
    dialog().querySelector<HTMLButtonElement>('[data-testid=delete-group]')!.click();
    await stable();
    button('Delete group')!.click();
    await settle();
    http
      .expectOne({ method: 'DELETE', url: '/api/companies/c1/groups/g2' })
      .flush(problem(403), { status: 403, statusText: 'Forbidden' });
    await stable();

    expect(overlay().querySelector('[data-testid=delete-error]')?.textContent).toContain(
      'Only owners and admins can change groups.',
    );
    expect(el.querySelectorAll('li[data-group]')).toHaveLength(1);
  });
});
