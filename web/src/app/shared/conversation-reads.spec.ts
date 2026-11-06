import { HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import type { ConversationReader } from '../api/generated/models';
import { IDS } from '../testing/fixtures';
import { provideTestHttp, settle } from '../testing/test-helpers';
import { words } from '../testing/words';
import { ConversationReadsButton, readsSummary } from './conversation-reads';

const OLGA: ConversationReader = {
  userId: 'u-olga',
  name: 'Olga Owner',
  firstReadAt: '2026-10-12T09:00:00Z',
  lastReadAt: '2026-10-12T09:00:00Z',
  reads: 1,
  kinds: ['conversation'],
};

describe('readsSummary', () => {
  it.each<[string, ConversationReader[], string]>([
    ['nobody', [], 'nobody has'],
    ['one read', [OLGA], 'read once'],
    ['three reads', [{ ...OLGA, reads: 3 }], 'read 3 times'],
    ['two readers', [OLGA, { ...OLGA, userId: 'u-other', name: 'Otto Owner' }], '2 people read it'],
  ])('says who read it in a few words: %s', (_case, readers, says) => {
    expect(readsSummary(readers)).toBe(says);
  });
});

describe('ConversationReadsButton', () => {
  it('reads the list once, says it on the button, and lists it in its popover', async () => {
    TestBed.configureTestingModule({ providers: [...provideTestHttp()] });
    const http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(ConversationReadsButton);
    fixture.componentRef.setInput('companyId', 'c1');
    fixture.componentRef.setInput('conversationId', IDS.conversation);
    await settle();
    http.expectOne(`/api/companies/c1/conversations/${IDS.conversation}/reads`).flush([OLGA]);
    await settle();
    const el: HTMLElement = fixture.nativeElement;
    const button = el.querySelector<HTMLButtonElement>('[data-testid=owner-notice]')!;
    expect(words(button)).toBe('The Owner can read this · read once');
    expect(button.getAttribute('aria-expanded')).toBe('false');

    button.click();
    await settle();
    const popover = el.querySelector('[data-testid=reads-popover]')!;
    expect(button.getAttribute('aria-controls')).toBe(popover.id);
    expect(popover.getAttribute('aria-label')).toBe('Who else read it');
    expect(words(popover)).toContain('Olga Owner read the conversation, once.');
    // The list it read is the popover's: nothing is read again.
    http.verify();
  });
});
