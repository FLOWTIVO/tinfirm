import { HttpTestingController } from '@angular/common/http/testing';
import { type ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import type { MockInstance } from 'vitest';
import { Session } from '../../core/session';
import { currentUser, fakeNotify, provideTestHttp, settle } from '../../testing/test-helpers';
import { Profile } from './profile';

describe('Profile', () => {
  let fixture: ComponentFixture<Profile>;
  let http: HttpTestingController;
  let notify: ReturnType<typeof fakeNotify>['notify'];
  let navigate: MockInstance<Router['navigate']>;

  async function create() {
    const notifier = fakeNotify();
    notify = notifier.notify;
    TestBed.configureTestingModule({
      imports: [Profile],
      providers: [provideRouter([]), ...provideTestHttp(), notifier.provider],
    });
    TestBed.inject(Session).setUser(currentUser());
    navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(Profile);
    await settle();
    // The connected apps, the Gmail links and the personal access tokens read their own lists.
    http.expectOne('/api/me/oauth/grants').flush([]);
    http.expectOne('/api/me/gmail/links').flush({ items: [] });
    http.expectOne('/api/me/tokens').flush([]);
    await settle();
    await fixture.whenStable();
  }

  afterEach(() => http.verify());

  it('shows the person and reads their apps and tokens', async () => {
    await create();
    expect(fixture.nativeElement.textContent).toContain('Me Myself');
    expect(notify.warning).not.toHaveBeenCalled();
    expect(navigate).not.toHaveBeenCalled();
  });
});
