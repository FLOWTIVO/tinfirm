import type { OfficeApi, OfficeDialog } from './office';

/** Tinfirm's built-in OAuth client of the Outlook add-in (ADR-0074, `BuiltInOAuthClients`). */
export const OUTLOOK_CLIENT_ID = 'tinfirm-outlook';

/** The add-in's sign-in callback, the one redirect URI of its client. */
export const OUTLOOK_CALLBACK_PATH = '/outlook/callback';

/** The task pane starts conversations, so it asks for write; the consent page offers every level. */
export const OUTLOOK_SCOPE = 'tinfirm:write';

/** What the callback page sends the task pane with `messageParent`. */
export interface CallbackMessage {
  code?: string;
  state?: string;
  error?: string;
  errorDescription?: string;
}

/** A sign-in or token answer that went wrong, in words for the pane. */
export class SignInError extends Error {
  constructor(
    message: string,
    /** The person dismissed or closed the window: nothing to report. */
    readonly dismissed = false,
  ) {
    super(message);
    this.name = 'SignInError';
  }
}

/** The person's tokens for the company are gone: they sign in again. */
export class SignedOutError extends Error {
  constructor() {
    super('Sign in to Tinfirm again.');
    this.name = 'SignedOutError';
  }
}

function base64Url(bytes: Uint8Array): string {
  let binary = '';
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

/** A PKCE verifier: 32 random bytes, 43 characters of base64url (RFC 7636). */
export function newVerifier(): string {
  return base64Url(crypto.getRandomValues(new Uint8Array(32)));
}

/** The `S256` challenge of a verifier. */
export async function challengeOf(verifier: string): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier));
  return base64Url(new Uint8Array(digest));
}

/** The company's MCP endpoint, the `resource` its tokens are for. */
export function resourceOf(origin: string, companyId: string): string {
  return `${origin}/api/companies/${companyId}/mcp`;
}

/** The authorization request the sign-in window opens, on Tinfirm's own origin. */
export function authorizeUrl(
  origin: string,
  companyId: string,
  state: string,
  challenge: string,
): string {
  const query = new URLSearchParams({
    response_type: 'code',
    client_id: OUTLOOK_CLIENT_ID,
    redirect_uri: origin + OUTLOOK_CALLBACK_PATH,
    scope: OUTLOOK_SCOPE,
    state,
    code_challenge: challenge,
    code_challenge_method: 'S256',
    resource: resourceOf(origin, companyId),
  });
  return `${origin}/oauth2/authorize?${query.toString()}`;
}

/** Reads the callback page's message; anything else is a refusal. */
export function parseCallbackMessage(message: string | undefined): CallbackMessage {
  try {
    const value: unknown = JSON.parse(message ?? '');
    if (value && typeof value === 'object') {
      const fields = value as Record<string, unknown>;
      const text = (key: string) =>
        typeof fields[key] === 'string' ? (fields[key] as string) : undefined;
      return {
        code: text('code'),
        state: text('state'),
        error: text('error'),
        errorDescription: text('errorDescription'),
      };
    }
  } catch {
    // Not the callback's message.
  }
  return { error: 'invalid_response' };
}

/** What a refused authorization means to the person. */
export function refusalMessage(message: CallbackMessage): string {
  switch (message.error) {
    case 'access_denied':
      return 'Tinfirm was not allowed to connect, or you are not a member of this company.';
    case 'invalid_scope':
    case 'invalid_target':
    case 'invalid_request':
      return 'Tinfirm refused the sign-in request. Ask your administrator to download the add-in’s manifest again.';
    default:
      return message.errorDescription ?? 'The sign-in did not finish. Try again.';
  }
}

interface TokenResponse {
  access_token: string;
  refresh_token?: string;
  expires_in?: number;
}

interface StoredTokens {
  refreshToken: string;
}

/** Refresh an access token this long before it expires. */
const EXPIRY_MARGIN_MS = 60_000;

