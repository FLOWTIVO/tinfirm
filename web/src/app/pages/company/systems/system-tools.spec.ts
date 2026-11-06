import { HttpTestingController } from '@angular/common/http/testing';
import { type ComponentFixture, TestBed } from '@angular/core/testing';
import type {
  ExternalSystem,
  SystemCredential,
  SystemTool,
  SystemToolRefresh,
} from '../../../api/generated/models';
import {
  aPersonalMcpServer,
  aSystem,
  aSystemCredential,
  aSystemTool,
} from '../../../testing/fixtures';
import { problemMessage } from '../../../api/problem';
import { BrowserNavigation } from '../../../core/browser-navigation';
import {
  fakeNotify,
  problem,
  provideTestHttp,
  settle,
  submitForm,
  typeInto,
} from '../../../testing/test-helpers';
import {
  asksForCredential,
  readWith,
  refreshSummary,
  suggestion,
  SystemTools,
  unconfirmedBecause,
} from './system-tools';

const NOTES = aPersonalMcpServer({
  id: 'sys-notes',
  name: 'Notes',
  handle: 'notes',
  personal: false,
});
const TOOLS = `/api/companies/c1/systems/${NOTES.id}/tools`;

const mcpTool = (overrides: Partial<SystemTool>): SystemTool =>
  aSystemTool({ fixed: false, effect: 'UNKNOWN', suggestedEffect: null, ...overrides });

const CATALOGUE: SystemTool[] = [
  mcpTool({ name: 'search_notes', title: 'Search the notes', suggestedEffect: 'READ' }),
  mcpTool({ name: 'create_note', suggestedEffect: 'WRITE' }),
  mcpTool({ name: 'archive_note', effect: 'WRITE', enabled: false }),
];

describe('refreshSummary', () => {
  const result = (overrides: Partial<SystemToolRefresh>): SystemToolRefresh => ({
    ok: true,
    added: [],
    removed: [],
    unconfirmed: [],
    withAccount: false,
    credentialRequired: false,
    ...overrides,
  });

  it.each`
    case                        | refresh                                                                    | summary
    ${'nothing new'}            | ${result({})}                                                              | ${'Read the tools again. Nothing was added or removed.'}
    ${'added and removed'}      | ${result({ added: ['a', 'b'], removed: ['c'] })}                           | ${'Read the tools again. 2 tools added, 1 tool removed.'}
    ${'a confirmation lost'}    | ${result({ unconfirmed: ['search_notes'] })}                               | ${'Read the tools again. Nothing was added or removed. search_notes changed (description, schema or hints), so its effect needs confirming again.'}
    ${'two confirmations lost'} | ${result({ unconfirmed: ['a', 'b'] })}                                     | ${'Read the tools again. Nothing was added or removed. a, b changed (description, schema or hints), so their effects need confirming again.'}
    ${'an unreadable server'}   | ${result({ ok: false, message: 'The server answered 502 to tools/list' })} | ${"Couldn't read the server's tools: The server answered 502 to tools/list. The catalogue stays as it was."}
  `('says $case', ({ refresh, summary }) => {
    expect(refreshSummary(refresh)).toBe(summary);
  });

  it('says the server asks for a credential none was given for, and a refused one', () => {
    const asks = result({ ok: false, credentialRequired: true, message: '401' });
    expect(asksForCredential(asks)).toBe(true);
    expect(refreshSummary(asks)).toBe(
      'The server asks for a credential, and none was given: you hold no account of your own on it, and it has no working credential to read it with. The catalogue stays as it was.',
    );
    const refused = result({
      ok: false,
      withAccount: true,
      credentialRequired: true,
      message: 'The server answered 401 to initialize',
    });
    expect(asksForCredential(refused)).toBe(false);
    expect(refreshSummary(refused)).toBe(
      'The server refused the account it was read with: The server answered 401 to initialize. Sign it in again, or store a new token. The catalogue stays as it was.',
    );
  });

  it('says what the tools were read with, the credential by the name the server gives', () => {
    expect(
      readWith(
        result({ withAccount: true, credentialId: 'cred-1', credentialName: 'Server token' }),
      ),
    ).toBe('Read with the credential Server token.');
    expect(readWith(result({ withAccount: true, credentialId: 'cred-2' }))).toBe(
      'Read with one of its credentials.',
    );
    expect(readWith(result({ withAccount: true }))).toBe('Read with your own account.');
    expect(readWith(result({}))).toBe('Read without an account.');
  });
});

