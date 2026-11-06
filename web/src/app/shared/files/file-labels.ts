import { downloadAttachment } from '../../api/generated/functions';

/** "18.0 KiB" */
export function fileSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KiB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MiB`;
}

/**
 * A file of a conversation as a plain link: the browser downloads it with the session cookie, and
 * the server forces a download (ADR-0047).
 */
export function attachmentUrl(
  companyId: string,
  conversationId: string,
  attachmentId: string,
): string {
  return downloadAttachment.PATH.replace('{companyId}', encodeURIComponent(companyId))
    .replace('{conversationId}', encodeURIComponent(conversationId))
    .replace('{attachmentId}', encodeURIComponent(attachmentId));
}
