/**
 * The little of Office.js the Outlook add-in uses (ADR-0074), typed by hand rather than with the
 * whole of `@types/office-js`. Office.js itself loads from Microsoft's CDN on the add-in's pages
 * only, whose CSP allows it (the server's `/outlook/**` headers).
 */

/** Office.js, from the CDN Microsoft requires add-ins to load it from. */
export const OFFICE_JS_URL = 'https://appsforoffice.microsoft.com/lib/1/hosted/office.js';

/** How long Office.js may take to load and to say it is ready. */
const READY_TIMEOUT_MS = 20_000;

export interface AsyncResult<T> {
  status: string;
  value: T;
  error?: { code: number; message: string };
}

export interface EmailAddress {
  displayName: string;
  emailAddress: string;
}

/** An attachment of the open email, as Outlook lists it (Mailbox 1.1). */
export interface AttachmentDetails {
  id: string;
  name: string;
  contentType?: string;
  /** Bytes. */
  size: number;
  /** `file`, `item` (an attached email or event) or `cloud` (a link to a file). */
  attachmentType: string;
  /** An image of the body itself. */
  isInline: boolean;
}

/** An attachment's content: `base64` for a file, `eml` or `iCalendar` for an item, `url` for a cloud file. */
export interface AttachmentContent {
  content: string;
  format: string;
}

/** The open email, as Outlook's read form gives it (`Office.context.mailbox.item`). */
export interface MessageRead {
  itemId?: string;
  internetMessageId?: string;
  subject: string;
  from?: EmailAddress;
  to?: EmailAddress[];
  cc?: EmailAddress[];
  dateTimeCreated?: Date;
  body: {
    getAsync(coercionType: string, callback: (result: AsyncResult<string>) => void): void;
  };
  displayReplyForm(formData: { htmlBody: string }): void;
  attachments?: AttachmentDetails[];
  /** Mailbox 1.8. */
  getAttachmentContentAsync?(
    attachmentId: string,
    callback: (result: AsyncResult<AttachmentContent>) => void,
  ): void;
}

/**
 * The add-in's own settings in the person's mailbox (`Office.context.roamingSettings`): Outlook
 * loads them when the add-in starts and every Outlook of the mailbox shares them, at most 32 KB in
 * all. A `saveAsync` replaces what another Outlook saved since this one loaded them.
 */
export interface RoamingSettings {
  get(name: string): unknown;
  set(name: string, value: unknown): void;
  remove(name: string): void;
  saveAsync(callback?: (result: AsyncResult<void>) => void): void;
}

export interface OfficeDialog {
  addEventHandler(
    eventType: string,
    handler: (arg: { message?: string; origin?: string; error?: number }) => void,
  ): void;
  close(): void;
}

export interface OfficeApi {
  onReady(): Promise<{ host: string | null; platform: string | null }>;
  context: {
    mailbox?: {
      item: MessageRead | null;
      addHandlerAsync(eventType: string, handler: () => void, callback?: () => void): void;
    };
    /** Outlook's only. */
    roamingSettings?: RoamingSettings;
    requirements?: { isSetSupported(name: string, minVersion?: string): boolean };
    /** Missing outside Office. */
    ui?: {
      displayDialogAsync(
        url: string,
        options: { height: number; width: number; promptBeforeOpen?: boolean },
        callback: (result: AsyncResult<OfficeDialog>) => void,
      ): void;
      messageParent(message: string): void;
    };
  };
  AsyncResultStatus: { Succeeded: string; Failed: string };
  CoercionType: { Text: string };
  EventType: { ItemChanged: string; DialogMessageReceived: string; DialogEventReceived: string };
}

declare global {
  interface Window {
    Office?: OfficeApi;
  }
}

let loading: Promise<OfficeApi | null> | null = null;

/**
 * Loads Office.js once and waits until it is ready: the Office API, or null when Office.js does not
 * load. Outside Outlook (a page opened in a browser tab) it loads and offers no mailbox, and in
 * Office's dialog it offers no mailbox either: callers check what they need. Office.js sets
 * `history.pushState` and `history.replaceState` to null, which the router needs; they are kept and
 * put back once it has loaded.
 */
export function loadOffice(doc: Document = document): Promise<OfficeApi | null> {
  loading ??= new Promise<OfficeApi | null>((resolve) => {
    const view = doc.defaultView;
    if (!view) {
      resolve(null);
      return;
    }
    const history = view.history;
    const pushState = history.pushState.bind(history);
    const replaceState = history.replaceState.bind(history);
    const restore = () => {
      history.pushState = pushState;
      history.replaceState = replaceState;
    };
    const timer = setTimeout(() => resolve(null), READY_TIMEOUT_MS);
    const ready = (office: OfficeApi) => {
      office
        .onReady()
        .then(() => resolve(office))
        .catch(() => resolve(null))
        .finally(() => clearTimeout(timer));
    };
    if (view.Office) {
      ready(view.Office);
      return;
    }
    const script = doc.createElement('script');
    script.src = OFFICE_JS_URL;
    script.async = true;
    script.onload = () => {
      restore();
      if (view.Office) {
        ready(view.Office);
      } else {
        clearTimeout(timer);
        resolve(null);
      }
    };
    script.onerror = () => {
      restore();
      clearTimeout(timer);
      resolve(null);
    };
    doc.head.appendChild(script);
  });
  return loading;
}

/** Forgets a load, for tests. */
export function resetOfficeForTests(): void {
  loading = null;
}

/** Whether this Outlook gives an add-in the content of an attachment (Mailbox 1.8). */
export function readsAttachments(office: OfficeApi, item: MessageRead): boolean {
  if (typeof item.getAttachmentContentAsync !== 'function') return false;
  try {
    return office.context.requirements?.isSetSupported('Mailbox', '1.8') ?? true;
  } catch {
    return false;
  }
}

/** An attachment's content, as Outlook gives it. */
export function attachmentContent(
  office: OfficeApi,
  item: MessageRead,
  attachmentId: string,
): Promise<AttachmentContent> {
  return new Promise((resolve, reject) => {
    if (!item.getAttachmentContentAsync) {
      reject(new Error('This Outlook does not give attachments to add-ins'));
      return;
    }
    item.getAttachmentContentAsync(attachmentId, (result) => {
      if (result.status === office.AsyncResultStatus.Succeeded) {
        resolve(result.value);
      } else {
        reject(new Error(result.error?.message ?? 'Outlook did not give the attachment'));
      }
    });
  });
}

/** The open email's body as plain text. */
export function bodyText(office: OfficeApi, item: MessageRead): Promise<string> {
  return new Promise((resolve, reject) => {
    item.body.getAsync(office.CoercionType.Text, (result) => {
      if (result.status === office.AsyncResultStatus.Succeeded) {
        resolve(result.value ?? '');
      } else {
        reject(new Error(result.error?.message ?? 'Outlook did not give the email’s text'));
      }
    });
  });
}
