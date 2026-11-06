import { HttpTestingController } from '@angular/common/http/testing';
import { type ComponentFixture, TestBed } from '@angular/core/testing';
import type { ModelAccessView } from '../../../api/generated/models';
import { aModelAccess, IDS } from '../../../testing/fixtures';
import {
  fakeNotify,
  problem,
  provideTestHttp,
  settle,
  submitForm,
  typeInto,
} from '../../../testing/test-helpers';
import { words } from '../../../testing/words';
import { PrincipalBudgetDialog } from './principal-budget-dialog';

const URL = `/api/companies/c1/principals/${IDS.svcReader}/model-access`;
const OWN = aModelAccess({
  principalId: IDS.svcReader,
  budgetUsd: 20,
  budgetOverridden: true,
  models: ['gpt-5.4'],
  modelsOverridden: true,
});

describe('PrincipalBudgetDialog', () => {
  let fixture: ComponentFixture<PrincipalBudgetDialog>;
  let http: HttpTestingController;
  let saved: ModelAccessView[];

  async function open(
    view: ModelAccessView,
    served = ['claude-sonnet-4-5', 'gpt-5.4'],
    provided = false,
  ) {
    TestBed.configureTestingModule({
      imports: [PrincipalBudgetDialog],
      providers: [...provideTestHttp(), fakeNotify().provider],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(PrincipalBudgetDialog);
    fixture.componentRef.setInput('companyId', 'c1');
    fixture.componentRef.setInput('principal', { id: IDS.svcReader, name: 'svc-reader' });
    fixture.componentRef.setInput('view', view);
    fixture.componentRef.setInput('defaults', { budgetUsd: 50, period: '30d', models: [] });
    fixture.componentRef.setInput('served', served);
    fixture.componentRef.setInput('provided', provided);
    saved = [];
    fixture.componentInstance.saved.subscribe((v) => saved.push(v));
    fixture.componentInstance.open.set(true);
    await settle();
    await fixture.whenStable();
  }

  const $ = <T extends Element = HTMLElement>(selector: string) =>
    document.querySelector<T>(selector);
  const click = async (selector: string) => {
    $<HTMLInputElement>(selector)!.click();
    await settle();
    await fixture.whenStable();
  };

  afterEach(() => {
    http.verify();
    document.querySelector('.cdk-overlay-container')?.replaceChildren();
  });

  it('reads the defaults beside the choices', async () => {
    await open(aModelAccess({ principalId: IDS.svcReader }));
    const dialog = $('hlm-dialog-content')!;
    expect(words($('#budget-default')!.closest('label'))).toBe(
      'The company’s default $50.00 per 30 days',
    );
    expect(words($('#models-default')!.closest('label'))).toBe(
      'The company’s default Every model the gateway serves',
    );
    expect($<HTMLInputElement>('#budget-default')!.checked).toBe(true);
    expect($<HTMLInputElement>('#models-default')!.checked).toBe(true);
    expect(words(dialog)).toContain('may spend through the gateway per 30 days');
  });

  it('shows what is spent now against the budget as chosen, and what an own one was', async () => {
    await open(OWN);
    const now = () => $('[data-testid=spent-now]')!;
    expect(words(now())).toBe('Spent now $11.80 of $20.00 Resets 1 Nov');
    expect($('[role=meter]')!.getAttribute('aria-valuenow')).toBe('59');
    typeInto($('#budget-amount'), '12');
    await settle();
    await fixture.whenStable();
    expect(words(now())).toContain('$11.80 of $12.00');
    expect(words($('#budget-amount')!.parentElement)).toContain('was $20.00');
    await click('#budget-default');
    expect(words(now())).toContain('$11.80 of $50.00');
  });

  it.each<[string, string[], object]>([
    ['the budget back to the default', ['#budget-default'], { clearBudget: true }],
    ['the models back to the default', ['#models-default'], { clearModels: true }],
    [
      'both back to the defaults',
      ['#budget-default', '#models-default'],
      { clearBudget: true, clearModels: true },
    ],
  ])('sends %s', async (_, clicks, body) => {
    await open(OWN);
    for (const selector of clicks) await click(selector);
    submitForm($('#principal-budget-form'));
    await settle();
    const req = http.expectOne({ method: 'PATCH', url: URL });
    expect(req.request.body).toEqual(body);
    req.flush(aModelAccess({ principalId: IDS.svcReader }));
    await settle();
    expect(saved).toHaveLength(1);
    expect(fixture.componentInstance.open()).toBe(false);
  });

  it('closes without a request when nothing changed', async () => {
    await open(OWN);
    submitForm($('#principal-budget-form'));
    await settle();
    http.expectNone(URL);
    expect(fixture.componentInstance.open()).toBe(false);
  });

  it.each<[string, () => Promise<void>, string]>([
    [
      'an amount that is none',
      async () => {
        await click('#budget-own');
        typeInto($('#budget-amount'), 'lots');
      },
      'Enter an amount from 0 to 1,000,000, such as 25 or 12.50.',
    ],
    [
      'own models with none picked',
      async () => {
        await click('#models-own');
      },
      'Pick at least one model.',
    ],
  ])('refuses %s', async (_, act, message) => {
    await open(aModelAccess({ principalId: IDS.svcReader }));
    await act();
    submitForm($('#principal-budget-form'));
    await settle();
    await fixture.whenStable();
    http.expectNone(URL);
    expect(words($('hlm-dialog-content'))).toContain(message);
  });

  it('says why the server refused', async () => {
    await open(aModelAccess({ principalId: IDS.svcReader }));
    await click('#models-own');
    $<HTMLInputElement>('[aria-label="Models it may use"] input')!.click();
    submitForm($('#principal-budget-form'));
    await settle();
    const req = http.expectOne({ method: 'PATCH', url: URL });
    expect(req.request.body).toEqual({ models: ['claude-sonnet-4-5'] });
    req.flush(problem(403), { status: 403, statusText: 'Forbidden' });
    await settle();
    await fixture.whenStable();
    expect(words($('[data-testid=budget-error]'))).toBe('Only owners and admins set budgets.');
    expect(saved).toHaveLength(0);
  });

  it('cannot limit the models before the gateway lists them', async () => {
    await open(aModelAccess({ principalId: IDS.svcReader }), []);
    expect($<HTMLInputElement>('#models-own')!.disabled).toBe(true);
    expect(words($('#models-own')!.closest('label'))).toBe(
      'Only these The gateway lists its models once it passes its test.',
    );
  });

  it('enters the key the gateway team made, and says when the gateway refuses it', async () => {
    await open(aModelAccess({ principalId: IDS.svcReader, keyStatus: 'ERROR' }), [], true);
    const dialog = () => words($('hlm-dialog-content'));
    expect(dialog()).toContain('Key of svc-reader');
    expect(words($('[data-testid=provided-key-state]'))).toBe(
      'No key yet: until one is entered, the work it pays for waits.',
    );
    // The gateway sets the budget: none to choose here.
    expect($('[data-testid=spent-now]')).toBeNull();
    expect($('#budget-own')!.closest('fieldset')!.classList).toContain('hidden');
    expect($('[data-testid=remove-key]')).toBeNull();

    typeInto($('#provided-key'), 'sk-wrong-key-0000');
    await settle();
    await fixture.whenStable();
    await click('[data-testid=save-key]');
    const refused = http.expectOne({ method: 'PUT', url: `${URL}/key` });
    expect(refused.request.body).toEqual({ key: 'sk-wrong-key-0000' });
    refused.flush(problem(400, 'MODEL_KEY_REFUSED'), { status: 400, statusText: 'Bad Request' });
    await settle();
    await fixture.whenStable();
    expect(words($('#provided-key-error'))).toBe(
      'The gateway refused this key. Check it with your gateway team.',
    );

    typeInto($('#provided-key'), 'sk-team-made-key-a1b2');
    await settle();
    await fixture.whenStable();
    await click('[data-testid=save-key]');
    const entered = http.expectOne({ method: 'PUT', url: `${URL}/key` });
    entered.flush(aModelAccess({ principalId: IDS.svcReader, keyProvided: true, keyHint: 'a1b2' }));
    await settle();
    expect(saved.map((v) => v.keyHint)).toEqual(['a1b2']);
    expect($<HTMLInputElement>('#provided-key')!.value).toBe('');
  });

  it('removes a provided key', async () => {
    await open(
      aModelAccess({ principalId: IDS.svcReader, keyProvided: true, keyHint: 'a1b2' }),
      [],
      true,
    );
    expect(words($('[data-testid=provided-key-state]'))).toContain(
      'Entered by an admin · ends in …a1b2',
    );
    await click('[data-testid=remove-key]');
    http
      .expectOne({ method: 'DELETE', url: `${URL}/key` })
      .flush(aModelAccess({ principalId: IDS.svcReader, keyProvided: false }));
    await settle();
    expect(saved.map((v) => v.keyProvided)).toEqual([false]);
  });
});
