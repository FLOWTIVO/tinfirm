import { HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { currentUser, problem, provideTestHttp } from '../testing/test-helpers';
import { Session } from './session';

describe('Session', () => {
  let session: Session;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideRouter([]), ...provideTestHttp()] });
    session = TestBed.inject(Session);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('loads the current user once and caches it', async () => {
    const first = session.ensureLoaded();
    const second = session.ensureLoaded();
    http.expectOne({ method: 'GET', url: '/api/me' }).flush(currentUser());

    expect(await first).toEqual(currentUser());
    expect(await second).toEqual(currentUser());
    expect(session.isAuthenticated()).toBe(true);
    expect(session.memberships()).toHaveLength(1);

    // Cached: no second request.
    expect(await session.ensureLoaded()).toEqual(currentUser());
    http.expectNone('/api/me');
  });

  it('treats 401 as "not logged in" and caches that answer', async () => {
    const result = session.ensureLoaded();
    http.expectOne('/api/me').flush(problem(401), { status: 401, statusText: 'Unauthorized' });

    expect(await result).toBeNull();
    expect(session.isAuthenticated()).toBe(false);
    expect(await session.ensureLoaded()).toBeNull();
    http.expectNone('/api/me');
  });

  it('does not cache other failures, so the next navigation retries', async () => {
    const result = session.ensureLoaded();
    http.expectOne('/api/me').flush(problem(503), { status: 503, statusText: 'Unavailable' });
    expect(await result).toBeNull();

    const retry = session.ensureLoaded();
    http.expectOne('/api/me').flush(currentUser());
    expect(await retry).toEqual(currentUser());
  });

  it('exposes the instance-admin flag', () => {
    session.setUser(currentUser({ instanceAdmin: true }));
    expect(session.isInstanceAdmin()).toBe(true);
  });

  it('refresh() re-reads /api/me', async () => {
    session.setUser(currentUser());
    const refreshed = session.refresh();
    http
      .expectOne('/api/me')
      .flush(
        currentUser({ memberships: [{ companyId: 'c2', companyName: 'Globex', role: 'ADMIN' }] }),
      );
    await refreshed;
    expect(session.memberships().map((m) => m.companyId)).toEqual(['c2']);
  });

  it('logout() posts to the server and forgets the user', async () => {
    session.setUser(currentUser());
    const done = session.logout();
    const req = http.expectOne({ method: 'POST', url: '/api/auth/logout' });
    req.flush(null, { status: 204, statusText: 'No Content' });
    await done;

    expect(session.user()).toBeNull();
    // The cleared state is authoritative: no probe needed afterwards.
    expect(await session.ensureLoaded()).toBeNull();
    http.expectNone('/api/me');
  });

  it('logout() forgets the user even when the server call fails', async () => {
    session.setUser(currentUser());
    const done = session.logout();
    http.expectOne('/api/auth/logout').error(new ProgressEvent('error'));
    await expect(done).rejects.toBeTruthy();
    expect(session.user()).toBeNull();
  });
});
