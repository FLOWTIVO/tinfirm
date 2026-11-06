import { TestBed } from '@angular/core/testing';
import type { AgentReadinessPayer, AgentReadinessSystem } from '../api/generated/models';
import {
  aBinding,
  aConversation,
  aFixedBinding,
  aGrantChoice,
  aGrantedBinding,
  aModelAccess,
  aMyAccount,
  anAgentSettings,
  aReadiness,
  aReadinessSystem,
  aRun,
  aSubscription,
  aSystemRef,
  aWaitingBinding,
  IDS,
} from '../testing/fixtures';
import { WhoActsWhoPays, WhoActsWhoPaysLine } from './who-acts-who-pays';
import {
  bindingLine,
  budgetLeft,
  conversationLineView,
  conversationPayerLine,
  conversationPayerShort,
  fromConversation,
  fromReadiness,
  fromRun,
  readinessPayerLine,
  readinessRunsLine,
  readinessSystemLine,
} from './who-acts-who-pays-view';

describe('who acts and who pays, before a conversation (AgentReadiness)', () => {
  const github = (binding: AgentReadinessSystem['binding'], extra = {}) =>
    aReadinessSystem({ binding, ...extra });

  it.each`
    binding                 | extra                                             | text                                                                                | tone
    ${'OWN'}                | ${{}}                                             | ${'GitHub: your own account'}                                                       | ${'ok'}
    ${'GRANTED'}            | ${{ access: 'READ', grantId: IDS.analystsGrant }} | ${'GitHub: via Analysts, read-only'}                                                | ${'ok'}
    ${'CHOOSE'}             | ${{}}                                             | ${'GitHub: you choose among your groups’ grants when it is first needed'}           | ${'later'}
    ${'CONNECT'}            | ${{}}                                             | ${'GitHub: connect when first needed'}                                              | ${'later'}
    ${'RECONNECT_YOURS'}    | ${{}}                                             | ${'GitHub: your account needs signing in again'}                                    | ${'you'}
    ${'RECONNECT_OR_GRANT'} | ${{}}                                             | ${'GitHub: sign in again, or act via Analysts'}                                     | ${'you'}
    ${'RECONNECT_COMPANY'}  | ${{}}                                             | ${'GitHub: waits until an admin signs the lent credential in again'}                | ${'blocked'}
    ${'UNAVAILABLE'}        | ${{ own: true }}                                  | ${'GitHub (your own server): not available: personal MCP servers are switched off'} | ${'blocked'}
  `('binding $binding reads “$text”', ({ binding, extra, text, tone }) => {
    const accounts = [aMyAccount({ binding: 'GRANTED', account: null, grants: [aGrantChoice()] })];
    const line = readinessSystemLine(github(binding, extra), accounts);
    expect(line).toMatchObject({ key: IDS.github, text, tone });
  });

  it('names a grant without the reader’s accounts as one of their group', () => {
    expect(readinessSystemLine(github('GRANTED', { access: 'WRITE' })).text).toBe(
      'GitHub: via a grant to your group, read and write',
    );
  });

  it('says which lent credential waits for an admin', () => {
    const accounts = [
      aMyAccount({ system: aSystemRef(), account: null, grants: [aGrantChoice()] }),
    ];
    expect(readinessSystemLine(github('RECONNECT_COMPANY'), accounts).detail).toBe(
      'The credential svc-reader, lent to Analysts',
    );
  });

  const payer = (p: Partial<AgentReadinessPayer>) =>
    aReadiness({
      payer: { state: 'READY', modelAccess: 'BUDGET', models: [], ...p },
    });

  it.each`
    case                         | payer                                                                          | text                                                  | tone
    ${'a budget'}                | ${{}}                                                                          | ${'Your budget: $38.20 of $50.00 left, resets 1 Nov'} | ${'ok'}
    ${'a spent budget'}          | ${{ state: 'WAITING', reason: 'BUDGET_SPENT', until: '2026-11-01T00:00:00Z' }} | ${'Your budget is spent until 1 Nov'}                 | ${'blocked'}
    ${'a host offline'}          | ${{ state: 'WAITING', reason: 'HOST_OFFLINE' }}                                | ${'Your subscription’s runner host is offline'}       | ${'blocked'}
    ${'no gateway'}              | ${{ state: 'REFUSED', reason: 'GATEWAY_NOT_CONFIGURED' }}                      | ${'An admin must set up model access first'}          | ${'blocked'}
    ${'an inactive principal'}   | ${{ state: 'REFUSED', reason: 'PRINCIPAL_INACTIVE' }}                          | ${'Your model access is inactive'}                    | ${'blocked'}
    ${'a refusal of a new kind'} | ${{ state: 'REFUSED', reason: 'SOMETHING_NEW', detail: 'Ask an admin' }}       | ${'Nobody can pay for a conversation now'}            | ${'blocked'}
  `('says who pays with $case', ({ payer: p, text, tone }) => {
    expect(readinessPayerLine(payer(p), aModelAccess())).toMatchObject({ text, tone });
  });

  it('says the first turn waits, and what a refusal’s detail says', () => {
    expect(
      readinessPayerLine(
        payer({ state: 'WAITING', reason: 'BUDGET_SPENT', until: '2026-11-01T00:00:00Z' }),
      ).detail,
    ).toBe('You can start; the first turn waits until 1 Nov.');
    expect(
      readinessPayerLine(payer({ state: 'REFUSED', reason: 'X', detail: 'Ask an admin' })).detail,
    ).toBe('Ask an admin');
  });

  it('names a subscription by its kind and host', () => {
    const readiness = aReadiness({
      settings: anAgentSettings({
        modelAccess: 'SUBSCRIPTION',
        subscription: { label: 'claude-max', runnerHostId: IDS.personalHost },
      }),
      payer: { state: 'READY', modelAccess: 'SUBSCRIPTION', models: [] },
    });
    expect(
      readinessPayerLine(readiness, aModelAccess({ subscriptions: [aSubscription()] })).text,
    ).toBe('Your Claude subscription on alice-macbook');
    expect(readinessPayerLine(readiness).text).toBe('Your subscription “claude-max”');
    expect(readinessRunsLine(readiness).text).toBe('Your personal host only');
  });

  it('says where it runs, or why it cannot', () => {
    expect(readinessRunsLine(aReadiness()).text).toBe('A company host');
    const none = aReadiness({
      hosts: { runnable: false, eligible: 0, reason: 'No runner host serves Codex' },
    });
    expect(readinessRunsLine(none)).toMatchObject({
      text: 'No runner host can run it now',
      tone: 'blocked',
      detail: 'No runner host serves Codex',
    });
  });

  it('puts the lines together, one per system', () => {
    const view = fromReadiness(aReadiness(), { access: aModelAccess() });
    expect(view.acts.map((l) => l.text)).toEqual([
      'GitHub: your own account',
      'Jira: connect when first needed',
    ]);
    expect(view.pays.text).toContain('Your budget');
    expect(view.runs?.text).toBe('A company host');
  });
});

