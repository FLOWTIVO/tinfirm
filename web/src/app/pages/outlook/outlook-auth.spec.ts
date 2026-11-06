import type { OfficeApi, OfficeDialog } from './office';
import {
  authorizeUrl,
  challengeOf,
  newVerifier,
  OutlookAuth,
  parseCallbackMessage,
  refusalMessage,
  SignedOutError,
  SignInError,
} from './outlook-auth';

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

const ORIGIN = 'https://tinfirm.example';

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

/** An Office whose dialog answers with `message` once the pane listens. */
function officeAnswering(
  answer: (url: string) => string | null,
  origin: string = ORIGIN,
): OfficeApi {
  return {
    onReady: () => Promise.resolve({ host: 'Outlook', platform: 'PC' }),
    context: {
      ui: {
        displayDialogAsync: (url, _options, callback) => {
          const handlers = new Map<
            string,
            (arg: { message?: string; origin?: string; error?: number }) => void
          >();
          const dialog: OfficeDialog = {
            addEventHandler: (type, handler) => handlers.set(type, handler),
            close: () => undefined,
          };
          callback({ status: 'succeeded', value: dialog });
          const message = answer(url);
          queueMicrotask(() =>
            message === null
              ? handlers.get('dialogEventReceived')?.({ error: 12006 })
              : handlers.get('dialogMessageReceived')?.({ message, origin }),
          );
        },
        messageParent: () => undefined,
      },
    },
    AsyncResultStatus: { Succeeded: 'succeeded', Failed: 'failed' },
    CoercionType: { Text: 'text' },
    EventType: {
      ItemChanged: 'olkItemSelectedChanged',
      DialogMessageReceived: 'dialogMessageReceived',
      DialogEventReceived: 'dialogEventReceived',
    },
  };
}

