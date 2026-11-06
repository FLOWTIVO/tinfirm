import { HttpTestingController } from '@angular/common/http/testing';
import { type ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import type { MemoryNotes } from '../api/generated/models';
import { aMemoryNote, anAgent, IDS, someMemoryNotes } from '../testing/fixtures';
import { problem, provideTestHttp, settle } from '../testing/test-helpers';
import { words } from '../testing/words';
import { memoryGroups, type MemoryOwner, MemoryNotesPanel } from './memory-notes';

const API = '/api/companies/c1';
const MINE = `${API}/agents/${IDS.agent}/settings/me/memory`;
const TRIGGERS = `${API}/triggers/t-prepare/memory`;

describe('memoryGroups', () => {
  it('groups the accepted notes as the prompt reads them, by the agent’s names', () => {
    const groups = memoryGroups(
      [
        aMemoryNote({ number: 4, about: 'REPOSITORY', repositoryId: IDS.shopRepo }),
        aMemoryNote({ number: 5, about: 'SYSTEM', systemId: 'sys-gone' }),
        aMemoryNote({ number: 6, state: 'PROPOSED' }),
        aMemoryNote({ number: 3, about: 'SYSTEM', systemId: IDS.jira }),
        aMemoryNote({ number: 1 }),
      ],
      anAgent(),
      'About you',
    );
    expect(groups.map((g) => [g.title, g.notes.map((n) => n.id)])).toEqual([
      ['About you', ['m1']],
      ['About Jira', ['m3']],
      ['About acme/shop', ['m4']],
      ['About a system the agent no longer uses', ['m5']],
    ]);
  });
});

describe('MemoryNotesPanel', () => {
  let fixture: ComponentFixture<MemoryNotesPanel>;
  let http: HttpTestingController;
  let el: HTMLElement;
  let changed: MemoryNotes[];

  async function open(
    owner: MemoryOwner,
    memory: MemoryNotes,
    agent: ReturnType<typeof anAgent> | null = anAgent(),
  ) {
    TestBed.configureTestingModule({
      imports: [MemoryNotesPanel],
      providers: [provideRouter([]), ...provideTestHttp()],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(MemoryNotesPanel);
    fixture.componentRef.setInput('companyId', 'c1');
    fixture.componentRef.setInput('owner', owner);
    fixture.componentRef.setInput('agent', agent);
    changed = [];
    fixture.componentInstance.changed.subscribe((m) => changed.push(m));
    el = fixture.nativeElement;
    await settle();
    http.expectOne(owner.kind === 'person' ? MINE : TRIGGERS).flush(memory);
    await settle();
    await fixture.whenStable();
  }

  const person: MemoryOwner = { kind: 'person', agentId: IDS.agent };
  const trigger: MemoryOwner = { kind: 'trigger', triggerId: 't-prepare' };
  const q = (id: string) => el.querySelector<HTMLElement>(`[data-testid=${id}]`);
  const note = (n: number) => el.querySelector<HTMLElement>(`[data-note="${n}"]`)!;
  const overlay = () => document.querySelector('.cdk-overlay-container') as HTMLElement;
  const inOverlay = (id: string) => overlay().querySelector<HTMLElement>(`[data-testid=${id}]`);
  const click = async (button: HTMLElement | null) => {
    button!.click();
    await settle();
    await fixture.whenStable();
  };

  afterEach(() => {
    http.verify();
    overlay()?.replaceChildren();
  });

  it('lists a person’s notes by what they are about, with what they hold', async () => {
    await open(person, someMemoryNotes());
    expect(words(q('memory-state'))).toBe('On');
    expect(
      Array.from(el.querySelectorAll('[data-group] h3')).map((h) => h.textContent?.trim()),
    ).toEqual(['About you', 'About Jira', 'About acme/shop']);
    expect(words(note(1))).toContain('Alice wants one commit per pull request.');
    expect(words(note(1))).toContain('m1 · used');
    expect(words(q('memory-usage'))).toBe('123 of 20,000 characters');
    expect(q('memory-off')).toBeNull();
    expect(q('memory-proposals')).toBeNull();
  });

  it('deletes one note after asking, and shows the memory the server answers', async () => {
    await open(person, someMemoryNotes());
    await click(note(2).querySelector('[data-testid=delete-note]'));
    expect(words(overlay())).toContain('Delete m2?');
    expect(words(overlay())).toContain('“The tests run with make test-fast.”');

    await click(inOverlay('confirm-memory-delete'));
    const req = http.expectOne({ method: 'DELETE', url: `${MINE}/2` });
    const after = someMemoryNotes({ notes: [aMemoryNote()] });
    req.flush(after);
    await settle();
    await fixture.whenStable();
    expect(el.querySelector('[data-note="2"]')).toBeNull();
    expect(changed).toEqual([after]);
  });

  it('deletes every note after asking', async () => {
    await open(person, someMemoryNotes());
    await click(q('clear-memory'));
    expect(words(overlay())).toContain('Business analyst forgets all 3 notes. Memory stays on.');
    await click(inOverlay('confirm-memory-delete'));
    http.expectOne({ method: 'DELETE', url: MINE }).flush(someMemoryNotes({ notes: [] }));
    await settle();
    await fixture.whenStable();
    expect(words(q('memory-empty'))).toBe(
      'No notes yet. Business analyst writes one when it learns something worth keeping.',
    );
    expect(q('clear-memory')).toBeNull();
  });

  it('says memory is off, or forbidden by the agent’s editor, and keeps the notes listed', async () => {
    await open(person, someMemoryNotes({ inEffect: false }));
    expect(words(q('memory-state'))).toBe('Off');
    expect(words(q('memory-off'))).toBe(
      'Memory is off: your conversations neither read nor write these notes. Turn it on in your settings above.',
    );
    expect(note(1)).not.toBeNull();

    TestBed.resetTestingModule();
    await open(person, someMemoryNotes({ inEffect: false }), anAgent({ memoryAllowed: false }));
    expect(words(q('memory-off'))).toBe(
      'Business analyst’s editor does not allow memory: these notes are kept, but no conversation reads or writes them.',
    );
  });

  it('shows nothing to fill while memory is off and empty', async () => {
    await open(trigger, someMemoryNotes({ inEffect: false, notes: [] }));
    expect(words(q('memory-off'))).toContain('Memory is off for this trigger');
    expect(q('memory-empty')).toBeNull();
    expect(q('memory-usage')).toBeNull();
    expect(q('clear-memory')).toBeNull();
  });

  it('offers a trigger’s proposals for review, and accepts one', async () => {
    const proposed = aMemoryNote({
      number: 4,
      state: 'PROPOSED',
      text: 'Alice wants two commits per pull request.',
      replaces: 1,
      conversationId: 'conv-1',
    });
    await open(trigger, someMemoryNotes({ notes: [aMemoryNote(), proposed] }));
    const proposals = q('memory-proposals')!;
    expect(proposals.classList).toContain('border-human');
    expect(words(proposals)).toContain('1 waiting for review');
    expect(words(proposals)).toContain('m4 · about the trigger · proposed');
    expect(words(q('replaces'))).toBe('Replaces m1: “Alice wants one commit per pull request.”');
    expect(note(4).querySelector('a')?.getAttribute('href')).toBe('/c/c1/conversations/conv-1');
    expect(
      Array.from(el.querySelectorAll('[data-group] h3')).map((h) => h.textContent?.trim()),
    ).toEqual(['General']);

    await click(q('accept-note'));
    const req = http.expectOne({ method: 'POST', url: `${TRIGGERS}/4/accept` });
    req.flush(someMemoryNotes({ notes: [{ ...proposed, number: 4, id: 'm4', state: 'ACTIVE' }] }));
    await settle();
    await fixture.whenStable();
    expect(q('memory-proposals')).toBeNull();
    expect(words(note(4))).toContain('Alice wants two commits per pull request.');
  });

  it('rejects a proposal at once, and says when the memory is too full to accept one', async () => {
    const proposed = aMemoryNote({ number: 4, state: 'PROPOSED' });
    await open(trigger, someMemoryNotes({ notes: [aMemoryNote(), proposed] }));

    await click(q('accept-note'));
    http
      .expectOne({ method: 'POST', url: `${TRIGGERS}/4/accept` })
      .flush(problem(409, 'MEMORY_FULL'), { status: 409, statusText: 'Conflict' });
    await settle();
    http.expectOne(TRIGGERS).flush(someMemoryNotes({ notes: [aMemoryNote(), proposed] }));
    await settle();
    await fixture.whenStable();
    expect(words(q('memory-error'))).toBe(
      'The memory is full. Delete a note first, then accept this one.',
    );

    await click(q('reject-note'));
    http
      .expectOne({ method: 'DELETE', url: `${TRIGGERS}/4` })
      .flush(someMemoryNotes({ notes: [aMemoryNote()] }));
    await settle();
    await fixture.whenStable();
    expect(q('memory-proposals')).toBeNull();
    expect(q('memory-error')).toBeNull();
  });

  it('names what a note is about without the agent, when the reader cannot read it', async () => {
    await open(
      trigger,
      someMemoryNotes({
        notes: [aMemoryNote({ number: 2, about: 'REPOSITORY', repositoryId: IDS.shopRepo })],
      }),
      null,
    );
    expect(el.querySelector('[data-group] h3')?.textContent?.trim()).toBe('About a repository');
  });
});
