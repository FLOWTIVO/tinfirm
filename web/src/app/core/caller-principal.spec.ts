import { HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import type { Role } from '../api/generated/models';
import { aModelAccess, IDS } from '../testing/fixtures';
import { fakeCurrentCompany, provideTestHttp, settle } from '../testing/test-helpers';
import { CallerPrincipal } from './caller-principal';

describe('CallerPrincipal (plan §9, gap 2)', () => {
  function setup(role: Role | null, instanceAdmin = false) {
    TestBed.configureTestingModule({
      providers: [...provideTestHttp(), fakeCurrentCompany(role, instanceAdmin).provider],
    });
    return {
      http: TestBed.inject(HttpTestingController),
      principal: TestBed.inject(CallerPrincipal),
    };
  }

  it('reads the caller’s principal from their model access', async () => {
    const { http, principal } = setup('MEMBER');
    expect(principal.id()).toBeNull();
    await settle();
    http.expectOne('/api/companies/c1/me/model-access').flush(aModelAccess());
    await settle();
    expect(principal.id()).toBe(IDS.alice);
    expect(principal.modelAccess()?.budgetUsd).toBe(50);
    http.verify();
  });

  it('asks nothing for an instance admin who is not a member', async () => {
    const { http, principal } = setup(null, true);
    await settle();
    http.expectNone('/api/companies/c1/me/model-access');
    expect(principal.id()).toBeNull();
  });
});