describe('who acts and who pays in a conversation', () => {
  it.each`
    case                                | binding                                                 | mine     | tone
    ${'a bound account'}                | ${aBinding()}                                           | ${true}  | ${'ok'}
    ${'a grant'}                        | ${aGrantedBinding()}                                    | ${true}  | ${'ok'}
    ${'a Connect card, for its person'} | ${aWaitingBinding()}                                    | ${true}  | ${'you'}
    ${'a Connect card, for the Owner'}  | ${aWaitingBinding()}                                    | ${false} | ${'blocked'}
    ${'an admin to wait for'}           | ${aWaitingBinding({ reason: 'RECONNECT_COMPANY' })}     | ${true}  | ${'blocked'}
    ${'a binding that is gone'}         | ${aBinding({ state: 'GONE', reason: 'GRANT_REMOVED' })} | ${true}  | ${'gone'}
  `('reads $case by its own sentence', ({ binding, mine, tone }) => {
    expect(bindingLine(binding, mine)).toMatchObject({ text: binding.text, tone });
  });

  it('says a spent budget waits, neutral', () => {
    const line = conversationPayerLine(
      aConversation(),
      { principalId: IDS.alice },
      aModelAccess({ keyStatus: 'BUDGET_SPENT', spentUsd: 50 }),
    );
    expect(line).toMatchObject({ tone: 'blocked' });
    expect(line.text).toMatch(/^Your budget: \$0\.00 of \$50\.00 left/);
  });

  it('names the person’s own account as theirs for the Owner who reads it', () => {
    expect(bindingLine(aBinding(), false, 'Alice Novak').text).toBe(
      'GitHub: Alice Novak’s own account',
    );
    expect(bindingLine(aGrantedBinding(), false, 'Alice Novak').text).toBe(
      'GitHub: via Analysts, read-only',
    );
  });

  it('says a trigger’s binding acts read-only where its credential is lent so (ADR-0072)', () => {
    // The server's sentence says a grant's access, a trigger's only by `access`.
    expect(bindingLine(aFixedBinding(), true).text).toBe(
      "Jira: svc-reader's reader-bot, chosen by the trigger, read-only",
    );
    expect(bindingLine(aFixedBinding({ access: 'WRITE' }), true).text).toBe(
      "Jira: svc-reader's reader-bot, chosen by the trigger",
    );
    expect(
      conversationLineView(aConversation({ bindings: [aFixedBinding()] })).parts[0].lines[0].text,
    ).toBe("Jira: svc-reader's reader-bot, chosen by the trigger, read-only");
  });

  it('says “your” for the reader’s own budget, with what is left', () => {
    const view = fromConversation(
      aConversation({ bindings: [aGrantedBinding()] }),
      { principalId: IDS.alice },
      aModelAccess(),
    );
    expect(view.acts.map((l) => l.text)).toEqual(['GitHub: via Analysts, read-only']);
    expect(view.actsNote).toBe('Other systems connect when the agent first needs them.');
    expect(view.pays.text).toBe('Your budget: $38.20 of $50.00 left, resets 1 Nov');
    expect(view.runs?.text).toBe('Claude Code · last turn on office, a company host');
  });

  it('names the person’s budget for the Owner who reads it', () => {
    const view = fromConversation(aConversation(), { principalId: IDS.owner });
    expect(view.pays.text).toBe('Alice Novak’s budget');
    expect(view.acts[0].tone).toBe('ok');
  });

  it('says a subscription whose host was removed can no longer pay', () => {
    const view = fromConversation(
      aConversation({
        payer: {
          principalId: IDS.alice,
          name: 'Alice Novak',
          modelAccess: 'SUBSCRIPTION',
          subscriptionLabel: 'claude-max',
          subscriptionRunnerHostId: null,
        },
      }),
      { principalId: IDS.alice },
    );
    expect(view.pays).toMatchObject({ text: 'Your subscription “claude-max”', tone: 'gone' });
  });

  it('says that systems connect later before any is bound, and nothing once it ended', () => {
    expect(fromConversation(aConversation({ bindings: [] })).actsNote).toBe(
      'Systems connect when the agent first needs them.',
    );
    expect(fromConversation(aConversation({ state: 'ENDED' })).actsNote).toBeNull();
  });
});

