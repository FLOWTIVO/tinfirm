import { HttpTestingController } from '@angular/common/http/testing';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import type {
  ExternalSystem,
  KillSwitches,
  Role,
  SystemCredential,
  SystemLastTest,
  SystemTestResult,
  SystemTool,
  WebhookDeliveryPage,
} from '../../../api/generated/models';
import {
  aJiraSystem,
  aPausedSystem,
  aPersonalMcpServer,
  aSlackSystem,
  aTeamsSystem,
  aSystem,
  aSystemCredential,
  aSystemCredentialList,
  aSystemRepository,
  aSystemTool,
  aTriggerSummary,
  aWebhookDelivery,
  aWebhookSystem,
  noSwitches,
} from '../../../testing/fixtures';
import { problemMessage } from '../../../api/problem';
import { receivesDataSentence } from './system-kinds';
import { type Answer, serve } from '../../../testing/serve';
import {
  fakeCurrentCompany,
  fakeNotify,
  problem,
  provideTestHttp,
  settle,
  typeInto,
} from '../../../testing/test-helpers';
import { words } from '../../../testing/words';
import {
  lastTestSentence,
  removalEffects,
  SystemPage,
  systemTabs,
  testSentence,
} from './system-page';

@Component({ selector: 'app-list-stub', template: '<h1>List</h1>' })
class ListStub {}

const C = '/api/companies/c1';
const API = `${C}/systems`;

describe('testSentence', () => {
  it.each`
    result                                                                                                       | sentence
    ${{ ok: true, withAccount: true, login: 'adam', message: 'Reached with your own account' }}                  | ${'Reached with your own account, as adam.'}
    ${{ ok: true, withAccount: true, message: 'Reached with your own account' }}                                 | ${'Reached with your own account.'}
    ${{ ok: true, withAccount: false, message: 'The server answers without a token' }}                           | ${'The server answers without a token. Tested without an account: you hold no working one on it, and it has no single working credential to use.'}
    ${{ ok: true, withAccount: false, credentialRequired: true, message: 'Reached: the system answered 401' }}   | ${'Reached: the system answered 401. Tested without an account, and it asks for one: you hold no working one on it, and it has no single working credential to use.'}
    ${{ ok: false, withAccount: false, message: 'Connection refused' }}                                          | ${'Not reached: Connection refused.'}
    ${{ ok: false, withAccount: true, message: 'The system answered 502' }}                                      | ${'The system answered 502.'}
    ${{ ok: false, withAccount: true, credentialRequired: true, message: 'Your account was not accepted: 401' }} | ${'Your account was not accepted: 401. Sign in again, or store a new token, on Accounts.'}
  `('says "$sentence"', ({ result, sentence }) => {
    expect(testSentence({ credentialRequired: false, ...result } as SystemTestResult)).toBe(
      sentence,
    );
  });

  it('names the credential it used by the name the server gives (ADR-0072)', () => {
    const result: SystemTestResult = {
      ok: true,
      withAccount: true,
      credentialRequired: false,
      credentialId: 'cred-1',
      credentialName: 'CI bot',
      login: 'ci-bot',
      message: 'Reached with the credential',
    };
    expect(testSentence(result)).toBe('Reached with the credential CI bot, as ci-bot.');
    expect(testSentence({ ...result, credentialName: null })).toBe(
      'Reached with one of its credentials, as ci-bot.',
    );
    // Refused: the credential is given a new token or signed in again.
    expect(
      testSentence({
        ...result,
        ok: false,
        credentialRequired: true,
        login: null,
        message: 'The credential was not accepted: GitHub answered 401',
      }),
    ).toBe(
      'The credential was not accepted: GitHub answered 401. Store a new token for the credential CI bot, or sign it in again.',
    );
    // Named, but disabled or to sign in again: nothing was sent.
    expect(
      testSentence({
        ...result,
        ok: false,
        withAccount: false,
        login: null,
        message: 'The credential is disabled, or must be signed in again',
      }),
    ).toBe('Not tested: the credential CI bot is disabled, or must be signed in again.');
  });

  it.each`
    result                                                                                                           | sentence
    ${{ ok: true, withAccount: true, message: "Passed: the bot's token is accepted; the signing secret is stored" }} | ${"Passed: the bot's token is accepted; the signing secret is stored."}
    ${{ ok: false, withAccount: false, message: 'Failed: no bot is set' }}                                           | ${'Failed: no bot is set.'}
  `('says a chat system’s test as the server words it: "$sentence"', ({ result, sentence }) => {
    expect(testSentence(result as SystemTestResult, true)).toBe(sentence);
  });
});

