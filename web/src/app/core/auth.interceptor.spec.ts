import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { HttpTestingController } from '@angular/common/http/testing';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { currentUser, problem, provideTestHttp } from '../testing/test-helpers';
import { skipAuthRedirect } from './http-context';
import { Session } from './session';

@Component({ template: '' })
class Blank {}

describe('authInterceptor', () => {
  let http: HttpClient;
  let backend: HttpTestingController;
  let router: Router;
  let session: Session;

  beforeEach(async () => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([
          { path: 'c/:companyId/members', component: Blank },
          { path: 'login', component: Blank },
        ]),
        ...provideTestHttp(),
      ],
    });
    http = TestBed.inject(HttpClient);
    backend = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
    session = TestBed.inject(Session);
    session.setUser(currentUser());
    await router.navigateByUrl('/c/c1/members');
  });

  afterEach(() => backend.verify());

  it('on 401 forgets the session and redirects to /login with the return URL', async () => {
    const navigate = vi.spyOn(router, 'navigate');
    const call = firstValueFrom(http.get('/api/companies/c1/members'));
    backend
      .expectOne('/api/companies/c1/members')
      .flush(problem(401), { status: 401, statusText: 'Unauthorized' });

    await expect(call).rejects.toBeInstanceOf(HttpErrorResponse);
    expect(session.user()).toBeNull();
    expect(navigate).toHaveBeenCalledWith(['/login'], {
      queryParams: { returnUrl: '/c/c1/members' },
    });
  });

  it('leaves 401s alone when the caller expects them', async () => {
    const navigate = vi.spyOn(router, 'navigate');
    const call = firstValueFrom(http.post('/api/auth/login', {}, { context: skipAuthRedirect() }));
    backend
      .expectOne('/api/auth/login')
      .flush(problem(401), { status: 401, statusText: 'Unauthorized' });

    await expect(call).rejects.toBeInstanceOf(HttpErrorResponse);
    expect(navigate).not.toHaveBeenCalled();
    expect(session.user()).not.toBeNull();
  });

  it('passes other errors through untouched', async () => {
    const navigate = vi.spyOn(router, 'navigate');
    const call = firstValueFrom(http.get('/api/companies/c1'));
    backend
      .expectOne('/api/companies/c1')
      .flush(problem(403), { status: 403, statusText: 'Forbidden' });

    const error = await call.catch((e: unknown) => e);
    expect(error).toBeInstanceOf(HttpErrorResponse);
    expect((error as HttpErrorResponse).status).toBe(403);
    expect(navigate).not.toHaveBeenCalled();
    expect(session.user()).not.toBeNull();
  });

  it('does not redirect again from the login page', async () => {
    await router.navigateByUrl('/login');
    const navigate = vi.spyOn(router, 'navigate');
    const call = firstValueFrom(http.get('/api/me'));
    backend.expectOne('/api/me').flush(problem(401), { status: 401, statusText: 'Unauthorized' });

    await expect(call).rejects.toBeTruthy();
    expect(navigate).not.toHaveBeenCalled();
  });
});
