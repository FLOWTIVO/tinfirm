import type { SystemKind } from '../../../api/generated/models';
import { aSystem } from '../../../testing/fixtures';
import {
  pollIntervalError,
  pollIntervalPhrase,
  pollsIssues,
  callbackUrlFor,
  isChatKind,
  managesSystem,
  registrableKinds,
  SYSTEM_KINDS,
  systemKind,
  systemStatus,
} from './system-kinds';

describe('system kinds', () => {
  it.each`
    kind            | baseUrl           | repositories | tools          | app      | authHeader | registrable | accounts
    ${'JIRA_DC'}    | ${'Base URL'}     | ${false}     | ${'fixed'}     | ${true}  | ${false}   | ${true}     | ${true}
    ${'JIRA_CLOUD'} | ${'Site URL'}     | ${false}     | ${'fixed'}     | ${true}  | ${false}   | ${true}     | ${true}
    ${'GITHUB'}     | ${'API URL'}      | ${true}      | ${'fixed'}     | ${true}  | ${false}   | ${true}     | ${true}
    ${'GITLAB'}     | ${'Instance URL'} | ${true}      | ${'fixed'}     | ${true}  | ${false}   | ${true}     | ${true}
    ${'GIT'}        | ${null}           | ${true}      | ${null}        | ${false} | ${false}   | ${true}     | ${true}
    ${'MCP'}        | ${'MCP endpoint'} | ${false}     | ${'catalogue'} | ${true}  | ${true}    | ${true}     | ${true}
    ${'SLACK'}      | ${null}           | ${false}     | ${'fixed'}     | ${true}  | ${false}   | ${true}     | ${false}
    ${'TEAMS'}      | ${null}           | ${false}     | ${null}        | ${false} | ${false}   | ${true}     | ${false}
    ${'WEBHOOK'}    | ${null}           | ${false}     | ${null}        | ${false} | ${false}   | ${true}     | ${false}
    ${'BUZZ'}       | ${null}           | ${false}     | ${null}        | ${false} | ${false}   | ${false}    | ${false}
  `(
    '$kind takes base URL $baseUrl, repositories $repositories, tools $tools, an app $app, accounts $accounts',
    ({ kind, baseUrl, repositories, tools, app, authHeader, registrable, accounts }) => {
      const info = systemKind(kind as SystemKind);
      expect(info.baseUrl?.label ?? null).toBe(baseUrl);
      expect(info.repositories !== null).toBe(repositories);
      expect(info.tools).toBe(tools);
      expect(info.app).toBe(app);
      expect(info.authHeader).toBe(authHeader);
      expect(info.registrable).toBe(registrable);
      expect(info.accounts).toBe(accounts);
      if (accounts) expect(info.tokenHint).not.toBe('');
    },
  );

  it('knows every kind of the contract', () => {
    const kinds: SystemKind[] = [
      'JIRA_DC',
      'JIRA_CLOUD',
      'GITHUB',
      'GITLAB',
      'GIT',
      'MCP',
      'SLACK',
      'TEAMS',
      'BUZZ',
      'WEBHOOK',
    ];
    expect(SYSTEM_KINDS.map((k) => k.value).sort()).toEqual([...kinds].sort());
  });

  it('offers the registrable kinds by group: trackers, code hosts, MCP servers, chats, webhooks', () => {
    expect(registrableKinds().map((g) => [g.label, g.kinds.map((k) => k.value)])).toEqual([
      ['Trackers', ['JIRA_DC', 'JIRA_CLOUD']],
      ['Code hosts', ['GITHUB', 'GITLAB', 'GIT']],
      ['MCP servers', ['MCP']],
      ['Chats', ['SLACK', 'TEAMS']],
      ['Webhooks', ['WEBHOOK']],
    ]);
  });

  it.each`
    kind         | chat
    ${'SLACK'}   | ${true}
    ${'TEAMS'}   | ${true}
    ${'WEBHOOK'} | ${false}
    ${'GITHUB'}  | ${false}
  `('$kind is a chat: $chat', ({ kind, chat }) => {
    expect(isChatKind(kind)).toBe(chat);
  });

  it.each`
    status     | label               | tone
    ${'NEW'}   | ${'Not tested yet'} | ${'neutral'}
    ${'OK'}    | ${'Reachable'}      | ${'success'}
    ${'ERROR'} | ${'Unreachable'}    | ${'destructive'}
  `('a $status system reads "$label"', ({ status, label, tone }) => {
    expect(systemStatus({ status })).toEqual({ label, tone });
  });

  it.each`
    case                             | system                                                                           | url
    ${'before an app, the server’s'} | ${aSystem({ app: null, oauthCallbackUrl: 'https://t.test/api/oauth/callback' })} | ${'https://t.test/api/oauth/callback'}
    ${'with an app'}                 | ${aSystem()}                                                                     | ${'https://tinfirm.acme.test/api/oauth/callback'}
    ${'with an app, the app’s'}      | ${aSystem({ oauthCallbackUrl: null })}                                           | ${'https://tinfirm.acme.test/api/oauth/callback'}
    ${'for no manager, none'}        | ${aSystem({ app: null, oauthCallbackUrl: null })}                                | ${null}
  `('names the callback URL $case', ({ system, url }) => {
    expect(callbackUrlFor(system)).toBe(url);
  });

  it.each`
    personal | managesCompany | manages
    ${false} | ${true}        | ${true}
    ${false} | ${false}       | ${false}
    ${true}  | ${false}       | ${true}
    ${true}  | ${true}        | ${true}
  `(
    'a personal $personal system is managed $manages by one who manages the company $managesCompany',
    ({ personal, managesCompany, manages }) => {
      expect(managesSystem({ personal }, managesCompany)).toBe(manages);
    },
  );
});

describe('a tracker’s poll interval', () => {
  it.each<[SystemKind | '', boolean]>([
    ['JIRA_DC', true],
    ['JIRA_CLOUD', true],
    ['GITHUB', true],
    ['GITLAB', true],
    ['GIT', false],
    ['MCP', false],
    ['SLACK', false],
    ['WEBHOOK', false],
    ['', false],
  ])('is asked of %s: %s', (kind, polled) => {
    expect(pollsIssues(kind)).toBe(polled);
  });

  it.each<[number, string]>([
    [30, 'every 30 seconds'],
    [60, 'every minute'],
    [90, 'every 90 seconds'],
    [300, 'every 5 minutes'],
    [3600, 'every hour'],
  ])('says %i seconds as “%s”', (seconds, phrase) => {
    expect(pollIntervalPhrase(seconds)).toBe(phrase);
  });

  it.each<[string, boolean]>([
    ['', true],
    ['30', true],
    [' 3600 ', true],
    ['29', false],
    ['3601', false],
    ['1.5', false],
    ['a minute', false],
  ])('checks “%s”: %s', (typed, valid) => {
    expect(pollIntervalError(typed) === null).toBe(valid);
  });
});