describe('lastTestSentence', () => {
  const at = '2026-10-15T08:00:00Z';
  it.each`
    last                                                                         | chat     | sentence
    ${{ at, ok: true, withAccount: true, byName: 'Alex Rivera' }}                | ${false} | ${'passed with their own account'}
    ${{ at, ok: true, withAccount: true }}                                       | ${false} | ${'passed with the tester’s own account'}
    ${{ at, ok: true, withAccount: false, byName: 'Alex Rivera' }}               | ${false} | ${'passed without an account'}
    ${{ at, ok: false, withAccount: false, error: ' The system answered 502 ' }} | ${false} | ${'The system answered 502'}
    ${{ at, ok: false, withAccount: false, error: null }}                        | ${false} | ${'no reason given'}
    ${{ at, ok: true, withAccount: true, byName: 'Alex Rivera' }}                | ${true}  | ${'passed'}
  `('says "$sentence"', ({ last, chat, sentence }) => {
    expect(lastTestSentence(last as SystemLastTest, chat)).toBe(sentence);
  });

  it('names the credential it used, as the server names it now', () => {
    const last: SystemLastTest = {
      at,
      ok: true,
      withAccount: true,
      credentialId: 'cred-1',
      credentialName: 'CI bot',
    };
    expect(lastTestSentence(last)).toBe('passed with the credential CI bot');
    expect(lastTestSentence({ ...last, credentialName: null })).toBe(
      'passed with one of its credentials',
    );
    expect(
      lastTestSentence({ ...last, ok: false, error: 'The credential was not accepted: 401' }),
    ).toBe('The credential was not accepted: 401 (with the credential CI bot)');
  });
});

describe('systemTabs', () => {
  it.each`
    name                 | system                                     | canManage | tabs
    ${'GitHub'}          | ${aSystem()}                               | ${true}   | ${'overview repositories tools triggers'}
    ${'Jira'}            | ${aJiraSystem()}                           | ${true}   | ${'overview tools triggers'}
    ${'Git'}             | ${aSystem({ kind: 'GIT' })}                | ${true}   | ${'overview repositories'}
    ${'an MCP server'}   | ${aPersonalMcpServer({ personal: false })} | ${true}   | ${'overview tools'}
    ${'a personal one'}  | ${aPersonalMcpServer()}                    | ${true}   | ${'overview tools'}
    ${'Slack'}           | ${aSlackSystem()}                          | ${true}   | ${'overview bot channels tools triggers'}
    ${'Teams'}           | ${aTeamsSystem()}                          | ${true}   | ${'overview bot channels triggers'}
    ${'a webhook'}       | ${aWebhookSystem()}                        | ${true}   | ${'overview mapping deliveries triggers'}
    ${'a webhook, read'} | ${aWebhookSystem()}                        | ${false}  | ${'overview mapping triggers'}
  `('gives $name the tabs $tabs', ({ system, canManage, tabs }) => {
    expect(systemTabs(system, canManage).join(' ')).toBe(tabs);
  });
});

describe('receivesDataSentence', () => {
  const said =
    'what they pass to its tools, which can include what they read elsewhere in the conversation.';

  it.each`
    kind         | receivesData | internalHost | sentence
    ${'JIRA_DC'} | ${true}      | ${false}     | ${`Agents send this system ${said}`}
    ${'GITLAB'}  | ${true}      | ${false}     | ${`Agents send this system ${said}`}
    ${'SLACK'}   | ${true}      | ${false}     | ${`Agents send this system ${said}`}
    ${'MCP'}     | ${true}      | ${false}     | ${`Agents send this system (a third-party server) ${said}`}
    ${'MCP'}     | ${true}      | ${true}      | ${`Agents send this system ${said}`}
    ${'GIT'}     | ${false}     | ${false}     | ${null}
    ${'TEAMS'}   | ${false}     | ${false}     | ${null}
    ${'WEBHOOK'} | ${false}     | ${false}     | ${null}
  `(
    'says for $kind (receives $receivesData, internal $internalHost): $sentence',
    ({ kind, receivesData, internalHost, sentence }) => {
      expect(receivesDataSentence({ kind, receivesData, internalHost })).toBe(sentence);
    },
  );

  it('follows the server’s field, not the kind', () => {
    expect(
      receivesDataSentence({ kind: 'GITHUB', receivesData: false, internalHost: false }),
    ).toBeNull();
  });
});

describe('removalEffects', () => {
  const known = { credentials: 1, repositories: '2', tools: 6, triggers: 0 };

  it('says what a code host’s removal stops, with what the page read of it', () => {
    expect(removalEffects(aSystem(), known)).toEqual([
      'Every account on it is disconnected: members’ own, and its 1 credential with what it is lent to.',
      'Agents lose its 2 repositories and its 6 tools.',
      'The sign-in app’s client secret is deleted.',
      'No trigger watches it, so none is deleted.',
    ]);
  });

  it('says it before the page has read the counts', () => {
    const unknown = { credentials: null, repositories: null, tools: null, triggers: null };
    expect(removalEffects(aJiraSystem(), unknown)).toEqual([
      'Every account on it is disconnected: members’ own, and the company’s credentials with what they are lent to.',
      'Agents lose its tools.',
      'The triggers that watch it are deleted; their conversations end, readable in the app.',
    ]);
  });

  it.each`
    name           | system                  | first
    ${'a webhook'} | ${aWebhookSystem()}     | ${'Its URL stops taking deliveries at once, and its secret is deleted.'}
    ${'Slack'}     | ${aSlackSystem()}       | ${'Its bot and secrets are deleted, and every member’s link to it goes.'}
    ${'your own'}  | ${aPersonalMcpServer()} | ${'Your account on it is disconnected.'}
  `('says what $name’s removal stops first', ({ system, first }) => {
    expect(removalEffects(system, { ...known, triggers: 2 })[0]).toBe(first);
  });
});

