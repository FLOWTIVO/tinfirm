import { holdAttachments, MAX_ATTACHMENTS, uniqueName } from './email-attachments';
import { McpToolError, type HeldFile } from './mcp-client';
import type { AttachmentDetails, MessageRead, OfficeApi } from './office';

function office(supported = true): OfficeApi {
  return {
    onReady: () => Promise.resolve({ host: 'Outlook', platform: 'PC' }),
    context: { requirements: { isSetSupported: () => supported } },
    AsyncResultStatus: { Succeeded: 'succeeded', Failed: 'failed' },
    CoercionType: { Text: 'text' },
    EventType: { ItemChanged: '', DialogMessageReceived: '', DialogEventReceived: '' },
  };
}

function file(id: string, name: string, size: number): AttachmentDetails {
  return { id, name, size, contentType: 'text/plain', attachmentType: 'file', isInline: false };
}

function item(attachments: AttachmentDetails[]): MessageRead {
  return {
    subject: 'x',
    body: { getAsync: () => undefined },
    displayReplyForm: () => undefined,
    attachments,
    getAttachmentContentAsync: (id, callback) =>
      callback({ status: 'succeeded', value: { content: btoa(id), format: 'base64' } }),
  };
}

const held = async (name: string, _type: string, content: Blob): Promise<HeldFile> => ({
  id: `f-${name}`,
  fileName: name,
  contentType: 'text/plain',
  sizeBytes: content.size,
  expiresAt: '',
});

describe('the attachments the Outlook pane sends', () => {
  it('numbers a name another attachment has', () => {
    const taken = new Set(['report.pdf', 'report (2).pdf', 'notes']);
    expect(uniqueName('report.pdf', taken)).toBe('report (3).pdf');
    expect(uniqueName('notes', taken)).toBe('notes (2)');
    expect(uniqueName('../a\\b.txt', taken)).toBe('b.txt');
    expect(uniqueName('  ', taken)).toBe('attachment');
  });

  it('sends at most its files and megabytes, and names the rest with why', async () => {
    const many = Array.from({ length: MAX_ATTACHMENTS + 1 }, (_, i) =>
      file(`a${i}`, 'same.txt', 3),
    );
    const result = await holdAttachments(office(), item(many), true, held);
    expect(result.ids).toHaveLength(MAX_ATTACHMENTS);
    expect(result.attached.slice(0, 2)).toEqual(['same.txt', 'same (2).txt']);
    expect(result.leftOut).toEqual([`same (21).txt (more than ${MAX_ATTACHMENTS} files)`]);

    const big = await holdAttachments(
      office(),
      item([file('a', 'big.bin', 26 * 1024 * 1024), file('b', 'small.txt', 1)]),
      true,
      held,
    );
    expect(big.attached).toEqual(['small.txt']);
    expect(big.leftOut).toEqual(['big.bin (more than 25 MB of attachments in all)']);
  });

  it('names them all when this Outlook gives no attachments to add-ins', async () => {
    const result = await holdAttachments(office(false), item([file('a', 'a.txt', 1)]), true, held);
    expect(result).toEqual({
      ids: [],
      attached: [],
      leftOut: ['a.txt (this Outlook does not give attachments to add-ins)'],
    });
  });

  it('stops the start on a refusal of the person, not of the file', async () => {
    const limited = () => Promise.reject(new McpToolError('RATE_LIMITED', 'Too many', 30));
    await expect(
      holdAttachments(office(), item([file('a', 'a.txt', 1)]), true, limited),
    ).rejects.toMatchObject({ code: 'RATE_LIMITED' });
    const scanned = () => Promise.reject(new McpToolError('ATTACHMENT_NOT_SCANNABLE', 'No'));
    expect(
      (await holdAttachments(office(), item([file('a', 'a.zip', 1)]), true, scanned)).leftOut,
    ).toEqual(['a.zip (it cannot be scanned for credentials)']);
  });
});