describe('suggestion', () => {
  it.each`
    effect       | suggested  | says
    ${'UNKNOWN'} | ${'READ'}  | ${'Its hints say it only reads.'}
    ${'UNKNOWN'} | ${'WRITE'} | ${'Its hints say it writes.'}
    ${'UNKNOWN'} | ${null}    | ${'It sends no hints.'}
    ${'READ'}    | ${'READ'}  | ${null}
    ${'WRITE'}   | ${null}    | ${null}
  `('a $effect tool suggested $suggested says $says', ({ effect, suggested, says }) => {
    expect(suggestion({ effect, suggestedEffect: suggested })).toBe(says);
  });
});

describe('unconfirmedBecause', () => {
  const refreshed = { ok: true, added: ['a'], unconfirmed: ['b'] };
  it.each`
    name   | refresh                                      | says
    ${'a'} | ${refreshed}                                 | ${'New at the last refresh'}
    ${'b'} | ${refreshed}                                 | ${'Changed at the last refresh, so it needs confirming again'}
    ${'c'} | ${refreshed}                                 | ${null}
    ${'a'} | ${null}                                      | ${null}
    ${'a'} | ${{ ok: false, added: [], unconfirmed: [] }} | ${null}
  `('says why $name waits: $says', ({ name, refresh, says }) => {
    expect(unconfirmedBecause(name, refresh)).toBe(says);
  });
});