describe('SystemPage', () => {
  let http: HttpTestingController;
  let harness: RouterTestingHarness;
  let notify: ReturnType<typeof fakeNotify>['notify'];

  /** What the page's sections read, answered as a company that has nothing switched off. */
  function routes(
    system: ExternalSystem,
    extra: {
      tools?: SystemTool[];
      switches?: KillSwitches;
      triggers?: unknown[];
      deliveries?: WebhookDeliveryPage;
      credentials?: SystemCredential[];
    } = {},
  ): Record<string, Answer> {
    const at = `${API}/${system.id}`;
    return {
      [`GET ${at}`]: system,
      [`GET ${at}/repositories`]: { items: [aSystemRepository()] },
      [`GET ${at}/tools`]: { items: extra.tools ?? [aSystemTool()] },
      [`GET ${at}/polls`]: { items: [] },
      [`GET ${at}/chat/channels`]: { items: [] },
      [`GET ${at}/webhook/deliveries`]: extra.deliveries ?? { items: [aWebhookDelivery()] },
      [`GET ${at}/credentials`]: aSystemCredentialList(
        extra.credentials ?? [aSystemCredential({ systemId: system.id })],
      ),
      [`GET ${C}/triggers`]: { items: extra.triggers ?? [] },
      [`GET ${C}/kill-switches`]: extra.switches ?? noSwitches(),
      [`GET ${C}/inbox`]: { items: [], counts: {} },
      [`GET ${C}/service-identities`]: { items: [], nextCursor: null },
    };
  }

  /** Opens the page of the system and answers what it reads. */
  async function open(
    system: ExternalSystem | 404,
    role: Role = 'ADMIN',
    extra: {
      tools?: SystemTool[];
      switches?: KillSwitches;
      triggers?: unknown[];
      deliveries?: WebhookDeliveryPage;
      credentials?: SystemCredential[];
      url?: string;
    } = {},
  ) {
    const notifier = fakeNotify();
    notify = notifier.notify;
    TestBed.configureTestingModule({
      providers: [
        provideRouter(
          [
            { path: 'c/:companyId/systems', component: ListStub },
            { path: 'c/:companyId/accounts', component: ListStub },
            { path: 'c/:companyId/systems/:systemId', component: SystemPage },
          ],
          withComponentInputBinding(),
        ),
        ...provideTestHttp(),
        fakeCurrentCompany(role).provider,
        notifier.provider,
      ],
    });
    http = TestBed.inject(HttpTestingController);
    harness = await RouterTestingHarness.create();
    const id = system === 404 ? 'sys-gone' : system.id;
    await harness.navigateByUrl(extra.url ?? `/c/c1/systems/${id}`);
    await settle();
    if (system === 404) {
      http.expectOne(`${API}/${id}`).flush(problem(404), { status: 404, statusText: 'Not Found' });
      await serve(http, { [`GET ${C}/kill-switches`]: noSwitches() });
    } else {
      await serve(http, routes(system, extra));
    }
    harness.detectChanges();
  }

  const el = () => harness.routeNativeElement as HTMLElement;
  const by = (testId: string) => el().querySelector<HTMLElement>(`[data-testid=${testId}]`);
  const text = (node: Element | null | undefined) => words(node);
  const overlay = () => document.querySelector('.cdk-overlay-container') as HTMLElement;
  const inOverlay = (testId: string) =>
    overlay()?.querySelector<HTMLElement>(`[data-testid=${testId}]`) ?? null;
  const tab = (id: string) => el().querySelector<HTMLButtonElement>(`[role=tab][data-tab=${id}]`);
  const panel = (id: string) => el().querySelector<HTMLElement>(`#system-panel-${id}`);
  const tabTexts = () => Array.from(el().querySelectorAll('[role=tab]')).map((node) => text(node));

  async function menu(item: string) {
    by('system-menu')!.click();
    await settle();
    inOverlay(item)!.click();
    await settle();
  }

  afterEach(() => {
    http.verify();
    overlay()?.replaceChildren();
  });

  it.each`
    name        | system                                                    | app      | repositories | tools
    ${'GitHub'} | ${aSystem()}                                              | ${true}  | ${true}      | ${true}
    ${'Jira'}   | ${aJiraSystem()}                                          | ${true}  | ${false}     | ${true}
    ${'Git'}    | ${aSystem({ kind: 'GIT', baseUrl: null, app: null })}     | ${false} | ${true}      | ${false}
    ${'MCP'}    | ${aPersonalMcpServer({ personal: false, name: 'Notes' })} | ${true}  | ${false}     | ${true}
  `('draws the sections a $name system has', async ({ system, app, repositories, tools }) => {
    await open(system);
    expect(!!by('system-app')).toBe(app);
    expect(!!by('system-repositories')).toBe(repositories);
    expect(!!by('system-tools')).toBe(tools);
    // A git host has nothing to reach, so no Test and no reachability.
    expect(!!by('test-system')).toBe(system.baseUrl !== null);
    expect(!!by('system-status')).toBe(system.baseUrl !== null);
  });

  it.each`
    name                         | system                                                                       | sentence
    ${'GitHub'}                  | ${aSystem()}                                                                 | ${'Agents send this system what they pass to its tools, which can include what they read elsewhere in the conversation.'}
    ${'Slack'}                   | ${aSlackSystem()}                                                            | ${'Agents send this system what they pass to its tools, which can include what they read elsewhere in the conversation.'}
    ${'an MCP server elsewhere'} | ${aPersonalMcpServer({ personal: false, name: 'Notes' })}                    | ${'Agents send this system (a third-party server) what they pass to its tools, which can include what they read elsewhere in the conversation.'}
    ${'an internal MCP server'}  | ${aPersonalMcpServer({ personal: false, name: 'Wiki', internalHost: true })} | ${'Agents send this system what they pass to its tools, which can include what they read elsewhere in the conversation.'}
    ${'Git'}                     | ${aSystem({ kind: 'GIT', baseUrl: null, app: null, receivesData: false })}   | ${null}
    ${'a webhook'}               | ${aWebhookSystem()}                                                          | ${null}
  `('says under the name of $name what agents send it', async ({ system, sentence }) => {
    await open(system);
    const line = by('system-receives-data');
    if (sentence === null) {
      expect(line).toBeNull();
    } else {
      expect(text(line)).toBe(sentence);
    }
  });

  it('names a member’s own MCP server a third-party server', async () => {
    await open(aPersonalMcpServer(), 'MEMBER');
    expect(text(by('system-receives-data'))).toContain(
      'Agents send this system (a third-party server)',
    );
  });

  it('shows one tab at a time, with what each holds, and no handle as a setting', async () => {
    await open(aSystem({ pollIntervalSeconds: 60 }));
    expect(tabTexts()).toEqual(['Overview', 'Repositories 1', 'Tools 1', 'Triggers 0']);
    expect(tab('overview')?.getAttribute('aria-selected')).toBe('true');
    expect(panel('overview')?.hidden).toBe(false);
    expect(panel('tools')?.hidden).toBe(true);
    expect(text(el().querySelector('header'))).not.toContain('Tools as');
    expect(text(el())).not.toContain('Handle');
    // The tools' intro names them as a run does, read-only.
    expect(text(by('tools-handle'))).toBe('github_…');

    tab('tools')!.click();
    await settle();
    harness.detectChanges();
    expect(TestBed.inject(Router).url).toBe('/c/c1/systems/sys-github?tab=tools');
    expect(tab('tools')?.getAttribute('aria-selected')).toBe('true');
    expect(tab('tools')?.tabIndex).toBe(0);
    expect(tab('overview')?.tabIndex).toBe(-1);
    expect(panel('tools')?.hidden).toBe(false);
    expect(panel('overview')?.hidden).toBe(true);
  });

  it('moves between the tabs with the arrow keys, Home and End', async () => {
    await open(aSystem());
    const key = async (name: string, from: string) => {
      tab(from)!.dispatchEvent(new KeyboardEvent('keydown', { key: name, bubbles: true }));
      await settle();
      harness.detectChanges();
    };
    await key('ArrowRight', 'overview');
    expect(tab('repositories')?.getAttribute('aria-selected')).toBe('true');
    expect(document.activeElement).toBe(tab('repositories'));
    await key('End', 'repositories');
    expect(tab('triggers')?.getAttribute('aria-selected')).toBe('true');
    await key('ArrowRight', 'triggers');
    expect(tab('overview')?.getAttribute('aria-selected')).toBe('true');
    await key('ArrowLeft', 'overview');
    expect(tab('triggers')?.getAttribute('aria-selected')).toBe('true');
    await key('Home', 'triggers');
    expect(tab('overview')?.getAttribute('aria-selected')).toBe('true');
  });

  it('opens the tab the address names', async () => {
    await open(aSystem(), 'ADMIN', { url: '/c/c1/systems/sys-github?tab=repositories' });
    expect(tab('repositories')?.getAttribute('aria-selected')).toBe('true');
    expect(panel('repositories')?.hidden).toBe(false);
  });

  it('counts an MCP server’s tools to confirm, in orange on its tab', async () => {
    await open(aPersonalMcpServer({ personal: false, name: 'Notes' }), 'ADMIN', {
      tools: [
        aSystemTool({ name: 'search', fixed: false, effect: 'UNKNOWN' }),
        aSystemTool({ name: 'create', fixed: false, effect: 'UNKNOWN' }),
        aSystemTool({ name: 'read', fixed: false, effect: 'READ' }),
      ],
    });
    expect(text(tab('tools'))).toBe('Tools 3 2 to confirm');
    expect(tab('tools')?.querySelector('[data-testid=tab-to-confirm]')?.className).toContain(
      'text-human',
    );
    // An MCP server has no triggers.
    expect(tab('triggers')).toBeNull();
  });

  it.each`
    state                   | system                                                    | shows
    ${'never tested'}       | ${aSystem({ status: 'NEW' })}                             | ${'system-untested'}
    ${'reached'}            | ${aSystem({ status: 'OK' })}                              | ${'system-reached'}
    ${'failing'}            | ${aSystem({ status: 'ERROR', lastError: 'Bad gateway' })} | ${'system-last-error'}
    ${'paused by an admin'} | ${aSystem({ paused: true })}                              | ${'system-paused-note'}
  `('says in its health strip when it is $state', async ({ system, shows }) => {
    await open(system);
    expect(by('system-health')?.getAttribute('aria-label')).toBe('Health of GitHub');
    expect(by(shows)).not.toBeNull();
    if (system.paused) {
      expect(by('test-system')?.hasAttribute('disabled')).toBe(true);
      expect(text(by('system-paused'))).toBe('Paused');
      // Paused stands for the status: one chip.
      expect(by('system-status')).toBeNull();
    }
    if (system.lastError) {
      expect(text(by(shows))).toContain('Bad gateway');
      expect(text(by('system-status'))).toBe('Error');
      expect(by('test-again')).not.toBeNull();
    }
    if (system.status === 'OK' && !system.paused) expect(text(by('system-status'))).toBe('Ready');
  });

  it('says who paused it, when and why, and resumes it with its triggers', async () => {
    await open(aSystem({ paused: true }), 'ADMIN', {
      switches: noSwitches({ switches: [aPausedSystem()] }),
    });
    expect(text(by('system-paused-note'))).toContain('Paused by Adam Admin');
    expect(text(by('system-paused-note'))).toContain('“The app was leaked”');

    by('health-resume')!.click();
    await settle();
    expect(text(inOverlay('resume-dialog'))).toContain('Resume its trigger too');
    expect(text(inOverlay('resume-dialog'))).toContain('Review pull requests');
    overlay().querySelector<HTMLInputElement>('[data-testid=resume-triggers]')!.click();
    inOverlay('confirm-resume')!.click();
    await settle();
    const req = http.expectOne(`${API}/sys-github/resume`);
    expect(req.request.body).toEqual({ resumeTriggers: false });
    req.flush({ systemId: 'sys-github', triggersResumed: 0, triggersStillPaused: 1 });
    await settle();
    expect(notify.success).toHaveBeenCalledWith('GitHub is resumed', '1 trigger stays paused.');
    await serve(http, {
      [`GET ${API}/sys-github`]: aSystem(),
      [`GET ${C}/kill-switches`]: noSwitches(),
    });
  });

  it('pauses it from the menu, with a reason, and reads it again', async () => {
    await open(aSystem());
    await menu('pause-system');
    const confirm = inOverlay('pause-dialog-confirm') as HTMLButtonElement;
    expect(confirm.disabled).toBe(true);
    typeInto(overlay().querySelector('#pause-dialog-reason'), 'The app was leaked');
    overlay().querySelector<HTMLInputElement>('[data-testid=pause-stop-running]')!.click();
    await settle();
    confirm.click();
    await settle();
    const req = http.expectOne(`${API}/sys-github/pause`);
    expect(req.request.body).toEqual({ reason: 'The app was leaked', stopRunning: true });
    req.flush({
      systemId: 'sys-github',
      pausedAt: '2026-10-14T10:00:00Z',
      reason: 'The app was leaked',
      runsCancelled: 1,
      triggersPaused: 0,
    });
    await settle();
    expect(notify.success).toHaveBeenCalledWith('GitHub is paused', '1 turn cancelled.');
    await serve(http, {
      [`GET ${API}/sys-github`]: aSystem({ paused: true }),
      [`GET ${C}/kill-switches`]: noSwitches({ switches: [aPausedSystem()] }),
    });
    harness.detectChanges();
    expect(text(by('system-paused'))).toBe('Paused');
  });

  it('tests the system, says the outcome and reads its status again', async () => {
    const system = aSystem({ status: 'NEW' });
    await open(system);
    by('test-system')!.click();
    await settle();
    http
      .expectOne((r) => r.url === `${API}/${system.id}/test` && r.method === 'POST')
      .flush({
        ok: true,
        withAccount: true,
        credentialRequired: false,
        login: 'adam',
        message: 'Reached with your own account',
      });
    await settle();
    http.expectOne(`${API}/${system.id}`).flush({
      ...system,
      status: 'OK',
      lastTest: { at: new Date().toISOString(), ok: true, withAccount: true, byName: 'Adam Admin' },
    });
    await settle();
    harness.detectChanges();
    // This visit's own test says the most: who the account is at the system.
    expect(text(by('test-result'))).toMatch(
      /^Tested .+ by you · Reached with your own account, as adam\.$/,
    );
    expect(by('test-result')?.getAttribute('data-ok')).toBe('true');
    expect(by('system-last-test')).toBeNull();
    expect(by('system-status')?.getAttribute('data-status')).toBe('OK');
  });

  const hoursAgo = (hours: number) => new Date(Date.now() - hours * 3_600_000).toISOString();

  it.each`
    state                         | role        | system                                                                                                                                                               | line                                                                       | ok
    ${'passed with an account'}   | ${'ADMIN'}  | ${aSystem({ status: 'OK', lastTest: { at: hoursAgo(2), ok: true, withAccount: true, byName: 'Alex Rivera', byPrincipalId: 'p-alex' } })}                             | ${'Tested 2 hours ago by Alex Rivera · passed with their own account'}     | ${'true'}
    ${'failed'}                   | ${'ADMIN'}  | ${aSystem({ status: 'ERROR', lastError: 'Bad gateway', lastTest: { at: hoursAgo(3), ok: false, withAccount: false, error: 'Bad gateway', byName: 'Alex Rivera' } })} | ${'Test failed 3 hours ago by Alex Rivera · Bad gateway'}                  | ${'false'}
    ${'passed, read by a member'} | ${'MEMBER'} | ${aSystem({ status: 'OK', lastTest: { at: hoursAgo(2), ok: true, withAccount: false } })}                                                                            | ${'Tested 2 hours ago · passed without an account'}                        | ${'true'}
    ${'passed with a credential'} | ${'ADMIN'}  | ${aSystem({ status: 'OK', lastTest: { at: hoursAgo(2), ok: true, withAccount: true, byName: 'Alex Rivera', credentialId: 'cred-ci', credentialName: 'CI bot' } })}   | ${'Tested 2 hours ago by Alex Rivera · passed with the credential CI bot'} | ${'true'}
  `('says the stored last test when it $state', async ({ role, system, line, ok }) => {
    await open(system, role);
    expect(text(by('system-last-test'))).toBe(line);
    expect(by('system-last-test')?.getAttribute('data-ok')).toBe(ok);
    expect(by('system-untested')).toBeNull();
    expect(by('system-reached')).toBeNull();
    // A failed test is the admin's to run again.
    expect(!!by('test-again')).toBe(ok === 'false' && role === 'ADMIN');
  });

  it('leaves the strip to the status when a refresh failed after the last test passed', async () => {
    await open(
      aPersonalMcpServer({
        personal: false,
        name: 'Notes',
        status: 'ERROR',
        lastError: 'The server answered 503',
        lastTest: { at: hoursAgo(5), ok: true, withAccount: false, byName: 'Alex Rivera' },
      }),
    );
    expect(by('system-last-test')).toBeNull();
    expect(text(by('system-last-error'))).toBe(
      'Its tools could not be read · The server answered 503',
    );
  });

  it('says a paused system’s refusal in words', async () => {
    const system = aSystem();
    await open(system);
    by('test-system')!.click();
    await settle();
    http
      .expectOne(`${API}/${system.id}/test`)
      .flush(problem(409, 'SYSTEM_PAUSED'), { status: 409, statusText: 'Conflict' });
    await settle();
    // The words are problem.ts's own, for every page that meets a paused system.
    const [title, error, overrides] = notify.error.mock.calls[0];
    expect(title).toBe("Couldn't test the system");
    expect(problemMessage(error, overrides)).toBe(
      'An admin paused this system: testing it, reading its tools and connecting accounts on it wait until it is resumed.',
    );
  });

  it.each`
    name        | system                                                                               | line
    ${'GitHub'} | ${aSystem({ pollIntervalSeconds: 300 })}                                             | ${'Every 5 minutes'}
    ${'Jira'}   | ${aJiraSystem({ pollIntervalSeconds: 60 })}                                          | ${'Every minute'}
    ${'MCP'}    | ${aPersonalMcpServer({ personal: false, name: 'Notes', pollIntervalSeconds: null })} | ${null}
  `('says how often a $name system is checked for changes', async ({ system, line }) => {
    await open(system);
    if (line) expect(text(by('system-poll-interval'))).toBe(line);
    else expect(by('system-poll-interval')).toBeNull();
  });

  it('holds the company’s credentials on its overview, lent to groups and service accounts', async () => {
    await open(aSystem());
    expect(panel('overview')?.querySelector('[data-testid=system-credentials]')).not.toBeNull();
    const row = by('credential');
    expect(text(row?.querySelector('[data-testid=credential-name]'))).toBe('svc-reader');
    expect(text(row?.querySelector('[data-testid=credential-grant]'))).toContain('Analysts');
    expect(text(row?.querySelector('[data-testid=credential-lending]'))).toContain('svc-reader');
  });

  it('tests with the credential the admin picks when the system holds several', async () => {
    const system = aSystem({ status: 'NEW' });
    await open(system, 'ADMIN', {
      credentials: [
        aSystemCredential(),
        aSystemCredential({ id: 'cred-ci', name: 'CI bot', login: 'ci-bot', grants: [] }),
      ],
    });
    const select = el().querySelector<HTMLSelectElement>('select#test-credential')!;
    expect(Array.from(select.options).map((o) => text(o))).toEqual([
      'With the usual account',
      'With svc-reader',
      'With CI bot',
    ]);
    select.value = 'cred-ci';
    select.dispatchEvent(new Event('change'));
    await settle();
    by('test-system')!.click();
    await settle();
    const req = http.expectOne((r) => r.url === `${API}/${system.id}/test`);
    expect(req.request.params.get('credentialId')).toBe('cred-ci');
    req.flush({
      ok: true,
      withAccount: true,
      credentialRequired: false,
      credentialId: 'cred-ci',
      credentialName: 'CI bot',
      login: 'ci-bot',
      message: 'Reached with the credential',
    });
    await settle();
    http.expectOne(`${API}/${system.id}`).flush({ ...system, status: 'OK' });
    await settle();
    harness.detectChanges();
    expect(text(by('test-result'))).toMatch(/Reached with the credential CI bot, as ci-bot\.$/);
  });

  it('offers to add a credential when a test reached it without any account and it asked for one', async () => {
    const system = aPersonalMcpServer({ personal: false, name: 'Notes', status: 'NEW' });
    await open(system, 'ADMIN', { credentials: [] });
    by('test-system')!.click();
    await settle();
    http
      .expectOne((r) => r.url === `${API}/${system.id}/test`)
      .flush({
        ok: true,
        withAccount: false,
        credentialRequired: true,
        message: 'The server answers and asks for a token',
      });
    await settle();
    http.expectOne(`${API}/${system.id}`).flush({ ...system, status: 'OK' });
    await settle();
    harness.detectChanges();
    expect(text(by('test-result'))).toContain(
      'The server answers and asks for a token. Tested without an account, and it asks for one',
    );
    // From another tab too: the strip shows above every tab, and the Overview opens with its dialog.
    el().querySelector<HTMLElement>('#system-tab-tools')!.click();
    await settle();
    harness.detectChanges();
    by('health-add-credential')!.click();
    await settle();
    harness.detectChanges();
    expect(overlay().querySelector('#credential-name')).not.toBeNull();
    expect(text(overlay().querySelector('h2'))).toBe('Add a credential to Notes');
  });

  it('offers no credential when a test reached it without one and it asked for none', async () => {
    // Jira takes accounts, but this test was answered without asking for one.
    const system = aJiraSystem({ status: 'NEW' });
    await open(system, 'ADMIN', { credentials: [] });
    by('test-system')!.click();
    await settle();
    http
      .expectOne((r) => r.url === `${API}/${system.id}/test`)
      .flush({
        ok: true,
        withAccount: false,
        credentialRequired: false,
        message: 'Reached: the system answered 200',
      });
    await settle();
    http.expectOne(`${API}/${system.id}`).flush({ ...system, status: 'OK' });
    await settle();
    harness.detectChanges();
    expect(text(by('test-result'))).toContain('Tested without an account');
    expect(by('health-add-credential')).toBeNull();
  });

  it('holds no credential on a personal server', async () => {
    await open(aPersonalMcpServer(), 'MEMBER');
    expect(by('system-credentials')).toBeNull();
  });

  it('sets the app up from the health strip', async () => {
    await open(aJiraSystem({ oauthCallbackUrl: 'https://t.test/api/oauth/callback' }));
    expect(text(by('health-app'))).toBe('Sign-in app: not set up — accounts connect by token only');
    by('health-set-app')!.click();
    await settle();
    expect(overlay().querySelector('#app-form')).not.toBeNull();
  });

  it.each`
    name       | system            | tabs
    ${'Slack'} | ${aSlackSystem()} | ${['Overview', 'Bot', 'Channels 0', 'Tools 1', 'Triggers 0']}
    ${'Teams'} | ${aTeamsSystem()} | ${['Overview', 'Bot', 'Channels 0', 'Triggers 0']}
  `('draws a $name system’s tabs, its bot in the health strip', async ({ system, tabs }) => {
    await open(system);
    expect(tabTexts()).toEqual(tabs);
    expect(by('chat-bot')).not.toBeNull();
    expect(by('chat-setup')).not.toBeNull();
    expect(text(by('health-bot'))).toContain('Bot: set · lent to');
    // Slack's app signs people in for history (open point 13); Teams has neither.
    expect(!!by('system-app')).toBe(system.kind === 'SLACK');
    expect(by('system-repositories')).toBeNull();
    expect(by('system-base-url')).toBeNull();
    expect(by('test-system')).not.toBeNull();
  });

  it('draws a webhook without reachability: its deliveries and secret instead', async () => {
    await open(aWebhookSystem());
    expect(tabTexts()).toEqual(['Overview', 'Mapping', 'Deliveries 1', 'Triggers 0']);
    expect(by('system-status')).toBeNull();
    expect(by('test-system')).toBeNull();
    expect(text(by('webhook-last-delivery'))).toContain('fired 1 trigger');
    // Without the server's counts, the strip says no week.
    expect(by('webhook-week')).toBeNull();
    expect(text(by('health-secret'))).toContain('Secret set');
    expect(by('webhook-setup')).not.toBeNull();
  });

  it('counts a webhook’s last 7 days on its tab and in its health strip', async () => {
    await open(aWebhookSystem(), 'ADMIN', {
      deliveries: {
        items: [aWebhookDelivery()],
        nextCursor: 'more',
        lastSevenDays: {
          since: '2026-10-08T08:00:00Z',
          deliveries: 312,
          failed: 2,
          refused: 4,
        },
      },
    });
    // The week's count, not the 25 of a first page.
    expect(tabTexts()).toEqual(['Overview', 'Mapping', 'Deliveries 312', 'Triggers 0']);
    expect(text(by('webhook-last-delivery'))).toMatch(
      /^Last delivery .+ · fired 1 trigger · 312 deliveries in 7 days, 2 not mapped, 4 refused$/,
    );
    expect(text(by('deliveries-week'))).toBe('312 deliveries in 7 days, 2 not mapped, 4 refused');
  });

  it('removes the system from the menu, saying what stops, and goes back to the list', async () => {
    const system = aSystem();
    await open(system);
    await menu('delete-system');
    const effects = text(overlay().querySelector('[data-testid=remove-effects]'));
    expect(effects).toContain('Every account on it is disconnected');
    expect(effects).toContain('and its 1 credential with what it is lent to');
    expect(effects).toContain('Agents lose its 1 repository and its 1 tool.');
    expect(effects).toContain('No trigger watches it, so none is deleted.');
    inOverlay('confirm-delete-system')!.click();
    await settle();
    http.expectOne((r) => r.url === `${API}/${system.id}` && r.method === 'DELETE').flush(null);
    await settle();
    expect(TestBed.inject(Router).url).toBe('/c/c1/systems');
    expect(notify.success).toHaveBeenCalledWith('GitHub is removed');
  });

  it('counts the triggers that watch it, which its removal deletes', async () => {
    await open(aJiraSystem(), 'ADMIN', {
      triggers: [aTriggerSummary(), aTriggerSummary({ id: 't2', name: 'Triage new issues' })],
    });
    expect(text(tab('triggers'))).toBe('Triggers 2');
    await menu('delete-system');
    expect(text(overlay().querySelector('[data-testid=remove-effects]'))).toContain(
      'The 2 triggers that watch it are deleted; their conversations end, readable in the app.',
    );
  });

  it('shows a member their own MCP server, managed by them, with the way back to Accounts', async () => {
    const system = aPersonalMcpServer();
    await open(system, 'MEMBER');
    expect(by('system-personal')).not.toBeNull();
    expect(by('system-back')?.getAttribute('href')).toBe('/c/c1/accounts?tab=systems');
    expect(by('edit-system')).not.toBeNull();
    expect(by('test-system')).not.toBeNull();
    expect(by('refresh-tools')).not.toBeNull();
    // Only an admin pauses, and only a company system.
    by('system-menu')!.click();
    await settle();
    expect(inOverlay('pause-system')).toBeNull();
    expect(inOverlay('delete-system')).not.toBeNull();
  });

  it('shows a member a company system read-only', async () => {
    const system = aSystem({ app: null });
    await open(system, 'MEMBER');
    expect(by('edit-system')).toBeNull();
    expect(by('system-menu')).toBeNull();
    expect(by('add-repository')).toBeNull();
    expect(by('app-callback')).toBeNull();
    expect(by('system-credentials')).toBeNull();
    expect(by('system-app')?.getAttribute('data-has-app')).toBe('true');
  });

  it('is the not-found page and nothing more for a system the server does not show', async () => {
    await open(404);
    expect(text(el())).toContain('Page not found');
    expect(by('system-back')).toBeNull();
  });
});
