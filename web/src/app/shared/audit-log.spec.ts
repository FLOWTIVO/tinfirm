import { HttpTestingController } from '@angular/common/http/testing';
import { type ComponentFixture, TestBed } from '@angular/core/testing';
import type { AuditEvent } from '../api/generated/models';
import { provideTestHttp, settle } from '../testing/test-helpers';
import { words } from '../testing/words';
import { AuditLog } from './audit-log';

const EVENTS = '/api/admin/audit-events';
const USER = '0192f0c4-7d1e-7c3a-9b4e-2f1a3c5d7e9f';

function anEvent(seq: number, overrides: Partial<AuditEvent> = {}): AuditEvent {
  return {
    id: `e${seq}`,
    seq,
    occurredAt: '2026-10-12T08:00:00Z',
    actor: { type: 'USER', id: USER, display: 'Olga Owner' },
    action: 'user.created',
    targetType: 'user',
    targetId: USER,
    hash: 'a'.repeat(64),
    prevHash: 'b'.repeat(64),
    ...overrides,
  };
}

describe('AuditLog, the instance’s chain', () => {
  let fixture: ComponentFixture<AuditLog>;
  let http: HttpTestingController;
  let el: HTMLElement;

  beforeEach(async () => {
    TestBed.configureTestingModule({ imports: [AuditLog], providers: [...provideTestHttp()] });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(AuditLog);
    fixture.componentRef.setInput('scope', { kind: 'instance' });
    el = fixture.nativeElement;
    await settle();
  });
  afterEach(() => http.verify());

  const rows = () =>
    Array.from(el.querySelectorAll('[data-testid=audit-event]')).map((r) =>
      words(r.querySelector('button')),
    );

  it('reads its events in pages, without filters to offer', async () => {
    http
      .expectOne((r) => r.url === EVENTS)
      .flush({
        items: [
          anEvent(2),
          anEvent(1, { actor: { type: 'SYSTEM', id: null, display: 'system' }, details: {} }),
        ],
        nextCursor: 'next',
      });
    await settle();
    expect(rows()[0]).toMatch(/^\d\d:\d\d:\d\d OO Olga Owner user. created User · 0192f0c4 2$/);
    expect(rows()[1]).toMatch(/ T Tinfirm user. created /);
    expect(words(el.querySelector('h3'))).toMatch(/ 2\+ events$/);

    el.querySelector<HTMLButtonElement>('[aria-controls="audit-details-e2"]')!.click();
    await settle();
    expect(el.querySelector('[data-testid=quick-filter]')).toBeNull();
    expect(words(el.querySelector('[data-testid=audit-details]'))).toContain('No details');

    Array.from(el.querySelectorAll('button'))
      .find((b) => words(b) === 'Load more')!
      .click();
    await settle();
    const more = http.expectOne((r) => r.url === EVENTS);
    expect(more.request.params.get('cursor')).toBe('next');
    more.flush({ items: [anEvent(0, { prevHash: '0'.repeat(64) })], nextCursor: null });
    await settle();
    expect(rows()).toHaveLength(3);
    expect(words(el.querySelector('[data-testid=audit-end]'))).toBe(
      '3 events · start of the chain',
    );
  });

  it('names each user as the server named them, and one who is gone by their id alone', async () => {
    http
      .expectOne((r) => r.url === EVENTS)
      .flush({
        items: [
          anEvent(2, { targetName: 'Olga Owner' }),
          anEvent(1, {
            targetName: null,
            actor: { type: 'USER', id: USER, display: 'deleted user' },
          }),
        ],
        nextCursor: null,
      });
    await settle();
    expect(rows()[0]).toMatch(/ user\. created User Olga Owner · 0192f0c4 2$/);
    expect(rows()[1]).toMatch(/ \? Deleted user user\. created User · 0192f0c4 1$/);
  });

  it('says when the chain holds nothing yet', async () => {
    http.expectOne((r) => r.url === EVENTS).flush({ items: [] });
    await settle();
    expect(words(el.querySelector('[data-testid=audit-empty]'))).toContain('No events yet');
    expect(el.textContent).not.toContain('Clear the filters');
  });
});
