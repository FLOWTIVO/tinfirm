import { HttpTestingController } from '@angular/common/http/testing';
import { type ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import type { Attachment } from '../../api/generated/models';
import { anAttachment as attachment } from '../../testing/fixtures';
import { problem, provideTestHttp, settle } from '../../testing/test-helpers';
import { AttachmentPreview, previewNotice } from './attachment-preview';
import { MAX_DIFF_LINES, type DiffLine } from './preview';

describe('AttachmentPreview (ADR-0047)', () => {
  let fixture: ComponentFixture<AttachmentPreview>;
  let http: HttpTestingController;
  let closed = 0;

  const overlay = () => document.querySelector('.cdk-overlay-container') as HTMLElement;
  const body = () => overlay().querySelector('[data-testid=preview-body]')!;

  async function create(file: Attachment | null, siblings: Attachment[] = []) {
    TestBed.configureTestingModule({
      imports: [AttachmentPreview],
      providers: [provideRouter([]), ...provideTestHttp()],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(AttachmentPreview);
    fixture.componentRef.setInput('companyId', 'c1');
    fixture.componentRef.setInput('conversationId', 'cv1');
    fixture.componentRef.setInput('siblings', siblings);
    fixture.componentRef.setInput('file', file);
    fixture.componentInstance.closed.subscribe(() => closed++);
    // Not whenStable(): a loading resource is a pending task until its request is answered.
    await settle();
  }

  /** The one download of the file, answered with the given bytes. */
  async function serve(id: string, content: BlobPart, type = 'text/plain') {
    const req = http.expectOne({
      method: 'GET',
      url: `/api/companies/c1/conversations/cv1/attachments/${id}`,
    });
    req.flush(new Blob([content], { type }));
    await settle();
    await fixture.whenStable();
  }

  beforeEach(() => (closed = 0));
  afterEach(() => {
    http.verify();
    overlay()?.replaceChildren();
  });

  it('is closed without a file', async () => {
    await create(null);
    expect(overlay()?.querySelector('[data-testid=attachment-preview]')).toBeFalsy();
  });

  it('shows the name, the size and a download link, and plain text as text', async () => {
    await create(attachment({ id: 'a1', fileName: 'notes.txt', contentType: 'text/plain' }));
    expect(overlay().querySelector('[data-testid=preview-name]')?.textContent?.trim()).toBe(
      'notes.txt',
    );
    expect(overlay().textContent).toContain('18.0 KiB');
    expect(body().querySelector('[aria-busy=true]')).not.toBeNull();
    await serve('a1', 'Hello <b>world</b>\nline 2');
    const pre = body().querySelector('[data-testid=preview-text]')!;
    expect(pre.textContent).toBe('Hello <b>world</b>\nline 2');
    expect(pre.querySelector('b')).toBeNull();
    const download = overlay().querySelector('[data-testid=preview-download]')!;
    expect(download.getAttribute('href')).toBe(
      '/api/companies/c1/conversations/cv1/attachments/a1',
    );
    expect(download.getAttribute('download')).toBe('notes.txt');
    expect(overlay().querySelector('[data-testid=preview-notice]')).toBeNull();
  });

  it("renders Markdown, with the document's own images and links pointing at its siblings", async () => {
    const doc = attachment({ id: 'doc', fileName: 'reports/q3.md', contentType: 'text/markdown' });
    const chart = attachment({
      id: 'img',
      fileName: 'reports/chart.png',
      contentType: 'image/png',
    });
    const data = attachment({ id: 'csv', fileName: 'data/q3.csv', contentType: 'text/csv' });
    await create(doc, [doc, chart, data]);
    await serve(
      'doc',
      '# Q3\n\n![Revenue](chart.png) and [the data](../data/q3.csv), ' +
        '![ext](https://example.com/x.png) and <script>alert(1)</script>',
      'text/markdown',
    );
    const md = body().querySelector('app-markdown')!;
    expect(md.querySelector('h1')?.textContent).toBe('Q3');
    const img = md.querySelector('img')!;
    expect(img.getAttribute('src')).toBe('/api/companies/c1/conversations/cv1/attachments/img');
    expect(img.getAttribute('alt')).toBe('Revenue');
    const links = Array.from(md.querySelectorAll('a'));
    expect(links.map((a) => a.getAttribute('href'))).toEqual([
      '/api/companies/c1/conversations/cv1/attachments/csv',
      'https://example.com/x.png',
    ]);
    expect(md.querySelector('script')).toBeNull();
    expect(md.textContent).toContain('<script>alert(1)</script>');
  });

  it('shows CSV as a table', async () => {
    await create(attachment({ id: 'a1', fileName: 'export.csv', contentType: 'text/csv' }));
    await serve('a1', 'name,amount\n"Smith, J",10\nLee,20\n', 'text/csv');
    const table = body().querySelector('[data-testid=preview-csv]')!;
    expect(Array.from(table.querySelectorAll('th')).map((th) => th.textContent)).toEqual([
      'name',
      'amount',
    ]);
    expect(Array.from(table.querySelectorAll('tbody tr')).map((tr) => tr.textContent)).toEqual([
      'Smith, J10',
      'Lee20',
    ]);
  });

  it('colours the lines of a diff', async () => {
    await create(attachment({ id: 'a1', fileName: 'fix.patch', contentType: 'text/x-diff' }));
    await serve('a1', '--- a\n+++ b\n@@ -1 +1 @@\n-old\n+new\n', 'text/x-diff');
    const rows = Array.from(body().querySelectorAll('[data-testid=preview-diff] tr'));
    expect(rows.map((r) => r.getAttribute('data-type'))).toEqual([
      'meta',
      'meta',
      'hunk',
      'del',
      'add',
    ]);
    expect(rows[3].classList.contains('bg-diff-del')).toBe(true);
    expect(rows[4].textContent).toContain('added: new');
  });

  it('indents JSON and shows code as it is', async () => {
    await create(attachment({ id: 'a1', fileName: 'out.json', contentType: 'application/json' }));
    await serve('a1', '{"a":[1]}', 'application/json');
    expect(body().querySelector('[data-testid=preview-code]')?.textContent).toBe(
      '{\n  "a": [\n    1\n  ]\n}',
    );
  });

  it('shows an image from its download URL without reading it, and says when it fails', async () => {
    await create(attachment({ id: 'a1', fileName: 'chart.svg', contentType: 'image/svg+xml' }));
    http.expectNone({ method: 'GET', url: '/api/companies/c1/conversations/cv1/attachments/a1' });
    const img = body().querySelector<HTMLImageElement>('[data-testid=preview-image]')!;
    expect(img.getAttribute('src')).toBe('/api/companies/c1/conversations/cv1/attachments/a1');
    expect(img.getAttribute('alt')).toBe('chart.svg');
    expect(body().querySelector('[data-testid=preview-image-failed]')).toBeNull();
    img.dispatchEvent(new Event('error'));
    await settle();
    expect(body().querySelector('[data-testid=preview-image-failed]')).not.toBeNull();
  });

  it('says when it shows only the start of a long file', async () => {
    await create(
      attachment({
        id: 'a1',
        fileName: 'big.log',
        contentType: 'text/plain',
        sizeBytes: 3_000_000,
      }),
    );
    await serve('a1', 'line\n'.repeat(300_000));
    expect(overlay().querySelector('[data-testid=preview-notice]')?.textContent).toContain(
      'Showing the first 1.0 MiB of 2.9 MiB',
    );
  });

  it('says when it shows only the first rows of a CSV', async () => {
    await create(attachment({ id: 'a1', fileName: 'big.csv', contentType: 'text/csv' }));
    await serve('a1', 'n\n' + '1\n'.repeat(600), 'text/csv');
    expect(body().querySelectorAll('tbody tr')).toHaveLength(500);
    expect(overlay().querySelector('[data-testid=preview-notice]')?.textContent).toContain(
      'Showing the first 500 rows',
    );
  });

  it('says when it shows only the first rows and columns of a wide CSV', async () => {
    await create(attachment({ id: 'a1', fileName: 'wide.csv', contentType: 'text/csv' }));
    const header = Array.from({ length: 101 }, (_, i) => `c${i}`).join(',') + '\n';
    await serve('a1', header + '1\n'.repeat(600), 'text/csv');
    expect(body().querySelectorAll('th')).toHaveLength(100);
    expect(body().querySelectorAll('tbody tr')).toHaveLength(500);
    expect(overlay().querySelector('[data-testid=preview-notice]')?.textContent).toContain(
      'Showing the first 500 rows and the first 100 columns. Download the file for all of it.',
    );
  });

  // Not through the dialog: jsdom takes the better part of a minute to insert 5000 rows.
  it('says when it shows only the first lines of a diff', () => {
    const file = attachment({ id: 'a1', fileName: 'big.patch', sizeBytes: 3_000_000 });
    const line: DiffLine = { type: 'add', text: 'x' };
    const lines = Array.from({ length: MAX_DIFF_LINES }, () => line);
    expect(previewNotice(file, { kind: 'diff', lines, truncated: true })).toBe(
      'Showing the first 5000 lines. Download the file for all of it.',
    );
    // Cut short by the bytes read, within the lines: it is the bytes that are named.
    expect(previewNotice(file, { kind: 'diff', lines: lines.slice(0, 10), truncated: true })).toBe(
      'Showing the first 1.0 MiB of 2.9 MiB. Download the file for all of it.',
    );
    expect(previewNotice(file, { kind: 'diff', lines, truncated: false })).toBeNull();
  });

  it('shows the error of a failed download, with retry', async () => {
    await create(attachment({ id: 'a1', fileName: 'notes.txt' }));
    http
      .expectOne({ method: 'GET', url: '/api/companies/c1/conversations/cv1/attachments/a1' })
      .flush(new Blob([JSON.stringify(problem(500, 'INTERNAL', 'Database down'))]), {
        status: 500,
        statusText: 'Error',
      });
    await settle();
    await fixture.whenStable();
    expect(body().querySelector('[role=alert]')?.textContent).toContain("Couldn't load the file");
    Array.from(body().querySelectorAll('button'))
      .find((b) => b.textContent?.includes('Try again'))!
      .click();
    await settle();
    await serve('a1', 'now it works');
    expect(body().querySelector('[data-testid=preview-text]')?.textContent).toBe('now it works');
  });

  it('offers only the download for a file it cannot show', async () => {
    await create(attachment({ id: 'a1', fileName: 'report.pdf', contentType: 'application/pdf' }));
    http.expectNone({ method: 'GET', url: '/api/companies/c1/conversations/cv1/attachments/a1' });
    expect(body().querySelector('[data-testid=preview-none]')?.textContent).toContain(
      'No preview for this file',
    );
  });

  it('tells the parent when it is closed', async () => {
    await create(attachment({ id: 'a1', fileName: 'notes.txt' }));
    await serve('a1', 'x');
    Array.from(overlay().querySelectorAll('button'))
      .find((b) => b.textContent?.trim() === 'Close')!
      .click();
    await settle();
    expect(closed).toBe(1);
  });
});
