import { attachmentUrl, fileSize } from './file-labels';

describe('file labels', () => {
  it('sizes a file', () => {
    expect(fileSize(512)).toBe('512 B');
    expect(fileSize(18_432)).toBe('18.0 KiB');
    expect(fileSize(3 * 1024 * 1024)).toBe('3.0 MiB');
  });

  it('links a conversation’s file for download', () => {
    expect(attachmentUrl('c1', 'cv 1', 'att1')).toBe(
      '/api/companies/c1/conversations/cv%201/attachments/att1',
    );
  });
});
