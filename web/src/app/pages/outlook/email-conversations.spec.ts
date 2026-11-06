import { aConversation } from '../../testing/fixtures';
import { EmailConversations, emailKey, LastAgent } from './email-conversations';
import type { RoamingSettings } from './office';

class MemoryStorage implements Storage {
  private readonly values = new Map<string, string>();
  get length() {
    return this.values.size;
  }
  clear() {
    this.values.clear();
  }
  getItem(key: string) {
    return this.values.get(key) ?? null;
  }
  key(index: number) {
    return [...this.values.keys()][index] ?? null;
  }
  removeItem(key: string) {
    this.values.delete(key);
  }
  setItem(key: string, value: string) {
    this.values.set(key, value);
  }
}

/** The mailbox's roaming settings, as every Outlook of it shares them. */
class FakeRoaming implements RoamingSettings {
  readonly values = new Map<string, unknown>();
  readonly saveAsync = vi.fn();
  get(name: string) {
    return this.values.get(name);
  }
  set(name: string, value: unknown) {
    this.values.set(name, structuredClone(value));
  }
  remove(name: string) {
    this.values.delete(name);
  }
}

const NAME = 'tinfirm.outlook.c1.conversations';

describe('the conversations of emails', () => {
  it('remembers a conversation per email and company, and forgets it', () => {
    const storage = new MemoryStorage();
    const store = new EmailConversations('c1', null, storage);
    const remembered = store.remember(
      'key-1',
      aConversation({ id: 'conv-1', agentName: 'Dev' }),
      'INVESTIGATE',
    );
    expect(store.get('key-1')).toEqual(remembered);
    expect(remembered).toMatchObject({
      conversationId: 'conv-1',
      agentName: 'Dev',
      action: 'INVESTIGATE',
    });
    expect(new EmailConversations('c2', null, storage).get('key-1')).toBeNull();
    store.forget('key-1');
    expect(store.get('key-1')).toBeNull();
  });

  it('keeps at most a hundred emails, the newest', () => {
    const store = new EmailConversations('c1', null, new MemoryStorage());
    for (let i = 0; i < 105; i++) {
      store.remember(`key-${i}`, aConversation({ id: `conv-${i}` }), 'SUMMARISE');
    }
    expect(store.get('key-104')).not.toBeNull();
    expect(store.get('key-0')).toBeNull();
  });

  it('shares them through the roaming settings, so that another computer finds them', () => {
    const roaming = new FakeRoaming();
    new EmailConversations('c1', roaming, new MemoryStorage()).remember(
      'key-1',
      aConversation({ id: 'conv-1' }),
      'TRIAGE',
    );
    expect(roaming.saveAsync).toHaveBeenCalled();

    const elsewhere = new EmailConversations('c1', roaming, new MemoryStorage());
    expect(elsewhere.get('key-1')?.conversationId).toBe('conv-1');
    elsewhere.forget('key-1');
    expect(roaming.values.get(NAME)).toEqual({});
  });

  it('keeps the newer link when another Outlook saved over this one’s', () => {
    const roaming = new FakeRoaming();
    const storage = new MemoryStorage();
    const link = (conversationId: string, startedAt: string) => ({
      conversationId,
      agentName: 'Dev',
      action: 'SUMMARISE',
      startedAt,
    });
    roaming.set(NAME, { 'key-1': link('conv-old', '2026-10-19T10:00:00.000Z') });
    storage.setItem(
      NAME,
      JSON.stringify({ 'key-1': link('conv-new', '2026-10-20T10:00:00.000Z') }),
    );

    expect(new EmailConversations('c1', roaming, storage).get('key-1')?.conversationId).toBe(
      'conv-new',
    );
  });

  it('moves a link this computer kept by Outlook’s item id to the email’s key, and so roams it', () => {
    const roaming = new FakeRoaming();
    const storage = new MemoryStorage();
    storage.setItem(
      NAME,
      JSON.stringify({
        'item-1': {
          conversationId: 'conv-1',
          agentName: 'Dev',
          action: 'SUMMARISE',
          startedAt: '2026-10-19T10:00:00.000Z',
        },
      }),
    );
    const store = new EmailConversations('c1', roaming, storage);

    expect(store.get('key-1', 'item-1')?.conversationId).toBe('conv-1');
    expect(Object.keys(JSON.parse(storage.getItem(NAME)!))).toEqual(['key-1']);
    expect(Object.keys(roaming.values.get(NAME) as object)).toEqual(['key-1']);
  });

  it('fits the links in the roaming settings’ 32 KB, the newest first, in bytes', () => {
    const roaming = new FakeRoaming();
    const store = new EmailConversations('c1', roaming, new MemoryStorage());
    for (let i = 0; i < 100; i++) {
      store.remember(
        `key-${i}`,
        // Three bytes a character in UTF-8: 100 such links fit 24,000 characters, not bytes.
        aConversation({ id: `conv-${i}`, agentName: '経理担当'.repeat(12) }),
        'SUMMARISE',
      );
    }
    const kept = roaming.values.get(NAME) as Record<string, unknown>;
    const bytes = new TextEncoder().encode(JSON.stringify(JSON.stringify(kept))).length;
    expect(bytes).toBeLessThanOrEqual(24_000);
    expect(Object.keys(kept).length).toBeLessThan(100);
    expect(store.get('key-99')).not.toBeNull();
  });

  it('works without storage', () => {
    const store = new EmailConversations('c1', null, null);
    store.remember('key-1', aConversation(), 'SUMMARISE');
    expect(store.get('key-1')).toBeNull();
    const last = new LastAgent('c1', null);
    last.set('a1');
    expect(last.get()).toBeNull();
  });
});

describe('the key an email is remembered by', () => {
  it('is the same wherever the email moves, and another for another email', async () => {
    const key = await emailKey({ itemId: 'inbox-1', internetMessageId: '<m1@example.com>' });
    expect(key).toMatch(/^[0-9a-f]{32}$/);
    expect(await emailKey({ itemId: 'archive-1', internetMessageId: ' <m1@example.com> ' })).toBe(
      key,
    );
    expect(await emailKey({ itemId: 'inbox-1', internetMessageId: '<m2@example.com>' })).not.toBe(
      key,
    );
  });

  it('falls back to Outlook’s item id, and is none without either', async () => {
    expect(await emailKey({ itemId: 'item-1' })).toMatch(/^[0-9a-f]{32}$/);
    expect(await emailKey({})).toBeNull();
  });
});
