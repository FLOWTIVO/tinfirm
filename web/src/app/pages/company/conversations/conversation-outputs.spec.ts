import { HttpTestingController } from '@angular/common/http/testing';
import { type ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import type { Attachment, Output } from '../../../api/generated/models';
import { anAttachment, anOutput, AT, IDS } from '../../../testing/fixtures';
import { provideTestHttp, settle } from '../../../testing/test-helpers';
import { ConversationOutputs } from './conversation-outputs';

const DOC_FILE = anAttachment({
  id: 'att-brief',
  fileName: 'reports/brief.md',
  contentType: 'text/markdown',
  sizeBytes: 2048,
  uploadedBy: 'Business analyst',
  agentId: IDS.agent,
  runId: IDS.run,
  outputId: 'out-doc',
});

const DOCUMENT = anOutput({
  id: 'out-doc',
  kind: 'DOCUMENT',
  title: 'The brief',
  url: null,
  repositoryId: null,
  repositoryName: null,
  externalNumber: null,
  changeRequestState: null,
  attachmentId: DOC_FILE.id,
});

const LINK = anOutput({
  id: 'out-link',
  kind: 'LINK',
  title: 'The guest checkout dashboard',
  url: 'https://grafana.acme.test/d/checkout',
  repositoryId: null,
  repositoryName: null,
  externalNumber: null,
  changeRequestState: null,
});

describe('ConversationOutputs', () => {
  let fixture: ComponentFixture<ConversationOutputs>;
  let el: HTMLElement;
  let http: HttpTestingController;

  async function open(outputs: Output[], attachments: Attachment[] = []): Promise<void> {
    TestBed.configureTestingModule({
      imports: [ConversationOutputs],
      providers: [provideRouter([]), ...provideTestHttp()],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ConversationOutputs);
    fixture.componentRef.setInput('companyId', 'c1');
    fixture.componentRef.setInput('conversationId', IDS.conversation);
    fixture.componentRef.setInput('outputs', outputs);
    fixture.componentRef.setInput('attachments', attachments);
    fixture.componentRef.setInput('turns', [{ id: IDS.run, turnNumber: 1 }]);
    el = fixture.nativeElement;
    await settle();
  }

  afterEach(() => http.verify());

  const rows = () => [...el.querySelectorAll<HTMLElement>('[data-testid=output]')];
  const text = (node: Element | null | undefined) => node?.textContent?.replace(/\s+/g, ' ').trim();

  it('says so when the conversation produced nothing yet', async () => {
    await open([]);
    expect(text(el.querySelector('[data-testid=outputs-empty]'))).toContain('Nothing yet');
    expect(rows()).toHaveLength(0);
  });

  it.each<[NonNullable<Output['changeRequestState']>, string, string]>([
    ['OPEN', 'Open', 'primary'],
    ['DRAFT', 'Draft', 'muted'],
    ['MERGED', 'Merged', 'success'],
    ['CLOSED', 'Closed', 'muted'],
    ['UNKNOWN', 'State unknown', 'muted'],
  ])(
    'shows a change request %s with its state, repository and number',
    async (state, label, tone) => {
      await open([anOutput({ changeRequestState: state })]);
      const [row] = rows();
      expect(row.dataset['kind']).toBe('CHANGE_REQUEST');
      const link = row.querySelector<HTMLAnchorElement>('[data-testid=output-link]')!;
      expect(link.href).toBe('https://github.com/acme/docs/pull/12');
      expect(link.target).toBe('_blank');
      expect(link.rel).toContain('noopener');
      expect(text(link)).toContain('Validate the guest address');
      const chip = row.querySelector('[data-testid=change-request-state]')!;
      expect(text(chip)).toBe(label);
      expect(chip.className).toContain(tone);
      expect(chip.className).not.toContain('human');
      expect(text(row)).toContain('acme/docs #12');
      expect(row.querySelector('[data-testid=output-run]')?.getAttribute('href')).toBe(
        '/c/c1/runs/run1',
      );
      expect(text(row.querySelector('[data-testid=output-run]'))).toBe('From turn 1 · View run');
    },
  );

  it('names a removed repository', async () => {
    await open([anOutput({ repositoryId: null, repositoryName: null })]);
    expect(text(rows()[0])).toContain('A removed repository #12');
  });

  it('previews and downloads a document, and lists outputs by kind', async () => {
    await open([LINK, DOCUMENT, anOutput()], [DOC_FILE]);
    expect(rows().map((r) => r.dataset['kind'])).toEqual(['CHANGE_REQUEST', 'DOCUMENT', 'LINK']);

    const doc = rows()[1];
    expect(text(doc.querySelector('[data-testid=output-title]'))).toBe('The brief');
    expect(text(doc)).toContain('reports/brief.md · 2.0 KiB');
    expect(doc.querySelector('[data-testid=document-download]')?.getAttribute('href')).toBe(
      '/api/companies/c1/conversations/cv1/attachments/att-brief',
    );

    doc.querySelector<HTMLButtonElement>('[data-testid=document-preview]')!.click();
    await settle();
    http
      .expectOne('/api/companies/c1/conversations/cv1/attachments/att-brief')
      .flush(new Blob(['# The brief\n\nGuests skip the address check.']));
    await settle();
    const dialog = document.querySelector('[data-testid=attachment-preview]')!;
    expect(text(dialog.querySelector('[data-testid=preview-name]'))).toBe('reports/brief.md');
    expect(text(dialog.querySelector('[data-testid=preview-body]'))).toContain(
      'Guests skip the address check.',
    );

    const link = rows()[2].querySelector<HTMLAnchorElement>('[data-testid=output-link]')!;
    expect(link.href).toBe('https://grafana.acme.test/d/checkout');
  });

  it.each<[string, Output[], Attachment[], string]>([
    [
      'an output',
      [anOutput({ title: '', purgedAt: AT.finished, url: null })],
      [],
      'Removed on Oct 12, 2026 under the company’s retention',
    ],
    [
      'a document’s file',
      [DOCUMENT],
      [{ ...DOC_FILE, contentPurgedAt: AT.finished }],
      'reports/brief.md · Removed on Oct 12, 2026 under the company’s retention',
    ],
  ])('says when retention removed %s, and offers no preview', async (_, outputs, files, says) => {
    await open(outputs, files);
    const [row] = rows();
    expect(text(row)).toContain(says);
    expect(row.querySelector('[data-testid=document-preview]')).toBeNull();
    expect(row.querySelector('[data-testid=document-download]')).toBeNull();
    expect(row.querySelector('[data-testid=output-link]')).toBeNull();
  });
});