describe('SystemTools', () => {
  let fixture: ComponentFixture<SystemTools>;
  let http: HttpTestingController;
  let notify: ReturnType<typeof fakeNotify>['notify'];

  async function create(
    system: ExternalSystem,
    items: SystemTool[],
    canManage = true,
    credentials: SystemCredential[] = [],
  ) {
    const notifier = fakeNotify();
    notify = notifier.notify;
    TestBed.configureTestingModule({
      imports: [SystemTools],
      providers: [
        ...provideTestHttp(),
        notifier.provider,
        { provide: BrowserNavigation, useValue: { assign: vi.fn() } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(SystemTools);
    fixture.componentRef.setInput('companyId', 'c1');
    fixture.componentRef.setInput('system', system);
    fixture.componentRef.setInput('canManage', canManage);
    fixture.componentRef.setInput('credentials', credentials);
    await settle();
    http.expectOne(`/api/companies/c1/systems/${system.id}/tools`).flush({ items });
    await settle();
    fixture.detectChanges();
  }

  const el = () => fixture.nativeElement as HTMLElement;
  const tool = (name: string) => el().querySelector<HTMLElement>(`[data-testid="tool-${name}"]`)!;
  const text = (node: Element | null | undefined) => node?.textContent?.replace(/\s+/g, ' ').trim();

  const overlay = () => document.querySelector('.cdk-overlay-container') as HTMLElement;

  afterEach(() => {
    http.verify();
    overlay()?.replaceChildren();
  });

  it('shows a tracker’s fixed tools with their effect and nothing to change', async () => {
    await create(aSystem(), [
      aSystemTool({ name: 'get_issue', effect: 'READ' }),
      aSystemTool({ name: 'create_pull_request', effect: 'WRITE' }),
    ]);
    expect(text(tool('get_issue').querySelector('[data-testid=tool-effect]'))).toBe('Reads');
    expect(text(tool('create_pull_request').querySelector('[data-testid=tool-effect]'))).toBe(
      'Writes',
    );
    expect(text(el().querySelector('[data-testid=tools-handle]'))).toBe('github_…');
    expect(el().querySelector('[data-testid=refresh-tools]')).toBeNull();
    expect(el().querySelector('[data-testid^=confirm-]')).toBeNull();
    expect(el().querySelector('hlm-switch')).toBeNull();
  });

  it('shows a tool’s description on expand', async () => {
    await create(aSystem(), [aSystemTool({ description: 'Opens a pull request from a branch.' })]);
    const row = tool('create_pull_request').querySelector<HTMLButtonElement>(
      '#tool-row-create_pull_request',
    )!;
    expect(row.getAttribute('aria-expanded')).toBe('false');
    expect(text(el())).not.toContain('Opens a pull request from a branch.');
    row.click();
    await settle();
    fixture.detectChanges();
    expect(row.getAttribute('aria-expanded')).toBe('true');
    expect(text(el().querySelector('#tool-details-create_pull_request'))).toBe(
      'Opens a pull request from a branch.',
    );
  });

  it('lists the MCP tools to confirm first, in orange for whoever manages it, with their hint', async () => {
    await create(NOTES, CATALOGUE);
    expect(text(el().querySelector('[data-testid=tools-to-confirm]'))).toBe('2 to confirm');
    expect(el().querySelector('[data-testid=tools-to-confirm]')?.classList).toContain('text-human');
    expect(
      Array.from(el().querySelectorAll('[data-effect]')).map((li) =>
        li.getAttribute('data-testid'),
      ),
    ).toEqual(['tool-search_notes', 'tool-create_note', 'tool-archive_note']);
    expect(text(el().querySelector('#tools-pending-title'))).toBe('To confirm 2');
    const search = tool('search_notes');
    expect(text(search.querySelector('[data-testid=tool-effect]'))).toBe(
      'Counts as a write until you say',
    );
    expect(search.querySelector('[data-testid=tool-effect]')?.classList).toContain('text-human');
    expect(text(search.querySelector('[data-testid=tool-suggestion]'))).toBe(
      'Its hints say it only reads.',
    );
    const ask = search.querySelector<HTMLButtonElement>('[data-testid=confirm-effect]')!;
    expect(text(ask)).toBe('Reads or writes?');
    expect(ask.className).toContain('text-human');
    // The two choices, the one the hints suggest marked.
    ask.click();
    await settle();
    fixture.detectChanges();
    expect(ask.getAttribute('aria-expanded')).toBe('true');
    expect(text(search.querySelector('[data-testid=confirm-READ]'))).toContain(
      'It only reads Its hints say so',
    );
    expect(text(search.querySelector('[data-testid=confirm-WRITE]'))).not.toContain(
      'Its hints say so',
    );
    // A confirmed tool is no longer orange, and one switched off says so.
    const archive = tool('archive_note');
    expect(archive.querySelector('[data-testid=tool-effect]')?.classList).not.toContain(
      'text-human',
    );
    archive.querySelector<HTMLButtonElement>('#tool-row-archive_note')!.click();
    await settle();
    fixture.detectChanges();
    expect(archive.querySelector('[data-testid=confirm-WRITE]')?.getAttribute('aria-pressed')).toBe(
      'true',
    );
    expect(text(archive.querySelector('label[for="tool-enabled-archive_note"]'))).toContain('Off');
  });

  it('says when every tool’s effect is confirmed', async () => {
    await create(NOTES, [CATALOGUE[2]]);
    expect(el().querySelector('[data-testid=tools-all-confirmed]')).not.toBeNull();
    expect(el().querySelector('[data-testid=tools-to-confirm]')).toBeNull();
  });

  it('keeps an unconfirmed tool neutral for someone who cannot confirm it', async () => {
    await create(NOTES, CATALOGUE, false);
    expect(el().querySelector('.text-human')).toBeNull();
    expect(el().querySelector('[data-testid^=confirm-]')).toBeNull();
    expect(el().querySelector('[data-testid=refresh-tools]')).toBeNull();
  });

  it.each`
    choice     | effect
    ${'READ'}  | ${'READ'}
    ${'WRITE'} | ${'WRITE'}
  `('confirms a tool as $choice', async ({ choice, effect }) => {
    await create(NOTES, CATALOGUE);
    tool('search_notes').querySelector<HTMLButtonElement>('[data-testid=confirm-effect]')!.click();
    await settle();
    fixture.detectChanges();
    tool('search_notes')
      .querySelector<HTMLButtonElement>(`[data-testid=confirm-${choice}]`)!
      .click();
    await settle();
    const req = http.expectOne(`${TOOLS}/search_notes`);
    expect(req.request.method).toBe('PATCH');
    expect(req.request.body).toEqual({ effect });
    req.flush({ ...CATALOGUE[0], effect });
    await settle();
    fixture.detectChanges();
    expect(tool('search_notes').getAttribute('data-effect')).toBe(effect);
    expect(text(el().querySelector('[data-testid=tools-to-confirm]'))).toBe('1 to confirm');
    expect(notify.success).toHaveBeenCalled();
    // It joins the confirmed ones; the focus goes on to the next tool to confirm.
    expect(tool('search_notes').querySelector('[data-testid=confirm-effect]')).toBeNull();
    await settle();
    expect(document.activeElement?.id).toBe('tool-ask-create_note');
  });

  it('switches a tool off, and back when the server refuses', async () => {
    await create(NOTES, CATALOGUE);
    const toggle = () =>
      tool('create_note').querySelector<HTMLButtonElement>('button[role=switch]')!;
    toggle().click();
    await settle();
    http
      .expectOne(`${TOOLS}/create_note`)
      .flush(problem(403), { status: 403, statusText: 'Forbidden' });
    await settle();
    fixture.detectChanges();
    expect(toggle().getAttribute('aria-checked')).toBe('true');
    expect(notify.error).toHaveBeenCalled();

    toggle().click();
    await settle();
    const req = http.expectOne(`${TOOLS}/create_note`);
    expect(req.request.body).toEqual({ enabled: false });
    req.flush({ ...CATALOGUE[1], enabled: false });
    await settle();
    fixture.detectChanges();
    expect(tool('create_note').getAttribute('data-enabled')).toBe('false');
  });

  it('reads the catalogue again, says what changed and shows the new one', async () => {
    await create(NOTES, []);
    expect(text(el().querySelector('[data-testid=tools-empty]'))).toContain('No tools read yet');
    el().querySelector<HTMLButtonElement>('[data-testid=refresh-tools]')!.click();
    await settle();
    http
      .expectOne((r) => r.url === `${TOOLS}/refresh` && r.method === 'POST')
      .flush({
        ok: true,
        added: ['search_notes', 'create_note', 'archive_note'],
        removed: [],
        unconfirmed: [],
        withAccount: false,
        credentialRequired: false,
      });
    await settle();
    http.expectOne(TOOLS).flush({ items: CATALOGUE });
    await settle();
    fixture.detectChanges();
    expect(text(el().querySelector('[data-testid=refresh-result]'))).toBe(
      'Read the tools again. 3 tools added. Read without an account.',
    );
    expect(el().querySelectorAll('[data-effect]')).toHaveLength(3);
    // One credential or none: the server chooses, so there is nothing to pick.
    expect(el().querySelector('#tools-credential')).toBeNull();
  });

  it('asks for a credential the server wants, adds one in place and reads the tools with it', async () => {
    await create(NOTES, []);
    const added: SystemCredential[] = [];
    fixture.componentInstance.credentialAdded.subscribe((c) => added.push(c));
    el().querySelector<HTMLButtonElement>('[data-testid=refresh-tools]')!.click();
    await settle();
    http.expectOne(`${TOOLS}/refresh`).flush({
      ok: false,
      message: 'The server answered 401 to initialize',
      withAccount: false,
      credentialRequired: true,
      added: [],
      removed: [],
      unconfirmed: [],
    });
    await settle();
    http.expectOne(TOOLS).flush({ items: [] });
    await settle();
    fixture.detectChanges();
    expect(text(el().querySelector('[data-testid=refresh-result]'))).toContain(
      'The server asks for a credential, and none was given',
    );
    // The same dialog as the credentials section's, right here.
    el().querySelector<HTMLButtonElement>('[data-testid=add-credential-for-tools]')!.click();
    await settle();
    fixture.detectChanges();
    typeInto(overlay().querySelector('#credential-name'), 'Server token');
    typeInto(overlay().querySelector('#credential-token'), 'tok-notes');
    submitForm(overlay().querySelector('#credential-form'));
    await settle();
    const add = http.expectOne(`/api/companies/c1/systems/${NOTES.id}/credentials`);
    expect(add.request.method).toBe('POST');
    expect(add.request.body).toEqual({ name: 'Server token', token: 'tok-notes' });
    add.flush(
      aSystemCredential({
        id: 'cred-notes',
        systemId: NOTES.id,
        name: 'Server token',
        grants: [],
        serviceAccounts: [],
      }),
    );
    await settle();
    expect(added.map((c) => c.id)).toEqual(['cred-notes']);
    // Read again at once, with the new credential.
    const again = http.expectOne((r) => r.url === `${TOOLS}/refresh`);
    expect(again.request.params.get('credentialId')).toBe('cred-notes');
    again.flush({
      ok: true,
      withAccount: true,
      credentialId: 'cred-notes',
      credentialName: 'Server token',
      credentialRequired: false,
      added: ['search_notes'],
      removed: [],
      unconfirmed: [],
    });
    await settle();
    http.expectOne(TOOLS).flush({ items: [CATALOGUE[0]] });
    await settle();
    fixture.detectChanges();
    expect(text(el().querySelector('[data-testid=refresh-result]'))).toBe(
      'Read the tools again. 1 tool added. Read with the credential Server token.',
    );
    expect(el().querySelector('[data-testid=tools-credential-required]')).toBeNull();
  });

  it('reads the tools with the credential picked, when the system holds several', async () => {
    await create(NOTES, CATALOGUE, true, [
      aSystemCredential({ id: 'cred-a', systemId: NOTES.id, name: 'Read token' }),
      aSystemCredential({ id: 'cred-b', systemId: NOTES.id, name: 'Write token', enabled: false }),
    ]);
    const select = el().querySelector<HTMLSelectElement>('select#tools-credential')!;
    expect(Array.from(select.options).map((o) => text(o))).toEqual([
      'With the usual account',
      'With Read token',
      'With Write token (disabled)',
    ]);
    select.value = 'cred-a';
    select.dispatchEvent(new Event('change'));
    await settle();
    el().querySelector<HTMLButtonElement>('[data-testid=refresh-tools]')!.click();
    await settle();
    const req = http.expectOne((r) => r.url === `${TOOLS}/refresh`);
    expect(req.request.params.get('credentialId')).toBe('cred-a');
    req.flush({
      ok: true,
      withAccount: true,
      credentialId: 'cred-a',
      credentialName: 'Read token',
      credentialRequired: false,
      added: [],
      removed: [],
      unconfirmed: [],
    });
    await settle();
    http.expectOne(TOOLS).flush({ items: CATALOGUE });
    await settle();
    fixture.detectChanges();
    expect(text(el().querySelector('[data-testid=refresh-result]'))).toBe(
      'Read the tools again. Nothing was added or removed. Read with the credential Read token.',
    );
  });

  it('sends a member to their own account when their own server asks for one', async () => {
    await create(aPersonalMcpServer(), []);
    el().querySelector<HTMLButtonElement>('[data-testid=refresh-tools]')!.click();
    await settle();
    http.expectOne(`/api/companies/c1/systems/${aPersonalMcpServer().id}/tools/refresh`).flush({
      ok: false,
      withAccount: false,
      credentialRequired: true,
      added: [],
      removed: [],
      unconfirmed: [],
    });
    await settle();
    http.expectOne(`/api/companies/c1/systems/${aPersonalMcpServer().id}/tools`).flush({
      items: [],
    });
    await settle();
    fixture.detectChanges();
    expect(text(el().querySelector('[data-testid=tools-credential-required]'))).toBe(
      'Connect your own account on it under Accounts, then refresh again.',
    );
    expect(el().querySelector('[data-testid=add-credential-for-tools]')).toBeNull();
  });

  it('says a refused refresh in words', async () => {
    await create(NOTES, CATALOGUE);
    el().querySelector<HTMLButtonElement>('[data-testid=refresh-tools]')!.click();
    await settle();
    http
      .expectOne(`${TOOLS}/refresh`)
      .flush(problem(409, 'SYSTEM_PAUSED'), { status: 409, statusText: 'Conflict' });
    await settle();
    // The words are problem.ts's own, for every page that meets a paused system.
    const [title, error, overrides] = notify.error.mock.calls[0];
    expect(title).toBe("Couldn't read the tools");
    expect(problemMessage(error, overrides)).toBe(
      'An admin paused this system: testing it, reading its tools and connecting accounts on it wait until it is resumed.',
    );
  });
});
