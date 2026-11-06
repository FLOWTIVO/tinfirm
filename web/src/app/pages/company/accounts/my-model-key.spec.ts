import { HttpTestingController } from '@angular/common/http/testing';
import { type ComponentFixture, TestBed } from '@angular/core/testing';
import type { ModelAccessView } from '../../../api/generated/models';
import { aModelAccess } from '../../../testing/fixtures';
import {
  fakeNotify,
  problem,
  provideTestHttp,
  settle,
  typeInto,
} from '../../../testing/test-helpers';
import { words } from '../../../testing/words';
import { MyModelKey } from './my-model-key';

const URL = '/api/companies/c1/me/model-access/key';

describe('MyModelKey', () => {
  let fixture: ComponentFixture<MyModelKey>;
  let http: HttpTestingController;
  let changed: ModelAccessView[];

  async function render(access: ModelAccessView) {
    TestBed.configureTestingModule({
      imports: [MyModelKey],
      providers: [...provideTestHttp(), fakeNotify().provider],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(MyModelKey);
    fixture.componentRef.setInput('companyId', 'c1');
    fixture.componentRef.setInput('access', access);
    changed = [];
    fixture.componentInstance.changed.subscribe((v) => changed.push(v));
    await settle();
    await fixture.whenStable();
  }

  const $ = <T extends Element = HTMLElement>(selector: string) =>
    (fixture.nativeElement as HTMLElement).querySelector<T>(selector);
  const click = async (selector: string) => {
    $<HTMLButtonElement>(selector)!.click();
    await settle();
    await fixture.whenStable();
  };

  afterEach(() => http.verify());

  it('lets a member enter their own key where the company allows it', async () => {
    await render(aModelAccess({ canEnterOwnKey: true }));
    expect(words($('[data-testid=my-model-key-state]'))).toBe(
      'No key yet: until one is entered, the work it pays for waits.',
    );
    expect($('[data-testid=remove-my-key]')).toBeNull();
    typeInto($('#my-model-key'), 'sk-my-key-from-the-team-zz99');
    await settle();
    await fixture.whenStable();
    await click('[data-testid=save-my-key]');
    const req = http.expectOne({ method: 'PUT', url: URL });
    expect(req.request.body).toEqual({ key: 'sk-my-key-from-the-team-zz99' });
    req.flush(
      aModelAccess({
        canEnterOwnKey: true,
        keyProvided: true,
        keyProvidedBySelf: true,
        keyHint: 'zz99',
      }),
    );
    await settle();
    expect(changed.map((v) => v.keyHint)).toEqual(['zz99']);
    expect($<HTMLInputElement>('#my-model-key')!.value).toBe('');
  });

  it('says when the gateway refuses the key', async () => {
    await render(aModelAccess({ canEnterOwnKey: true }));
    typeInto($('#my-model-key'), 'sk-not-the-gateways-key');
    await settle();
    await fixture.whenStable();
    await click('[data-testid=save-my-key]');
    http
      .expectOne({ method: 'PUT', url: URL })
      .flush(problem(400, 'MODEL_KEY_REFUSED'), { status: 400, statusText: 'Bad Request' });
    await settle();
    await fixture.whenStable();
    expect(words($('#my-model-key-error'))).toBe(
      'The gateway refused this key. Check it with your gateway team.',
    );
    expect(changed).toHaveLength(0);
  });

  it('shows a key an admin entered without letting the member change it', async () => {
    await render(aModelAccess({ keyProvided: true, keyHint: 'a1b2' }));
    expect(words($('[data-testid=my-model-key-state]'))).toContain(
      'Entered by an admin · ends in …a1b2',
    );
    expect($('#my-model-key')).toBeNull();
    expect(words($('[data-testid=my-model-key]'))).toContain(
      'An admin entered it; ask them to change it.',
    );
  });

  it('removes the key the member entered', async () => {
    await render(
      aModelAccess({ canEnterOwnKey: true, keyProvided: true, keyProvidedBySelf: true }),
    );
    await click('[data-testid=remove-my-key]');
    http
      .expectOne({ method: 'DELETE', url: URL })
      .flush(aModelAccess({ canEnterOwnKey: true, keyProvided: false }));
    await settle();
    expect(changed.map((v) => v.keyProvided)).toEqual([false]);
  });
});
