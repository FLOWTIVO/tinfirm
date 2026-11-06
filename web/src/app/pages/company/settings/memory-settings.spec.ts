import { HttpTestingController } from '@angular/common/http/testing';
import { type ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import type { MemorySettings, ModelAccessView } from '../../../api/generated/models';
import { aModelAccess } from '../../../testing/fixtures';
import {
  fakeCurrentCompany,
  fakeNotify,
  problem,
  provideTestHttp,
  settle,
  submitForm,
} from '../../../testing/test-helpers';
import { words } from '../../../testing/words';
import { MemorySettingsSection } from './memory-settings';
import { SettingsData } from './settings-data';

const BASE = '/api/companies/c1';
const SERVED = ['claude-haiku-4-5', 'claude-sonnet-4-5', 'gpt-5.4'];

/** The company's own key (ADR-0065), which allows the haiku model only. */
const companyKey = (overrides: Partial<ModelAccessView> = {}): ModelAccessView => ({
  ...aModelAccess({ models: ['claude-haiku-4-5'], modelsOverridden: true, ...overrides }),
  principalId: undefined,
});

describe('MemorySettingsSection', () => {
  let fixture: ComponentFixture<MemorySettingsSection>;
  let http: HttpTestingController;
  let el: HTMLElement;
  let notify: ReturnType<typeof fakeNotify>['notify'];

  async function open(
    memory: MemorySettings,
    options: { key?: ModelAccessView; served?: string[] | 'unconfigured' } = {},
  ) {
    const notifier = fakeNotify();
    notify = notifier.notify;
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        ...provideTestHttp(),
        fakeCurrentCompany('ADMIN').provider,
        notifier.provider,
        SettingsData,
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(MemorySettingsSection);
    el = fixture.nativeElement;
    await settle();
    for (const req of http.match((r) => r.url === `${BASE}/secrets`)) req.flush({ items: [] });
    http.expectOne(`${BASE}/memory-settings`).flush(memory);
    http.expectOne(`${BASE}/model-access/company-key`).flush(options.key ?? companyKey());
    const served = http.expectOne(`${BASE}/model-settings/models`);
    if (options.served === 'unconfigured') {
      served.flush(problem(409, 'GATEWAY_NOT_CONFIGURED'), { status: 409, statusText: 'Conflict' });
    } else {
      served.flush({ models: options.served ?? SERVED });
    }
    await settle();
    await fixture.whenStable();
  }

  const select = () => el.querySelector<HTMLSelectElement>('#memory-model')!;
  const options = () => Array.from(select().options).map((o) => [o.value, words(o)]);
  const choose = async (value: string) => {
    select().value = value;
    select().dispatchEvent(new Event('change'));
    await settle();
    await fixture.whenStable();
  };

  afterEach(() => http.verify());

  it('chooses a model the company key allows, and saves it', async () => {
    await open({ compactionModelAllowed: false });
    expect(words(el)).toContain('The company key pays for it, never a person or a trigger.');
    expect(options()).toEqual([
      ['', 'None: duplicates only'],
      ['claude-haiku-4-5', 'claude-haiku-4-5'],
    ]);
    expect(el.querySelector('[data-testid=memory-save]')).toBeNull();

    await choose('claude-haiku-4-5');
    expect(el.querySelector('[data-testid=memory-save]')).not.toBeNull();
    submitForm(el.querySelector('#memory-settings-form'));
    await settle();
    const put = http.expectOne({ method: 'PUT', url: `${BASE}/memory-settings` });
    expect(put.request.body).toEqual({ compactionModel: 'claude-haiku-4-5' });
    put.flush({ compactionModel: 'claude-haiku-4-5', compactionModelAllowed: true });
    await settle();
    await fixture.whenStable();
    expect(notify.success).toHaveBeenCalledWith(
      'Saved',
      'claude-haiku-4-5 tidies agents’ memories.',
    );
    expect(el.querySelector('[data-testid=memory-save]')).toBeNull();
  });

  it('says when the company key no longer allows the model, or its budget is spent', async () => {
    await open(
      { compactionModel: 'gpt-5.4', compactionModelAllowed: false },
      { key: companyKey({ keyStatus: 'BUDGET_SPENT' }) },
    );
    expect(options()).toEqual([
      ['', 'None: duplicates only'],
      ['gpt-5.4', 'gpt-5.4 (not offered now)'],
      ['claude-haiku-4-5', 'claude-haiku-4-5'],
    ]);
    const warnings = Array.from(el.querySelectorAll('[data-testid=memory-warning]')).map(words);
    expect(warnings).toEqual([
      'The company key no longer allows gpt-5.4: memories are only cleared of duplicates until you choose another model or widen the key’s models.',
      'The company key’s budget is spent: memories are only cleared of duplicates until it resets.',
    ]);
  });

  it('goes back to duplicates only, and shows a refusal in the server’s words', async () => {
    await open({ compactionModel: 'claude-haiku-4-5', compactionModelAllowed: true });
    await choose('');
    submitForm(el.querySelector('#memory-settings-form'));
    await settle();
    http
      .expectOne({ method: 'PUT', url: `${BASE}/memory-settings` })
      .flush(problem(400, 'MODEL_NOT_ALLOWED'), { status: 400, statusText: 'Bad Request' });
    await settle();
    await fixture.whenStable();
    expect(words(el.querySelector('[data-testid=memory-error]'))).toBe(
      'The company key does not allow this model. Widen its models first.',
    );
  });

  it('waits for the model gateway', async () => {
    await open(
      { compactionModelAllowed: false },
      {
        key: companyKey({ gatewayConfigured: false, keyStatus: undefined }),
        served: 'unconfigured',
      },
    );
    expect(select().disabled).toBe(true);
    expect(words(el.querySelector('[data-testid=memory-served-note]'))).toBe(
      'Set up the model gateway under Model access first.',
    );
  });
});