/**
 * The person's tokens at one company (ADR-0074): the access token in memory, the refresh token in
 * the task pane's `localStorage`, which rotates on every use. Sign-in runs in Office's dialog: the
 * pane keeps the PKCE verifier and the state, the dialog returns only the code, and the pane
 * exchanges it itself.
 */
export class OutlookAuth {
  private accessToken: string | null = null;
  private expiresAt = 0;
  private refreshing: Promise<string> | null = null;
  /** Counts the sign-outs, so that tokens on their way when one happened are not kept. */
  private generation = 0;

  constructor(
    private readonly companyId: string,
    private readonly origin: string = location.origin,
    private readonly storage: Storage | null = safeLocalStorage(),
    private readonly fetchFn: typeof fetch = (input, init) => fetch(input, init),
  ) {}

  private get storageKey(): string {
    return `tinfirm.outlook.${this.companyId}`;
  }

  /** Whether a refresh token is kept: the person signed in on this computer. */
  isSignedIn(): boolean {
    return this.accessToken !== null || this.stored() !== null;
  }

  /** A current access token, refreshed when it is about to expire. */
  async token(): Promise<string> {
    if (this.accessToken && Date.now() < this.expiresAt - EXPIRY_MARGIN_MS) {
      return this.accessToken;
    }
    return this.refresh();
  }

  /** A new access token from the refresh token; one refresh at a time, since it rotates. */
  refresh(): Promise<string> {
    this.refreshing ??= this.doRefresh().finally(() => (this.refreshing = null));
    return this.refreshing;
  }

  private async doRefresh(retried = false): Promise<string> {
    const stored = this.stored();
    if (!stored) throw new SignedOutError();
    const generation = this.generation;
    const response = await this.fetchFn(`${this.origin}/oauth2/token`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded', Accept: 'application/json' },
      body: new URLSearchParams({
        grant_type: 'refresh_token',
        refresh_token: stored.refreshToken,
        client_id: OUTLOOK_CLIENT_ID,
        resource: resourceOf(this.origin, this.companyId),
      }),
    });
    if (response.status === 400 || response.status === 401) {
      // Another pane on this computer (a second Outlook window) rotated the token meanwhile: use its.
      if (!retried && this.stored()?.refreshToken !== stored.refreshToken) {
        return this.doRefresh(true);
      }
      // Revoked under "Connected apps", expired after 30 days, or the person left the company.
      this.forget();
      throw new SignedOutError();
    }
    if (!response.ok) throw new Error(`Tinfirm answered ${response.status} to a token refresh`);
    return this.keepUnlessSignedOut((await response.json()) as TokenResponse, generation);
  }

  /** Opens the sign-in window and waits for the person to sign in and consent. */
  async signIn(office: OfficeApi): Promise<void> {
    const verifier = newVerifier();
    const state = newVerifier();
    const url = authorizeUrl(this.origin, this.companyId, state, await challengeOf(verifier));
    // Before the dialog: a sign-out while it is open must not be undone by its tokens either.
    const generation = this.generation;
    const message = await openDialog(office, url, this.origin);
    if (message.error || !message.code) throw new SignInError(refusalMessage(message));
    if (message.state !== state) throw new SignInError('The sign-in answered another request.');
    const response = await this.fetchFn(`${this.origin}/oauth2/token`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded', Accept: 'application/json' },
      body: new URLSearchParams({
        grant_type: 'authorization_code',
        code: message.code,
        redirect_uri: this.origin + OUTLOOK_CALLBACK_PATH,
        client_id: OUTLOOK_CLIENT_ID,
        code_verifier: verifier,
        resource: resourceOf(this.origin, this.companyId),
      }),
    });
    if (!response.ok) throw new SignInError('Tinfirm did not accept the sign-in. Try again.');
    await this.keepUnlessSignedOut((await response.json()) as TokenResponse, generation);
  }

  /**
   * Revokes the refresh token and forgets both tokens; the grant ends with it. Answers whether
   * Tinfirm confirmed the revocation: it answers 200 for any token (RFC 7009), so anything else
   * means it did not, and the grant stays under "Connected apps" until it expires or is revoked
   * there. The tokens are forgotten on this computer either way: the person asked to sign out.
   */
  async signOut(): Promise<boolean> {
    const stored = this.stored();
    this.forget();
    if (!stored) return true;
    return this.revoke(stored.refreshToken);
  }

  /** Forgets the tokens without revoking them: they no longer work. */
  forget(): void {
    this.generation++;
    this.accessToken = null;
    this.expiresAt = 0;
    try {
      this.storage?.removeItem(this.storageKey);
    } catch {
      // Storage refused: nothing kept.
    }
  }

  /**
   * Keeps the tokens, unless the person signed out while they were on the way: the sign-out revoked
   * the refresh token that was sent, which Tinfirm rotated into the one that came back, so that one
   * is revoked too and nothing is kept.
   */
  private async keepUnlessSignedOut(tokens: TokenResponse, generation: number): Promise<string> {
    if (generation === this.generation) return this.keep(tokens);
    if (tokens.refresh_token) await this.revoke(tokens.refresh_token);
    throw new SignedOutError();
  }

  /** Revokes a refresh token; answers whether Tinfirm confirmed it. */
  private async revoke(token: string): Promise<boolean> {
    try {
      const response = await this.fetchFn(`${this.origin}/oauth2/revoke`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: new URLSearchParams({
          token,
          token_type_hint: 'refresh_token',
          client_id: OUTLOOK_CLIENT_ID,
        }),
      });
      return response.ok;
    } catch {
      return false;
    }
  }

  private keep(tokens: TokenResponse): string {
    this.accessToken = tokens.access_token;
    this.expiresAt = Date.now() + (tokens.expires_in ?? 3600) * 1000;
    if (tokens.refresh_token) {
      try {
        this.storage?.setItem(
          this.storageKey,
          JSON.stringify({ refreshToken: tokens.refresh_token } satisfies StoredTokens),
        );
      } catch {
        // Storage refused: signed in until the pane closes.
      }
    }
    return tokens.access_token;
  }

  private stored(): StoredTokens | null {
    try {
      const raw = this.storage?.getItem(this.storageKey);
      if (!raw) return null;
      const value = JSON.parse(raw) as Partial<StoredTokens>;
      return typeof value.refreshToken === 'string' ? { refreshToken: value.refreshToken } : null;
    } catch {
      return null;
    }
  }
}