describe('who acts and who pays for one run', () => {
  it('names the person, the payer and the host', () => {
    const view = fromRun(aRun());
    expect(view.acts[0].text).toBe('For Alice Novak');
    expect(view.pays.text).toBe('Alice Novak’s budget');
    expect(view.runs?.text).toBe('Claude Code on office, a company host');
    expect(fromRun(aRun(), { principalId: IDS.alice }).pays.text).toBe('Your budget');
  });

  it('says a queued run is not on a host yet, and names a subscription', () => {
    const view = fromRun(
      aRun({
        runnerHostName: null,
        runnerHostKind: null,
        modelAccess: 'SUBSCRIPTION',
        subscriptionLabel: 'claude-max',
      }),
    );
    expect(view.runs).toMatchObject({
      text: 'Claude Code, not on a runner host yet',
      tone: 'later',
    });
    expect(view.pays.text).toBe('Alice Novak’s subscription “claude-max”');
  });
});

describe('budgetLeft', () => {
  it('never goes below zero', () => {
    expect(budgetLeft(aModelAccess({ spentUsd: 80 }))).toBe('$0.00 of $50.00 left, resets 1 Nov');
    expect(budgetLeft(aModelAccess({ budgetResetsAt: null, spentUsd: null }))).toBe(
      '$50.00 of $50.00 left',
    );
    expect(budgetLeft(null)).toBeNull();
  });
});

describe('WhoActsWhoPays', () => {
  it('draws the three parts, orange only on what the reader acts on', async () => {
    const fixture = TestBed.createComponent(WhoActsWhoPays);
    fixture.componentRef.setInput(
      'conversation',
      aConversation({ bindings: [aBinding(), aWaitingBinding()] }),
    );
    fixture.componentRef.setInput('principalId', IDS.alice);
    fixture.componentRef.setInput('access', aModelAccess());
    await fixture.whenStable();
    const el: HTMLElement = fixture.nativeElement;

    expect(el.querySelector('h2')?.textContent?.trim()).toBe('Who acts and who pays');
    const lines = Array.from(el.querySelectorAll('[data-part=acts] li'));
    expect(lines.map((l) => l.getAttribute('data-tone'))).toEqual(['ok', 'you']);
    expect(lines[1].querySelector('span.text-human')?.textContent).toContain('Jira: connect');
    expect(el.querySelectorAll('.text-human')).toHaveLength(2);
    expect(el.querySelector('[data-part=pays]')?.textContent).toContain('$38.20 of $50.00 left');
    expect(el.querySelector('[data-part=runs]')?.textContent).toContain('office');
  });

  it('draws nothing without anything to say', async () => {
    const fixture = TestBed.createComponent(WhoActsWhoPays);
    await fixture.whenStable();
    expect(fixture.nativeElement.querySelector('section')).toBeNull();
  });

  it('draws a readiness with the reader’s accounts, unframed in columns', async () => {
    const fixture = TestBed.createComponent(WhoActsWhoPays);
    fixture.componentRef.setInput('readiness', aReadiness());
    fixture.componentRef.setInput('framed', false);
    fixture.componentRef.setInput('columns', true);
    fixture.componentRef.setInput('heading', 'When you use it');
    await fixture.whenStable();
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('h2')?.textContent?.trim()).toBe('When you use it');
    expect(el.querySelector('section')?.classList).not.toContain('border');
    expect(el.querySelector('dl')?.className).toContain('sm:grid-cols-3');
    expect(el.querySelectorAll('[data-part=acts] li')).toHaveLength(2);
  });
});