describe('the Outlook add-in’s sign-in', () => {
  it('makes an S256 challenge of a 43-character verifier', async () => {
    const verifier = newVerifier();
    expect(verifier).toMatch(/^[A-Za-z0-9_-]{43}$/);
    // RFC 7636, appendix B.
    expect(await challengeOf('dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk')).toBe(
      'E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM',
    );
  });

  it('asks Tinfirm’s authorization server for the company’s endpoint, with write', () => {
    const url = new URL(authorizeUrl(ORIGIN, 'c1', 'the-state', 'the-challenge'));
    expect(url.origin + url.pathname).toBe(`${ORIGIN}/oauth2/authorize`);
    expect(Object.fromEntries(url.searchParams)).toEqual({
      response_type: 'code',
      client_id: 'tinfirm-outlook',
      redirect_uri: `${ORIGIN}/outlook/callback`,
      scope: 'tinfirm:write',
      state: 'the-state',
      code_challenge: 'the-challenge',
      code_challenge_method: 'S256',
      resource: `${ORIGIN}/api/companies/c1/mcp`,
    });
  });

  it('reads the callback’s message and words a refusal', () => {
    expect(parseCallbackMessage('{"code":"k","state":"s"}')).toEqual({
      code: 'k',
      state: 's',
      error: undefined,
      errorDescription: undefined,
    });
    expect(parseCallbackMessage('not json')).toEqual({ error: 'invalid_response' });
    expect(refusalMessage({ error: 'access_denied' })).toContain('not a member');
  });

  it('exchanges the code itself, keeps the refresh token and refreshes', async () => {
    const storage = new MemoryStorage();
    const calls: { url: string; body: URLSearchParams }[] = [];
    let state = '';
    const fetchFn = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const body = new URLSearchParams(String(init?.body));
      calls.push({ url: String(input), body });
      if (body.get('grant_type') === 'authorization_code') {
        return json({ access_token: 'access-1', refresh_token: 'refresh-1', expires_in: 3600 });
      }
      return json({ access_token: 'access-2', refresh_token: 'refresh-2', expires_in: 3600 });
    });
    const auth = new OutlookAuth('c1', ORIGIN, storage, fetchFn as unknown as typeof fetch);
    expect(auth.isSignedIn()).toBe(false);

    await auth.signIn(
      officeAnswering((url) => {
        state = new URL(url).searchParams.get('state') ?? '';
        return JSON.stringify({ code: 'the-code', state });
      }),
    );
    expect(calls[0].url).toBe(`${ORIGIN}/oauth2/token`);
    expect(calls[0].body.get('code')).toBe('the-code');
    expect(calls[0].body.get('code_verifier')).toMatch(/^[A-Za-z0-9_-]{43}$/);
    expect(calls[0].body.get('redirect_uri')).toBe(`${ORIGIN}/outlook/callback`);
    expect(await auth.token()).toBe('access-1');
    expect(storage.getItem('tinfirm.outlook.c1')).toBe('{"refreshToken":"refresh-1"}');

    // A new pane on this computer refreshes with the kept token, which rotates.
    const again = new OutlookAuth('c1', ORIGIN, storage, fetchFn as unknown as typeof fetch);
    expect(again.isSignedIn()).toBe(true);
    expect(await again.token()).toBe('access-2');
    expect(calls[1].body.get('grant_type')).toBe('refresh_token');
    expect(calls[1].body.get('refresh_token')).toBe('refresh-1');
    expect(storage.getItem('tinfirm.outlook.c1')).toBe('{"refreshToken":"refresh-2"}');
  });

  it('refuses a code for another request, and a closed window says nothing', async () => {
    const fetchFn = vi.fn();
    const auth = new OutlookAuth(
      'c1',
      ORIGIN,
      new MemoryStorage(),
      fetchFn as unknown as typeof fetch,
    );
    await expect(
      auth.signIn(officeAnswering(() => JSON.stringify({ code: 'k', state: 'another' }))),
    ).rejects.toThrow('another request');
    await expect(auth.signIn(officeAnswering(() => null))).rejects.toSatisfy(
      (error: unknown) => error instanceof SignInError && error.dismissed,
    );
    expect(fetchFn).not.toHaveBeenCalled();
  });

  it('refuses an answer from another site', async () => {
    const auth = new OutlookAuth(
      'c1',
      ORIGIN,
      new MemoryStorage(),
      vi.fn() as unknown as typeof fetch,
    );
    await expect(
      auth.signIn(
        officeAnswering(
          (url) => JSON.stringify({ code: 'k', state: new URL(url).searchParams.get('state') }),
          'https://evil.example',
        ),
      ),
    ).rejects.toThrow('another site');
  });

  it('takes the token another pane rotated meanwhile instead of signing out', async () => {
    const storage = new MemoryStorage();
    storage.setItem('tinfirm.outlook.c1', '{"refreshToken":"old"}');
    const fetchFn = vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      const token = new URLSearchParams(String(init?.body)).get('refresh_token');
      if (token === 'old') {
        // The other pane refreshed first: "old" is spent and "newer" is kept.
        storage.setItem('tinfirm.outlook.c1', '{"refreshToken":"newer"}');
        return json({ error: 'invalid_grant' }, 400);
      }
      return json({ access_token: 'access', refresh_token: 'newest', expires_in: 3600 });
    });
    const auth = new OutlookAuth('c1', ORIGIN, storage, fetchFn as unknown as typeof fetch);
    expect(await auth.token()).toBe('access');
    expect(fetchFn).toHaveBeenCalledTimes(2);
    expect(storage.getItem('tinfirm.outlook.c1')).toBe('{"refreshToken":"newest"}');
  });

  it('signs out when the refresh token no longer works, and revokes on sign-out', async () => {
    const storage = new MemoryStorage();
    storage.setItem('tinfirm.outlook.c1', '{"refreshToken":"old"}');
    const refused = new OutlookAuth('c1', ORIGIN, storage, (async () =>
      json({ error: 'invalid_grant' }, 400)) as unknown as typeof fetch);
    await expect(refused.token()).rejects.toBeInstanceOf(SignedOutError);
    expect(storage.getItem('tinfirm.outlook.c1')).toBeNull();

    storage.setItem('tinfirm.outlook.c1', '{"refreshToken":"kept"}');
    const revoke = vi.fn(async () => new Response(null, { status: 200 }));
    expect(
      await new OutlookAuth('c1', ORIGIN, storage, revoke as unknown as typeof fetch).signOut(),
    ).toBe(true);
    expect(storage.getItem('tinfirm.outlook.c1')).toBeNull();
    const [url, init] = revoke.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe(`${ORIGIN}/oauth2/revoke`);
    expect(new URLSearchParams(String(init.body)).get('token')).toBe('kept');
  });

  it('says when Tinfirm did not confirm the revocation, and forgets the token all the same', async () => {
    for (const answer of [
      async () => new Response(null, { status: 503 }),
      async () => {
        throw new TypeError('Failed to fetch');
      },
    ]) {
      const storage = new MemoryStorage();
      storage.setItem('tinfirm.outlook.c1', '{"refreshToken":"kept"}');
      const auth = new OutlookAuth('c1', ORIGIN, storage, answer as unknown as typeof fetch);
      expect(await auth.signOut()).toBe(false);
      expect(storage.getItem('tinfirm.outlook.c1')).toBeNull();
      expect(auth.isSignedIn()).toBe(false);
    }
  });

  it('keeps nothing of a refresh in flight at sign-out, and revokes the token it brought', async () => {
    const storage = new MemoryStorage();
    storage.setItem('tinfirm.outlook.c1', '{"refreshToken":"refresh-1"}');
    const bodies: URLSearchParams[] = [];
    let answer: (response: Response) => void = () => undefined;
    const fetchFn = vi.fn((_input: RequestInfo | URL, init?: RequestInit) => {
      const body = new URLSearchParams(String(init?.body));
      bodies.push(body);
      if (body.get('grant_type') === 'refresh_token') {
        return new Promise<Response>((resolve) => (answer = resolve));
      }
      return Promise.resolve(new Response(null, { status: 200 }));
    });
    const auth = new OutlookAuth('c1', ORIGIN, storage, fetchFn as unknown as typeof fetch);
    const pending = auth.token();
    expect(await auth.signOut()).toBe(true);
    // The refresh answers after the sign-out revoked "refresh-1", which Tinfirm had rotated.
    answer(json({ access_token: 'access-2', refresh_token: 'refresh-2', expires_in: 3600 }));

    await expect(pending).rejects.toBeInstanceOf(SignedOutError);
    expect(storage.getItem('tinfirm.outlook.c1')).toBeNull();
    expect(auth.isSignedIn()).toBe(false);
    expect(bodies.filter((body) => body.has('token')).map((body) => body.get('token'))).toEqual([
      'refresh-1',
      'refresh-2',
    ]);
  });

  it('keeps nothing of a sign-in that finishes after a sign-out, and revokes its token', async () => {
    const storage = new MemoryStorage();
    const bodies: URLSearchParams[] = [];
    let answer: (response: Response) => void = () => undefined;
    const fetchFn = vi.fn((_input: RequestInfo | URL, init?: RequestInit) => {
      const body = new URLSearchParams(String(init?.body));
      bodies.push(body);
      if (body.get('grant_type') === 'authorization_code') {
        return new Promise<Response>((resolve) => (answer = resolve));
      }
      return Promise.resolve(new Response(null, { status: 200 }));
    });
    const auth = new OutlookAuth('c1', ORIGIN, storage, fetchFn as unknown as typeof fetch);
    const pending = auth.signIn(
      officeAnswering((url) =>
        JSON.stringify({ code: 'the-code', state: new URL(url).searchParams.get('state') }),
      ),
    );
    await vi.waitFor(() => expect(bodies).toHaveLength(1));
    expect(await auth.signOut()).toBe(true);
    answer(json({ access_token: 'access-1', refresh_token: 'refresh-1', expires_in: 3600 }));

    await expect(pending).rejects.toBeInstanceOf(SignedOutError);
    expect(storage.getItem('tinfirm.outlook.c1')).toBeNull();
    expect(auth.isSignedIn()).toBe(false);
    expect(bodies[1]?.get('token')).toBe('refresh-1');
  });

  it('keeps nothing of a sign-in whose dialog was still open at a sign-out', async () => {
    const storage = new MemoryStorage();
    storage.setItem('tinfirm.outlook.c1', JSON.stringify({ refreshToken: 'refresh-0' }));
    const bodies: URLSearchParams[] = [];
    const fetchFn = vi.fn((_input: RequestInfo | URL, init?: RequestInit) => {
      const body = new URLSearchParams(String(init?.body));
      bodies.push(body);
      if (body.get('grant_type') === 'authorization_code') {
        return Promise.resolve(
          json({ access_token: 'access-1', refresh_token: 'refresh-1', expires_in: 3600 }),
        );
      }
      return Promise.resolve(new Response(null, { status: 200 }));
    });
    const auth = new OutlookAuth('c1', ORIGIN, storage, fetchFn as unknown as typeof fetch);
    // The dialog answers only when the test says so: the person signs out while it is open.
    let deliver: ((message: string) => void) | undefined;
    let opened: (state: string | null) => void = () => undefined;
    const dialogOpened = new Promise<string | null>((resolve) => (opened = resolve));
    const office: OfficeApi = {
      ...officeAnswering(() => null),
      context: {
        ui: {
          displayDialogAsync: (url, _options, callback) => {
            let onMessage: ((arg: { message?: string; origin?: string }) => void) | undefined;
            const dialog: OfficeDialog = {
              addEventHandler: (type, handler) => {
                if (type === 'dialogMessageReceived') onMessage = handler;
              },
              close: () => undefined,
            };
            callback({ status: 'succeeded', value: dialog });
            deliver = (message) => onMessage?.({ message, origin: ORIGIN });
            queueMicrotask(() => opened(new URL(url).searchParams.get('state')));
          },
          messageParent: () => undefined,
        },
      },
    };
    const pending = auth.signIn(office);
    const state = await dialogOpened;
    expect(await auth.signOut()).toBe(true);
    deliver?.(JSON.stringify({ code: 'the-code', state }));

    await expect(pending).rejects.toBeInstanceOf(SignedOutError);
    expect(storage.getItem('tinfirm.outlook.c1')).toBeNull();
    expect(auth.isSignedIn()).toBe(false);
    expect(bodies.filter((body) => body.has('token')).map((body) => body.get('token'))).toEqual([
      'refresh-0',
      'refresh-1',
    ]);
  });
});
