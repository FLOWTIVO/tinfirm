import type { Conversation } from '../../api/generated/models';
import type { EmailAction } from './email-request';
import type { MessageRead, RoamingSettings } from './office';
import { safeLocalStorage } from './outlook-auth';

/** The conversation the pane started about one email (ADR-0076). */
export interface RememberedConversation {
  conversationId: string;
  agentName: string;
  action: EmailAction;
  startedAt: string;
}

type Links = Record<string, RememberedConversation>;

/** The most emails remembered; the oldest go first. */
const MAX_REMEMBERED = 100;

/**
 * The most bytes the links take as the roaming settings keep them, in UTF-8 (32 KB for all of the
 * add-in's settings; Office.js counts characters, never more than the bytes); the oldest go first.
 */
const MAX_BYTES = 24_000;

/**
 * The key an email's conversation is remembered by: a hash of its Internet message id, which stays
 * when the email moves to another folder and is the same in every Outlook of the mailbox; of
 * Outlook's item id for an item without one. 32 hex digits: short, and the id is not kept.
 */
export async function emailKey(
  item: Pick<MessageRead, 'itemId' | 'internetMessageId'>,
): Promise<string | null> {
  const messageId = item.internetMessageId?.trim();
  const id = messageId ? `message:${messageId}` : item.itemId ? `item:${item.itemId}` : null;
  if (!id) return null;
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(id));
  return Array.from(new Uint8Array(digest).slice(0, 16), (byte) =>
    byte.toString(16).padStart(2, '0'),
  ).join('');
}

/** Where links are kept: the roaming settings, or this computer's `localStorage`. */
interface LinkStore {
  read(): Links;
  write(links: Links): void;
}

function links(value: unknown): Links {
  return value && typeof value === 'object' && !Array.isArray(value) ? (value as Links) : {};
}

function roamingStore(settings: RoamingSettings, name: string): LinkStore {
  return {
    read: () => {
      try {
        return links(settings.get(name));
      } catch {
        return {};
      }
    },
    write: (value) => {
      try {
        settings.set(name, value);
        // Saved in the background; when it fails (offline, or Outlook refused), this computer
        // still has it.
        settings.saveAsync();
      } catch {
        // Kept on this computer only.
      }
    },
  };
}

function localStore(storage: Storage | null, name: string): LinkStore {
  return {
    read: () => {
      try {
        const raw = storage?.getItem(name);
        return links(raw ? JSON.parse(raw) : {});
      } catch {
        return {};
      }
    },
    write: (value) => {
      try {
        storage?.setItem(name, JSON.stringify(value));
      } catch {
        // Storage refused: the pane forgets when it closes.
      }
    },
  };
}

/**
 * Which conversation belongs to which email, per company (ADR-0076): in the add-in's roaming
 * settings, which every Outlook of the person's mailbox shares, so that the pane shows it again on
 * any computer; and in this computer's `localStorage`, which keeps it when another Outlook saved the
 * roaming settings over it, and outside Outlook. Only keys, ids and names are kept, never the email.
 */
export class EmailConversations {
  private readonly local: LinkStore;
  private readonly stores: LinkStore[];

  constructor(
    companyId: string,
    roaming: RoamingSettings | null = null,
    storage: Storage | null = safeLocalStorage(),
  ) {
    const name = `tinfirm.outlook.${companyId}.conversations`;
    this.local = localStore(storage, name);
    this.stores = roaming ? [roamingStore(roaming, name), this.local] : [this.local];
  }

  /**
   * The email's conversation: the newer when the roaming settings and this computer disagree, as
   * when another Outlook saved over a link this one made. One this computer remembered by Outlook's
   * item id, before the key was the message id's hash (`legacyKey`), moves to the key, and so to the
   * roaming settings.
   */
  get(emailKey: string, legacyKey: string | null = null): RememberedConversation | null {
    const found = this.stores
      .map((store) => store.read()[emailKey])
      .filter((link): link is RememberedConversation => !!link)
      .sort((a, b) => String(b.startedAt).localeCompare(String(a.startedAt)))[0];
    if (found) return found;
    if (!legacyKey) return null;
    const local = this.local.read();
    const legacy = local[legacyKey];
    if (!legacy) return null;
    delete local[legacyKey];
    this.local.write(local);
    this.put(emailKey, legacy);
    return legacy;
  }

  remember(
    emailKey: string,
    conversation: Conversation,
    action: EmailAction,
  ): RememberedConversation {
    const remembered: RememberedConversation = {
      conversationId: conversation.id,
      agentName: conversation.agentName,
      action,
      startedAt: new Date().toISOString(),
    };
    this.put(emailKey, remembered);
    return remembered;
  }

  forget(emailKey: string): void {
    for (const store of this.stores) {
      const all = store.read();
      if (!(emailKey in all)) continue;
      delete all[emailKey];
      store.write(all);
    }
  }

  private put(emailKey: string, remembered: RememberedConversation): void {
    for (const store of this.stores) {
      // In the order they were remembered, this one last; the oldest go first.
      const all = store.read();
      delete all[emailKey];
      const entries = [...Object.entries(all), [emailKey, remembered] as const].slice(
        -MAX_REMEMBERED,
      );
      // As the roaming settings keep it: a JSON string inside their JSON.
      while (
        entries.length > 1 &&
        new TextEncoder().encode(JSON.stringify(JSON.stringify(Object.fromEntries(entries))))
          .length > MAX_BYTES
      ) {
        entries.shift();
      }
      store.write(Object.fromEntries(entries));
    }
  }
}

/** The last agent the person chose, per company. */
export class LastAgent {
  constructor(
    private readonly companyId: string,
    private readonly storage: Storage | null = safeLocalStorage(),
  ) {}

  get(): string | null {
    try {
      return this.storage?.getItem(`tinfirm.outlook.${this.companyId}.agent`) ?? null;
    } catch {
      return null;
    }
  }

  set(agentId: string): void {
    try {
      this.storage?.setItem(`tinfirm.outlook.${this.companyId}.agent`, agentId);
    } catch {
      // Not remembered.
    }
  }
}