describe('who acts and who pays in one line', () => {
  const text = (view: ReturnType<typeof conversationLineView>) =>
    view.parts.map((p) => [p.label, p.lines.map((l) => l.text).join(', ')].join(' ').trim());

  it('says the three parts in a few words, for the reader', () => {
    const view = conversationLineView(
      aConversation({ bindings: [aBinding(), aGrantedBinding({ systemId: IDS.jira })] }),
      { principalId: IDS.alice },
      aModelAccess(),
    );
    expect(text(view)).toEqual([
      'Acts as GitHub: your own account, GitHub: via Analysts, read-only',
      'Pays from your budget, $38.20 left',
      'Runs on office',
    ]);
    expect(view.tone).toBe('ok');
  });

  it('names the person’s accounts and budget for the Owner who reads it', () => {
    const view = conversationLineView(aConversation(), { principalId: IDS.owner });
    expect(text(view).slice(0, 2)).toEqual([
      'Acts as GitHub: Alice Novak’s own account',
      'Pays from Alice Novak’s budget',
    ]);
  });

  it('takes the icon of the part that most needs a look', () => {
    expect(
      conversationLineView(aConversation({ bindings: [aBinding(), aWaitingBinding()] }), {
        principalId: IDS.alice,
      }).tone,
    ).toBe('you');
    expect(
      conversationLineView(
        aConversation(),
        { principalId: IDS.alice },
        aModelAccess({ keyStatus: 'BUDGET_SPENT', spentUsd: 50 }),
      ).tone,
    ).toBe('blocked');
  });

  it('says systems connect later before any is bound, and the runtime before any turn', () => {
    const view = conversationLineView(aConversation({ bindings: [], turns: [] }));
    expect(text(view)).toEqual([
      'Systems connect when first needed',
      'Pays from Alice Novak’s budget',
      'Runs Claude Code',
    ]);
    expect(view.tone).toBe('later');
  });

  it('says a subscription whose host was removed can no longer pay', () => {
    const payer = {
      principalId: IDS.alice,
      name: 'Alice Novak',
      modelAccess: 'SUBSCRIPTION' as const,
      subscriptionLabel: 'claude-max',
      subscriptionRunnerHostId: null,
    };
    expect(
      conversationPayerShort(aConversation({ payer }), { principalId: IDS.alice }),
    ).toMatchObject({ tone: 'gone' });
  });
});

describe('WhoActsWhoPaysLine', () => {
  it('draws the parts on one line, orange only where the reader acts', async () => {
    const fixture = TestBed.createComponent(WhoActsWhoPaysLine);
    fixture.componentRef.setInput(
      'conversation',
      aConversation({ bindings: [aBinding(), aWaitingBinding()] }),
    );
    fixture.componentRef.setInput('principalId', IDS.alice);
    fixture.componentRef.setInput('access', aModelAccess());
    await fixture.whenStable();
    const el: HTMLElement = fixture.nativeElement;
    expect(el.getAttribute('data-testid')).toBe('who-acts-who-pays');
    expect(el.querySelector('[role=group]')?.getAttribute('aria-label')).toBe(
      'Who acts and who pays',
    );
    expect(el.querySelector('[data-part=acts]')?.textContent).toContain('GitHub: your own account');
    expect(el.querySelectorAll('.text-human')).toHaveLength(2);
    expect(el.querySelector('[data-part=pays]')?.textContent).toContain('your budget, $38.20 left');
    // On a phone it is one line cut short, without where it runs.
    expect(el.querySelector('[data-part=runs]')?.className).toContain('max-sm:hidden');
  });
});