/** `localStorage`, or null where the browser refuses it. */
export function safeLocalStorage(): Storage | null {
  try {
    return globalThis.localStorage ?? null;
  } catch {
    return null;
  }
}

/**
 * Office's dialog on the authorization request; resolves with the callback page's message. The
 * dialog's first page must be on the task pane's own domain, which `/oauth2/authorize` is.
 */
function openDialog(office: OfficeApi, url: string, origin: string): Promise<CallbackMessage> {
  return new Promise((resolve, reject) => {
    const ui = office.context.ui;
    if (!ui) {
      reject(new SignInError('Outlook did not open the sign-in window.'));
      return;
    }
    ui.displayDialogAsync(url, { height: 70, width: 40, promptBeforeOpen: false }, (result) => {
      if (result.status !== office.AsyncResultStatus.Succeeded) {
        // 12009: the person chose to ignore the window.
        reject(
          new SignInError(
            result.error?.code === 12009
              ? 'The sign-in window was blocked. Allow it and try again.'
              : 'Outlook did not open the sign-in window.',
            result.error?.code === 12009,
          ),
        );
        return;
      }
      const dialog: OfficeDialog = result.value;
      dialog.addEventHandler(office.EventType.DialogMessageReceived, (arg) => {
        dialog.close();
        // Only Tinfirm's callback page answers; Office names the sender's origin where it can.
        if (arg.origin && arg.origin !== origin) {
          reject(new SignInError('The sign-in window answered from another site.'));
          return;
        }
        resolve(parseCallbackMessage(arg.message));
      });
      dialog.addEventHandler(office.EventType.DialogEventReceived, () => {
        // 12006: the person closed the window.
        reject(new SignInError('The sign-in window was closed.', true));
      });
    });
  });
}
