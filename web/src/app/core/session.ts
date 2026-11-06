import { HttpErrorResponse } from '@angular/common/http';
import { computed, inject, Injectable, signal } from '@angular/core';
import { Api } from '../api/generated/api';
// Each function from its own file: the barrel of every function would land in the initial bundle.
import { logout } from '../api/generated/fn/auth/logout';
import { getCurrentUser } from '../api/generated/fn/me/get-current-user';
import type { CurrentUser } from '../api/generated/models';
import { skipAuthRedirect } from './http-context';

/**
 * The logged-in user (`GET /api/me`), cached as a signal.
 *
 * `ensureLoaded()` probes the session once; guards call it on every navigation, so it must stay
 * cheap. A 401 means "not logged in" and is cached; other failures are not cached so a later
 * navigation retries.
 */
@Injectable({ providedIn: 'root' })
export class Session {
  private readonly api = inject(Api);

  private readonly _user = signal<CurrentUser | null>(null);
  private loaded = false;
  private inflight: Promise<CurrentUser | null> | null = null;

  readonly user = this._user.asReadonly();
  readonly isAuthenticated = computed(() => this._user() !== null);
  readonly isInstanceAdmin = computed(() => this._user()?.instanceAdmin ?? false);
  readonly memberships = computed(() => this._user()?.memberships ?? []);

  /** Resolves the current user, or null when there is no valid session. Never rejects. */
  ensureLoaded(): Promise<CurrentUser | null> {
    if (this.loaded) return Promise.resolve(this._user());
    this.inflight ??= this.fetch();
    return this.inflight;
  }

  /** Re-reads `/api/me`, e.g. after the user's memberships or names changed. */
  refresh(): Promise<CurrentUser | null> {
    this.inflight = this.fetch();
    return this.inflight;
  }

  /** Stores the user returned by `POST /api/auth/login`. */
  setUser(user: CurrentUser): void {
    this._user.set(user);
    this.loaded = true;
    this.inflight = null;
  }

  /** Forgets the user locally (logout, or a 401 from any call). */
  clear(): void {
    this._user.set(null);
    this.loaded = true;
    this.inflight = null;
  }

  /** Ends the session on the server, then locally. Local state is cleared even if the call fails. */
  async logout(): Promise<void> {
    try {
      await this.api.invoke(logout, {}, skipAuthRedirect());
    } finally {
      this.clear();
    }
  }

  private async fetch(): Promise<CurrentUser | null> {
    try {
      const user = await this.api.invoke(getCurrentUser, {}, skipAuthRedirect());
      this._user.set(user);
      this.loaded = true;
      return user;
    } catch (error) {
      if (error instanceof HttpErrorResponse && error.status === 401) {
        this._user.set(null);
        this.loaded = true;
      }
      // Any other failure (server down, 5xx) keeps the current state and is retried later.
      return this._user();
    } finally {
      this.inflight = null;
    }
  }
}
