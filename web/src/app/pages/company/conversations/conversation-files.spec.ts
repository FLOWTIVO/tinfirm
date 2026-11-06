import { HttpTestingController, type TestRequest } from '@angular/common/http/testing';
import { type ComponentFixture, TestBed } from '@angular/core/testing';
import type { Attachment } from '../../../api/generated/models';
import { anAttachment, AT, IDS } from '../../../testing/fixtures';
import { fakeNotify, problem, provideTestHttp, settle } from '../../../testing/test-helpers';
import { ConversationFiles, MAX_FILE_BYTES } from './conversation-files';

const URL = '/api/companies/c1/conversations/cv1/attachments';

const OUTPUT_DOC = anAttachment({
  id: 'att-doc',
  fileName: 'reports/brief.md',
  contentType: 'text/markdown',
  uploadedBy: 'Business analyst',
  agentId: IDS.agent,
  outputId: 'out-doc',
});

interface Options {
  readonly mine?: boolean;
  readonly ended?: boolean;
  readonly mayAcknowledge?: boolean;
}

describe('ConversationFiles', () => {
  let fixture: ComponentFixture<ConversationFiles>;
  let el: HTMLElement;
  let http: HttpTestingController;
  let notify: ReturnType<typeof fakeNotify>['notify'];
  let added: Attachment[];
  let deleted: string[];

  async function open(files: Attachment[], options: Options = {}): Promise<void> {
    const notifier = fakeNotify();
    notify = notifier.notify;
    TestBed.configureTestingModule({
      imports: [ConversationFiles],
      providers: [...provideTestHttp(), notifier.provider],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ConversationFiles);
    fixture.componentRef.setInput('companyId', 'c1');
    fixture.componentRef.setInput('conversationId', IDS.conversation);
    fixture.componentRef.setInput('attachments', files);
    fixture.componentRef.setInput('mine', options.mine ?? true);
    fixture.componentRef.setInput('personName', 'Alice Novak');
    fixture.componentRef.setInput('ended', options.ended ?? false);
    fixture.componentRef.setInput('mayAcknowledge', options.mayAcknowledge ?? false);
    added = [];
    deleted = [];
    fixture.componentInstance.added.subscribe((a) => added.push(a));
    fixture.componentInstance.deleted.subscribe((id) => deleted.push(id));
    el = fixture.nativeElement;
    await settle();
  }

  afterEach(() => http.verify());

  const by = (testId: string, root: ParentNode = el) =>
    root.querySelector<HTMLElement>(`[data-testid=${testId}]`);
  const text = (node: Element | null | undefined) => node?.textContent?.replace(/\s+/g, ' ').trim();
  const rows = () => [...el.querySelectorAll<HTMLElement>('[data-testid=file]')];

  /** Picks a file in the hidden input, as the browser does. */
  async function pick(file: File): Promise<void> {
    const input = by('file-input') as HTMLInputElement;
    Object.defineProperty(input, 'files', { value: [file], configurable: true });
    input.dispatchEvent(new Event('change'));
    await settle();
  }

  function expectUpload(): TestRequest {
    const req = http.expectOne({ method: 'POST', url: URL });
    expect(req.request.body).toBeInstanceOf(FormData);
    return req;
  }

  it.each<[string, Options, boolean, string | undefined]>([
    ['its person, while it is open', {}, true, undefined],
    [
      'the Owner reading it',
      { mine: false },
      false,
      'Only Alice Novak adds and removes files here.',
    ],
    [
      'its person, once it ended',
      { ended: true },
      false,
      'The conversation has ended: its files stay.',
    ],
  ])('lets %s add files or says why not', async (_, options, canAdd, note) => {
    await open([anAttachment()], options);
    expect(by('add-file') !== null).toBe(canAdd);
    expect(rows()[0].querySelector('[data-testid=file-delete]') !== null).toBe(canAdd);
    expect(text(by('files-note'))).toBe(note);
    // Everyone who sees the conversation previews and downloads its files.
    expect(rows()[0].querySelector('[data-testid=file-preview]')).not.toBeNull();
  });

  it('says so when there is no file', async () => {
    await open([]);
    expect(text(by('files-empty'))).toBe(
      'No files yet. What you add here, every turn reads under /attachments.',
    );
  });

  it('describes each file, and keeps an output’s document', async () => {
    await open([anAttachment({ note: 'Last week’s export' }), OUTPUT_DOC]);
    const [mine, doc] = rows();
    expect(text(mine)).toContain('export.csv');
    expect(text(mine)).toContain('18.0 KiB · added by Alice Novak');
    expect(text(mine)).toContain('Last week’s export');
    expect(by('file-download', mine)?.getAttribute('href')).toBe(`${URL}/att1`);
    expect(by('file-download', mine)?.getAttribute('download')).toBe('export.csv');

    expect(text(doc)).toContain('saved by Business analyst');
    expect(by('file-delete', doc)).toBeNull();
    expect(text(by('file-is-output', doc))).toBe(
      'An output’s document: it stays while its output does.',
    );
  });

  it('says when retention purged a file, and offers no preview or download', async () => {
    await open([anAttachment({ contentPurgedAt: AT.finished })]);
    const [row] = rows();
    expect(row.dataset['purged']).toBe('true');
    expect(text(by('file-purged', row))).toBe(
      'Removed on Oct 12, 2026 under the company’s retention. Its name, size and hash stay.',
    );
    expect(by('file-preview', row)).toBeNull();
    expect(by('file-download', row)).toBeNull();
  });

  it('adds a file with an idempotency key of its own', async () => {
    await open([]);
    await pick(new File(['a,b'], 'notes.csv', { type: 'text/csv' }));
    expect(by('upload')?.getAttribute('aria-busy')).toBe('true');
    const req = expectUpload();
    const key = req.request.headers.get('Idempotency-Key');
    expect(key).toMatch(/^[0-9a-f-]{36}$/);
    expect((req.request.body as FormData).get('acknowledgeCredentialMatch')).toBeNull();
    const stored = anAttachment({ id: 'att9', fileName: 'notes.csv', sizeBytes: 3 });
    req.flush(stored, { status: 201, statusText: 'Created' });
    await settle();

    expect(rows().map((r) => r.dataset['fileId'])).toEqual(['att9']);
    expect(by('upload')).toBeNull();
    expect(added).toEqual([stored]);
    expect(notify.success).toHaveBeenCalledWith('Added notes.csv');

    // The next file is another submission, with another key.
    await pick(new File(['x'], 'second.txt'));
    const next = expectUpload();
    expect(next.request.headers.get('Idempotency-Key')).not.toBe(key);
    next.flush(anAttachment({ id: 'att10', fileName: 'second.txt' }));
    await settle();
  });

  it('retries a failed upload with the same key', async () => {
    await open([]);
    await pick(new File(['a'], 'notes.txt'));
    const first = expectUpload();
    const key = first.request.headers.get('Idempotency-Key');
    first.flush(problem(503), { status: 503, statusText: 'Unavailable' });
    await settle();
    expect(text(by('upload-error'))).toContain('Something went wrong on the server');

    by('upload-retry')!.click();
    await settle();
    const retry = expectUpload();
    expect(retry.request.headers.get('Idempotency-Key')).toBe(key);
    retry.flush(anAttachment({ id: 'att9', fileName: 'notes.txt' }), {
      status: 201,
      statusText: 'Created',
      headers: { 'Idempotent-Replayed': 'true' },
    });
    await settle();
    expect(rows()).toHaveLength(1);
  });

  it.each<[string, number, string, string]>([
    [
      'a duplicate name',
      409,
      'DUPLICATE_ATTACHMENT_NAME',
      'This conversation already has a file with this name.',
    ],
    [
      'too many files',
      409,
      'TOO_MANY_ATTACHMENTS',
      'This conversation has as many files as it can hold.',
    ],
    [
      'a file it cannot scan',
      422,
      'ATTACHMENT_NOT_SCANNABLE',
      'The file could not be scanned for credentials, so it is not stored.',
    ],
    [
      'an ended conversation',
      409,
      'CONVERSATION_ENDED',
      'This conversation has ended: its files stay, and it takes no new ones.',
    ],
  ])('names a refusal: %s, without a retry', async (_, status, code, message) => {
    await open([]);
    await pick(new File(['a'], 'notes.txt'));
    expectUpload().flush(problem(status, code), { status, statusText: 'Refused' });
    await settle();
    expect(text(by('upload-error'))).toBe(message);
    expect(by('upload-retry')).toBeNull();
    by('upload-dismiss')!.click();
    await settle();
    expect(by('upload')).toBeNull();
  });

  it('refuses a file over the limit before sending it', async () => {
    await open([]);
    const big = new File(['x'], 'dump.bin');
    Object.defineProperty(big, 'size', { value: MAX_FILE_BYTES + 1 });
    await pick(big);
    expect(text(by('upload-error'))).toBe(
      'dump.bin is 25.0 MiB; a file may have at most 25.0 MiB.',
    );
  });

  it('names the credentials the scan found, and never offers to store a vault secret', async () => {
    await open([], { mayAcknowledge: true });
    await pick(new File(['k'], 'env.txt'));
    expectUpload().flush(
      {
        ...problem(422, 'CREDENTIAL_FOUND'),
        credentialMatches: [
          { kind: 'VAULT_SECRET', name: 'JIRA_TOKEN' },
          { kind: 'FORMAT', name: 'GitHub token' },
        ],
      },
      { status: 422, statusText: 'Unprocessable' },
    );
    await settle();
    expect(text(by('upload-error'))).toContain('The file contains a credential');
    expect(text(by('upload-matches'))).toBe(
      'The value of the secret JIRA_TOKEN from the company’s vault A GitHub token credential',
    );
    expect(by('upload-acknowledge')).toBeNull();
    expect(by('upload-anyway')).toBeNull();
  });

  it.each<[string, boolean]>([
    ['an owner or admin acknowledges a credential format', true],
    ['a member cannot', false],
  ])('%s', async (_, mayAcknowledge) => {
    await open([], { mayAcknowledge });
    await pick(new File(['k'], 'env.txt'));
    const first = expectUpload();
    const key = first.request.headers.get('Idempotency-Key');
    first.flush(
      {
        ...problem(422, 'CREDENTIAL_FOUND'),
        credentialMatches: [{ kind: 'FORMAT', name: 'AWS key' }],
      },
      { status: 422, statusText: 'Unprocessable' },
    );
    await settle();
    if (!mayAcknowledge) {
      expect(by('upload-anyway')).toBeNull();
      return;
    }
    const anyway = by('upload-anyway') as HTMLButtonElement;
    expect(anyway.disabled).toBe(true);
    (by('upload-acknowledge')!.querySelector('input') as HTMLInputElement).click();
    await settle();
    expect(anyway.disabled).toBe(false);
    anyway.click();
    await settle();
    const second = expectUpload();
    expect((second.request.body as FormData).get('acknowledgeCredentialMatch')).toBe('true');
    expect(second.request.headers.get('Idempotency-Key')).not.toBe(key);
    second.flush(anAttachment({ id: 'att9', fileName: 'env.txt' }));
    await settle();
    expect(rows()).toHaveLength(1);
  });

  it('deletes a file after confirming', async () => {
    await open([anAttachment()]);
    by('file-delete', rows()[0])!.click();
    await settle();
    const dialog = by('delete-file-dialog', document)!;
    expect(text(dialog)).toContain('Delete export.csv?');
    (by('confirm-delete-file', document) as HTMLButtonElement).click();
    await settle();
    http.expectOne({ method: 'DELETE', url: `${URL}/att1` }).flush(null, {
      status: 204,
      statusText: 'No Content',
    });
    await settle();
    expect(rows()).toHaveLength(0);
    expect(deleted).toEqual(['att1']);
  });

  it('explains a file the server keeps as an output', async () => {
    await open([anAttachment()]);
    by('file-delete', rows()[0])!.click();
    await settle();
    (by('confirm-delete-file', document) as HTMLButtonElement).click();
    await settle();
    http
      .expectOne({ method: 'DELETE', url: `${URL}/att1` })
      .flush(problem(409, 'ATTACHMENT_IS_OUTPUT'), { status: 409, statusText: 'Conflict' });
    await settle();
    expect(text(by('delete-file-error', document))).toBe(
      'The agent saved this file as an output: it stays with its output and cannot be removed.',
    );
    expect(rows()).toHaveLength(1);
    expect(deleted).toEqual([]);
  });

  it('says a purged file cannot be downloaded when the preview meets 410', async () => {
    await open([anAttachment({ fileName: 'notes.txt', contentType: 'text/plain' })]);
    by('file-preview', rows()[0])!.click();
    await settle();
    http.expectOne(`${URL}/att1`).flush(new Blob(['{}']), { status: 410, statusText: 'Gone' });
    await settle();
    expect(text(by('preview-body', document))).toContain(
      'The company’s retention removed this file’s content.',
    );
  });
});
