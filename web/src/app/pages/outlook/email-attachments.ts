import { McpToolError, type HeldFile } from './mcp-client';
import { attachmentContent, readsAttachments, type MessageRead, type OfficeApi } from './office';

/** The attachments a start sends at most, as the Gmail add-on's. */
export const MAX_ATTACHMENTS = 20;

/** The megabytes of attachments a start sends at most, in all. */
export const MAX_ATTACHMENT_MEGABYTES = 25;

const MAX_ATTACHMENT_BYTES = MAX_ATTACHMENT_MEGABYTES * 1024 * 1024;

/** The refusals that stop a start, whichever file they came with. */
const STOPS = new Set(['RATE_LIMITED', 'INSUFFICIENT_SCOPE', 'EDITION_REQUIRED']);

/** The attachments held for a start: their ids, the names the request gives them, those left out. */
export interface HeldAttachments {
  ids: string[];
  attached: string[];
  leftOut: string[];
}

/** The open email's attachments that are not images of its body. */
export function attachmentsOf(item: MessageRead): NonNullable<MessageRead['attachments']> {
  return (item.attachments ?? []).filter((attachment) => !attachment.isInline);
}

/** The attachment's name, and a number before its extension when another attachment has it. */
export function uniqueName(original: string, taken: ReadonlySet<string>): string {
  let name = original.slice(Math.max(original.lastIndexOf('/'), original.lastIndexOf('\\')) + 1);
  name = name.trim();
  if (!name || name === '.' || name === '..') name = 'attachment';
  if (!taken.has(name)) return name;
  const dot = name.lastIndexOf('.');
  const stem = dot > 0 ? name.slice(0, dot) : name;
  const extension = dot > 0 ? name.slice(dot) : '';
  for (let number = 2; ; number++) {
    const candidate = `${stem} (${number})${extension}`;
    if (!taken.has(candidate)) return candidate;
  }
}

function bytesOf(base64: string): Uint8Array<ArrayBuffer> {
  const binary = atob(base64.replace(/\s/g, ''));
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes;
}

/** Why Tinfirm did not hold a file, in the request's words. */
function refusedBecause(error: unknown): string {
  if (error instanceof McpToolError) {
    switch (error.code) {
      case 'CREDENTIAL_FOUND':
        return 'it holds a credential';
      case 'ATTACHMENT_NOT_SCANNABLE':
        return 'it cannot be scanned for credentials';
      case 'ATTACHMENT_TOO_LARGE':
        return 'too large';
      case 'TOO_MANY_ATTACHMENTS':
        return 'you hold too many files for a start';
    }
  }
  return 'Tinfirm refused it';
}

/**
 * The open email's attachments, held for the start (ADR-0084) when the person sends them: at most
 * {@link MAX_ATTACHMENTS} files and {@link MAX_ATTACHMENT_MEGABYTES} MB in all, read from Outlook
 * (Mailbox 1.8) and checked and scanned by Tinfirm as an upload is; an attached email as its
 * `.eml`. What is not held is named with why. A refusal of the person's sign-in or of their rate
 * stops the start: it is thrown.
 */
export async function holdAttachments(
  office: OfficeApi,
  item: MessageRead,
  send: boolean,
  hold: (name: string, contentType: string, content: Blob) => Promise<HeldFile>,
): Promise<HeldAttachments> {
  const held: HeldAttachments = { ids: [], attached: [], leftOut: [] };
  const names = new Set<string>();
  const readable = readsAttachments(office, item);
  let bytes = 0;
  for (const attachment of attachmentsOf(item)) {
    const attachedEmail = attachment.attachmentType === 'item';
    const original =
      attachedEmail && !/\.eml$/i.test(attachment.name)
        ? `${attachment.name}.eml`
        : attachment.name;
    const name = uniqueName(original, names);
    names.add(name);
    const leaveOut = (why: string) => held.leftOut.push(`${name} (${why})`);
    if (!send) {
      leaveOut('not sent');
    } else if (attachment.attachmentType === 'cloud') {
      leaveOut('a link to a cloud file');
    } else if (!readable) {
      leaveOut('this Outlook does not give attachments to add-ins');
    } else if (held.ids.length >= MAX_ATTACHMENTS) {
      leaveOut(`more than ${MAX_ATTACHMENTS} files`);
    } else if (bytes + attachment.size > MAX_ATTACHMENT_BYTES) {
      leaveOut(`more than ${MAX_ATTACHMENT_MEGABYTES} MB of attachments in all`);
    } else {
      let content: Blob;
      try {
        const read = await attachmentContent(office, item, attachment.id);
        if (read.format === 'base64') {
          content = new Blob([bytesOf(read.content)]);
        } else if (read.format === 'eml' || read.format === 'iCalendar') {
          content = new Blob([read.content]);
        } else {
          leaveOut('a link to a cloud file');
          continue;
        }
      } catch {
        leaveOut('Outlook did not give it');
        continue;
      }
      if (bytes + content.size > MAX_ATTACHMENT_BYTES) {
        leaveOut(`more than ${MAX_ATTACHMENT_MEGABYTES} MB of attachments in all`);
        continue;
      }
      try {
        const type = attachedEmail
          ? 'message/rfc822'
          : attachment.contentType || 'application/octet-stream';
        const file = await hold(name, type, content);
        if (file.fileName !== name && names.has(file.fileName)) {
          // Checked, its name became another's: held, but not sent.
          leaveOut('another attachment has its name');
          continue;
        }
        names.add(file.fileName);
        held.ids.push(file.id);
        held.attached.push(file.fileName);
        bytes += file.sizeBytes;
      } catch (error) {
        if (!(error instanceof McpToolError) || STOPS.has(error.code)) throw error;
        leaveOut(refusedBecause(error));
      }
    }
  }
  return held;
}
