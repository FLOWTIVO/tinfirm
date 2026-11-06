import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { settle } from '../../testing/test-helpers';
import { resetOfficeForTests, type OfficeApi } from './office';
import { OutlookCallback } from './outlook-callback';

function office(ui: OfficeApi['context']['ui']): OfficeApi {
  return {
    onReady: () => Promise.resolve({ host: ui ? 'Outlook' : null, platform: null }),
    context: { ui },
    AsyncResultStatus: { Succeeded: 'succeeded', Failed: 'failed' },
    CoercionType: { Text: 'text' },
    EventType: {
      ItemChanged: 'olkItemSelectedChanged',
      DialogMessageReceived: 'dialogMessageReceived',
      DialogEventReceived: 'dialogEventReceived',
    },
  };
}

async function open(url: string): Promise<HTMLElement> {
  TestBed.configureTestingModule({
    providers: [provideRouter([{ path: 'outlook/callback', component: OutlookCallback }])],
  });
  const harness = await RouterTestingHarness.create();
  await harness.navigateByUrl(url);
  await settle(4);
  return harness.routeNativeElement as HTMLElement;
}

describe('the Outlook add-in’s sign-in callback', () => {
  beforeEach(() => resetOfficeForTests());
  afterEach(() => delete window.Office);

  it('hands the code and the state to the task pane', async () => {
    const messageParent = vi.fn();
    window.Office = office({ displayDialogAsync: () => undefined, messageParent });
    await open('/outlook/callback?code=the-code&state=the-state');
    expect(JSON.parse(messageParent.mock.calls[0][0] as string)).toEqual({
      code: 'the-code',
      state: 'the-state',
    });
  });

  it('hands a refusal over as well', async () => {
    const messageParent = vi.fn();
    window.Office = office({ displayDialogAsync: () => undefined, messageParent });
    await open('/outlook/callback?error=access_denied&state=s');
    expect(JSON.parse(messageParent.mock.calls[0][0] as string)).toEqual({
      error: 'access_denied',
      state: 's',
    });
  });

  it('says it can be closed outside Office’s dialog', async () => {
    window.Office = office(undefined);
    const page = await open('/outlook/callback?code=c&state=s');
    expect(page.querySelector('[data-testid="callback-outside"]')).not.toBeNull();
  });
});
