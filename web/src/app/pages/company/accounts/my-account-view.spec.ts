import type { MyAccount } from '../../../api/generated/models';
import { aGrantChoice, aMyAccount, anAccount, aSystemRef, IDS } from '../../../testing/fixtures';
import { myAccountView } from './my-account-view';

type Binding = MyAccount['binding'];

/** Every value of `MyAccount.binding`: a value the API adds and this table lacks breaks the build. */
const EVERY_BINDING: Record<Binding, true> = {
  OWN: true,
  GRANTED: true,
  CHOOSE: true,
  CONNECT: true,
  RECONNECT_YOURS: true,
  RECONNECT_COMPANY: true,
  RECONNECT_OR_GRANT: true,
  UNAVAILABLE: true,
  NOT_NEEDED: true,
};

const DEVS = aGrantChoice({
  grantId: 'grant-devs',
  groupId: 'g-devs',
  groupName: 'Developers',
  credentialName: 'CI bot',
  access: 'WRITE',
});

/** The reader's standing on GitHub as the API answers it, per binding (CONVENTIONS §15.3). */
const ITEMS: Record<Binding, MyAccount> = {
  OWN: aMyAccount(),
  GRANTED: aMyAccount({ account: null, binding: 'GRANTED', grants: [aGrantChoice()] }),
  CHOOSE: aMyAccount({ account: null, binding: 'CHOOSE', grants: [DEVS, aGrantChoice()] }),
  CONNECT: aMyAccount({ account: null, binding: 'CONNECT' }),
  RECONNECT_YOURS: aMyAccount({
    account: anAccount({ status: 'RECONNECT', lastError: 'Bad credentials' }),
    binding: 'RECONNECT_YOURS',
  }),
  RECONNECT_OR_GRANT: aMyAccount({
    account: anAccount({ status: 'RECONNECT' }),
    binding: 'RECONNECT_OR_GRANT',
    grants: [aGrantChoice()],
  }),
  RECONNECT_COMPANY: aMyAccount({
    account: null,
    binding: 'RECONNECT_COMPANY',
    grants: [aGrantChoice()],
  }),
  UNAVAILABLE: aMyAccount({
    system: aSystemRef({ id: IDS.crm, name: 'My CRM', kind: 'MCP', personal: true }),
    account: anAccount({ systemId: IDS.crm, kind: 'TOKEN', login: null }),
    binding: 'UNAVAILABLE',
    oauthAvailable: false,
  }),
  NOT_NEEDED: aMyAccount({
    system: aSystemRef({ id: IDS.crm, name: 'Logs', kind: 'MCP', personal: false }),
    account: null,
    binding: 'NOT_NEEDED',
    oauthAvailable: false,
  }),
};

describe('myAccountView (plan §5: Accounts › Systems)', () => {
  it('has a row for every binding of the contract', () => {
    expect(Object.keys(ITEMS).sort()).toEqual(Object.keys(EVERY_BINDING).sort());
  });

  it.each`
    binding                 | chip                         | tone         | connect      | disconnect | says
    ${'OWN'}                | ${'Your own account'}        | ${'ok'}      | ${'again'}   | ${true}    | ${'Agents act on GitHub as you: alice-novak.'}
    ${'GRANTED'}            | ${'Via Analysts, read-only'} | ${'ok'}      | ${'own'}     | ${false}   | ${'agents act with the credential svc-reader, lent to Analysts'}
    ${'CHOOSE'}             | ${'Choose per conversation'} | ${'ok'}      | ${'own'}     | ${false}   | ${'asks which one to act with'}
    ${'CONNECT'}            | ${'Not connected'}           | ${'idle'}    | ${'connect'} | ${false}   | ${'or when an agent first needs it'}
    ${'RECONNECT_YOURS'}    | ${'Sign in again'}           | ${'you'}     | ${'again'}   | ${true}    | ${'Your GitHub account (alice-novak) stopped working.'}
    ${'RECONNECT_OR_GRANT'} | ${'Sign in again'}           | ${'you'}     | ${'again'}   | ${true}    | ${'or a conversation offers the grant of Analysts (read-only) instead'}
    ${'RECONNECT_COMPANY'}  | ${'Waits for an admin'}      | ${'waiting'} | ${'own'}     | ${false}   | ${'which an admin must sign in again. Admins were told.'}
    ${'UNAVAILABLE'}        | ${'Switched off'}            | ${'off'}     | ${null}      | ${true}    | ${'Personal MCP servers are switched off in this company.'}
    ${'NOT_NEEDED'}         | ${'No account needed'}       | ${'ok'}      | ${null}      | ${false}   | ${'Logs needs no account: agents call it without one.'}
  `(
    '$binding reads “$chip” and offers $connect',
    ({ binding, chip, tone, connect, disconnect, says }) => {
      const view = myAccountView(ITEMS[binding as Binding]);
      expect(view).toMatchObject({ chip, tone, connect, disconnect });
      expect(view.says).toContain(says);
    },
  );

  it('is orange only where the reader must act', () => {
    const orange = (Object.keys(ITEMS) as Binding[]).filter(
      (b) => myAccountView(ITEMS[b]).tone === 'you',
    );
    expect(orange.sort()).toEqual(['RECONNECT_OR_GRANT', 'RECONNECT_YOURS']);
  });

  it('lists the grants where a conversation chooses among them, and the broken account’s error', () => {
    expect(myAccountView(ITEMS.CHOOSE).grants).toEqual([
      'Developers: the credential CI bot, read and write',
      'Analysts: the credential svc-reader, read-only',
    ]);
    expect(myAccountView(ITEMS.GRANTED).grants).toEqual([]);
    expect(myAccountView(ITEMS.RECONNECT_YOURS).error).toBe('Bad credentials');
    expect(myAccountView(ITEMS.RECONNECT_OR_GRANT).grants).toEqual([
      'Analysts: the credential svc-reader, read-only',
    ]);
  });

  it('names one’s own MCP server and its kind', () => {
    expect(myAccountView(ITEMS.UNAVAILABLE)).toMatchObject({
      name: 'My CRM',
      kind: 'Your MCP server',
    });
    expect(myAccountView(ITEMS.OWN).kind).toBe('GitHub');
    expect(
      myAccountView(aMyAccount({ system: aSystemRef({ kind: 'JIRA_DC', name: 'Jira' }) })).kind,
    ).toBe('Jira Data Center');
  });

  it('says one’s own account without a login', () => {
    expect(myAccountView(aMyAccount({ account: anAccount({ login: null }) })).says).toBe(
      'Agents act on GitHub as you, with your own account.',
    );
  });
});
