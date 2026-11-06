import type { Locator, Page, TestInfo } from '@playwright/test';
import type { Api } from '../harness/api';
import { ports } from '../harness/config';
import {
  expect,
  expectNoAxeViolations,
  expectNoHorizontalOverflow,
  shoot,
  test,
} from '../fixtures';

/**
 * W1, Accounts (the plan §5 and §7): a member's accounts, budget and runner hosts on one page with three tabs, and
 * the connect flow of the binding card (a token, or a sign-in that comes back). The phone project runs it too: a
 * member flow. Every test puts back what it changed, so that both projects and the later slices find the seed.
 */

const SLICE = 'w1';

/** A suffix that keeps the names a test makes apart between the two projects and between runs. */
function unique(testInfo: TestInfo): string {
  return `${testInfo.project.name}-${Date.now().toString(36)}`;
}

async function openAccounts(page: Page, path: string): Promise<void> {
  await page.goto(path);
  await expect(page.getByRole('heading', { level: 1, name: 'Accounts' })).toBeVisible();
}

/** A system's row on Accounts › Systems, by the system's name. */
function system(page: Page, name: string): Locator {
  return page.getByRole('listitem', { name, exact: true });
}

/** Everything a page state must pass: axe with no serious or critical finding, and no sideways scroll. */
async function check(
  page: Page,
  testInfo: TestInfo,
  shot?: string,
  options: { dialog?: boolean } = {},
): Promise<void> {
  await expectNoAxeViolations(page, testInfo);
  await expectNoHorizontalOverflow(page);
  // A dialog covers the viewport only: its shot is of the viewport, not of the whole page under it.
  if (shot) await shoot(page, testInfo, SLICE, shot, { fullPage: !options.dialog });
}

test.describe('W1: Accounts', { tag: ['@app', '@phone'] }, () => {
  test('the Systems tab says, per system, how a conversation would act', async ({
    pageAs,
    appPath,
  }, testInfo) => {
    const alice = await pageAs('alice');
    await openAccounts(alice, appPath('/accounts'));
    const github = system(alice, 'GitHub');
    await expect(github.getByTestId('account-chip')).toHaveText('Your own account');
    await expect(github.getByTestId('account-says')).toHaveText(
      'Agents act on GitHub as you: alice.',
    );
    await expect(github.getByTestId('account-meta')).toContainText('Connected with a token');
    const jira = system(alice, 'Jira');
    await expect(jira.getByTestId('account-chip')).toHaveText('Via Analysts, read-only');
    await expect(jira.getByTestId('account-says')).toContainText(
      'agents act with the credential svc-dev, lent to Analysts',
    );
    // Nothing waits on Alice, so nothing is orange.
    await expect(alice.locator('main .text-human')).toHaveCount(0);
    await check(alice, testInfo, 'systems-alice');

    const bob = await pageAs('bob');
    await openAccounts(bob, appPath('/accounts'));
    await expect(system(bob, 'GitHub').getByTestId('account-chip')).toHaveText('Your own account');
    await expect(system(bob, 'Jira').getByTestId('account-chip')).toHaveText('Not connected');
    await expect(
      system(bob, 'Jira').getByRole('button', { name: 'Paste a token for Jira' }),
    ).toBeVisible();

    const dave = await pageAs('dave');
    await openAccounts(dave, appPath('/accounts'));
    for (const name of ['GitHub', 'Jira']) {
      await expect(system(dave, name).getByTestId('account-chip')).toHaveText('Not connected');
      await expect(system(dave, name).getByTestId('account-says')).toContainText(
        'or when an agent first needs it',
      );
    }
    await check(dave, testInfo, 'systems-dave');
  });

  test('a token is connected, refused with the system’s reason, and disconnected @serial', async ({
    pageAs,
    appPath,
    api,
    apiPath,
    seed,
  }, testInfo) => {
    const dave = await pageAs('dave');
    try {
      await openAccounts(dave, appPath('/accounts'));
      const github = system(dave, 'GitHub');
      await github.getByRole('button', { name: 'Paste a token for GitHub' }).click();
      const dialog = dave.getByRole('dialog', { name: 'Paste a token for GitHub' });
      await expect(
        dialog.getByText('A personal access token of your own GitHub account'),
      ).toBeVisible();

      // A token the system does not know: the refusal in words, never the token.
      await dialog.getByLabel('GitHub token').fill('gh-token-nobody-knows');
      await dialog.getByRole('button', { name: 'Check and connect' }).click();
      const refusal = dialog.getByTestId('token-error');
      await expect(refusal).toHaveText('GitHub answered 401 when asked whose the token is.');
      await expect(refusal).not.toContainText('gh-token-nobody-knows');
      await check(dave, testInfo, 'token-refused', { dialog: true });

      await dialog.getByLabel('GitHub token').fill('gh-token-dave');
      await dialog.getByRole('button', { name: 'Check and connect' }).click();
      await expect(dialog).toBeHidden();
      await expect(github.getByTestId('account-chip')).toHaveText('Your own account');
      await expect(github.getByTestId('account-says')).toHaveText(
        'Agents act on GitHub as you: dave.',
      );

      await github.getByRole('button', { name: 'Disconnect your GitHub account' }).click();
      const confirm = dave.getByRole('alertdialog', { name: 'Disconnect your GitHub account?' });
      await check(dave, testInfo);
      await confirm.getByRole('button', { name: 'Disconnect', exact: true }).click();
      await expect(confirm).toBeHidden();
      await expect(github.getByTestId('account-chip')).toHaveText('Not connected');
    } finally {
      await disconnect(await api('dave'), apiPath(`/me/accounts/${seed.systems.github.id}`));
    }
  });

  test('the OAuth sign-in returns to Accounts with the account connected @serial', async ({
    pageAs,
    appPath,
    api,
    apiPath,
    seed,
    stubs,
  }, testInfo) => {
    const admin = await api('admin');
    const app = apiPath(`/systems/${seed.systems.github.id}/app`);
    const daveAccount = apiPath(`/me/accounts/${seed.systems.github.id}`);
    await admin.put(app, { clientId: 'e2e-github-app', clientSecret: 'e2e-github-secret' });
    try {
      await stubs.setOAuthIdentity('github', 'dave');
      const dave = await pageAs('dave');
      await openAccounts(dave, appPath('/accounts'));
      await system(dave, 'GitHub').getByRole('button', { name: 'Sign in with GitHub' }).click();

      // Through the stub's authorization page and the server's callback, back to Accounts, which drops the outcome
      // from the URL once it said it.
      const result = dave.getByTestId('sign-in-result');
      await expect(result).toHaveText('GitHub is connected. Agents act there as you: dave.');
      await expect(result).toHaveAttribute('role', 'status');
      await expect(dave).toHaveURL(appPath('/accounts'));
      await expect(system(dave, 'GitHub').getByTestId('account-chip')).toHaveText(
        'Your own account',
      );
      await expect(system(dave, 'GitHub').getByTestId('account-meta')).toContainText(
        'Connected by signing in',
      );
      await check(dave, testInfo, 'signed-in');

      // Declined at the system: a sentence, and nothing connected.
      await disconnect(await api('dave'), daveAccount);
      await stubs.setOAuthIdentity('github', 'deny');
      await openAccounts(dave, appPath('/accounts'));
      await system(dave, 'GitHub').getByRole('button', { name: 'Sign in with GitHub' }).click();
      await expect(dave.getByTestId('sign-in-result')).toHaveText(
        'You declined at the system, so nothing was connected.',
      );
      await expect(system(dave, 'GitHub').getByTestId('account-chip')).toHaveText('Not connected');
      await check(dave, testInfo);
    } finally {
      await stubs.setOAuthIdentity('github', 'oauth-user');
      await disconnect(await api('dave'), daveAccount);
      await admin.delete(app, { ok: [204, 404] });
    }
  });

  test('the Budget tab shows the budget, its spend and its reset @serial', async ({
    pageAs,
    appPath,
    api,
    apiPath,
    seed,
    litellm,
  }, testInfo) => {
    const principalId = seed.people.alice.principalId;
    const admin = await api('admin');
    const alice = await api('alice');
    const access = apiPath(`/principals/${principalId}/model-access`);
    /** Waits until the key's sync wrote what LiteLLM says (an admin's change syncs the key after its commit). */
    const synced = (want: { spentUsd: number; budgetUsd: number; keyStatus: string }) =>
      expect
        .poll(
          async () => {
            const view = await alice.get(apiPath('/me/model-access'));
            return {
              spentUsd: view.spentUsd,
              budgetUsd: view.budgetUsd,
              keyStatus: view.keyStatus,
            };
          },
          { timeout: 30_000 },
        )
        .toEqual(want);

    const page = await pageAs('alice');
    try {
      await openAccounts(page, appPath('/accounts?tab=budget'));
      const budget = page.getByTestId('budget');
      await expect(budget.getByTestId('budget-of')).toHaveText('left of $50.00 per 30 days');
      await expect(budget.getByTestId('budget-whose')).toHaveText('The company’s default');
      await expect(budget.getByTestId('budget-models')).toHaveText(
        'Every model the gateway serves',
      );
      await expect(page.getByTestId('subscriptions-empty')).toBeVisible();

      // An admin gives Alice a budget of her own; its sync reads the spend LiteLLM has.
      await litellm.setSpend(principalId, 12.5);
      await admin.patch(access, { budgetUsd: 80 });
      await synced({ spentUsd: 12.5, budgetUsd: 80, keyStatus: 'OK' });
      await page.reload();
      await expect(budget.getByTestId('budget-left')).toHaveText('$67.50');
      await expect(budget.getByTestId('budget-of')).toHaveText('left of $80.00 per 30 days');
      await expect(budget.getByTestId('budget-spent')).toHaveText('$12.50');
      await expect(budget.getByTestId('budget-whose')).toHaveText('Set for you by an admin');
      await expect(budget.getByTestId('budget-resets')).toHaveText(
        /^Resets \d{1,2} [A-Z][a-z]{2}$/,
      );
      await expect(budget.getByRole('meter', { name: 'Budget spent' })).toHaveAttribute(
        'aria-valuetext',
        '$12.50 of $80.00 spent',
      );
      await check(page, testInfo, 'budget');

      // Spent: the budget waits for its reset, in a neutral notice.
      await litellm.setSpend(principalId, 80);
      await admin.patch(access, { budgetUsd: 75 });
      await synced({ spentUsd: 80, budgetUsd: 75, keyStatus: 'BUDGET_SPENT' });
      await page.reload();
      await expect(budget.getByTestId('budget-notice')).toContainText('Your budget is spent until');
      await expect(budget.getByTestId('budget-left')).toHaveText('$0.00');
      await expect(page.locator('main .text-human')).toHaveCount(0);
      await check(page, testInfo, 'budget-spent');
    } finally {
      await litellm.setSpend(principalId, 0);
      await admin.patch(access, { clearBudget: true });
      await synced({ spentUsd: 0, budgetUsd: seed.gateway.defaultBudgetUsd, keyStatus: 'OK' });
    }
  });

  test('the Hosts tab registers a personal runner host and shows its token once @serial', async ({
    pageAs,
    appPath,
    api,
    apiPath,
  }, testInfo) => {
    const name = `bob-laptop-${unique(testInfo)}`;
    const bob = await pageAs('bob');
    try {
      await openAccounts(bob, appPath('/accounts?tab=hosts'));
      await expect(bob.getByTestId('hosts-empty')).toBeVisible();
      await check(bob, testInfo, 'hosts-empty');

      await bob.getByRole('button', { name: 'Register a runner host' }).click();
      const dialog = bob.getByRole('dialog', { name: 'Register a runner host of yours' });
      await dialog.getByLabel('Name').fill(name);
      await dialog.getByRole('button', { name: 'Register' }).click();

      const setup = bob.getByRole('dialog', { name: `Set up ${name}` });
      const token = setup.getByTestId('runner-token');
      await expect(token).toHaveText(/^tfr_[A-Za-z0-9_-]{43}$/);
      const shown = (await token.textContent())?.trim() ?? '';
      await expect(setup.getByText(`tinfirm-runner-host login --server`)).toContainText(shown);
      await expect(setup.getByText('tinfirm-runner-host credential add claude')).toBeVisible();
      await check(bob, testInfo, 'host-token', { dialog: true });

      // Once closed, the token is gone for good; the host is listed, offline until it connects.
      await setup.getByRole('button', { name: 'Done' }).click();
      await expect(setup).toBeHidden();
      await expect(bob.getByText(shown)).toHaveCount(0);
      const host = bob.getByTestId('runner-host').filter({ hasText: name });
      await expect(host.getByTestId('host-state')).toHaveText('Offline · never connected');
      await expect(host.getByTestId('host-slots')).toHaveText('0 of 1 slot in use');
      await expect(host).toContainText('The host reports its subscriptions when it connects.');
      await check(bob, testInfo, 'hosts');

      await host.getByRole('button', { name: `Remove ${name}` }).click();
      const confirm = bob.getByRole('alertdialog', { name: `Remove ${name}?` });
      await confirm.getByRole('button', { name: 'Remove runner host' }).click();
      await expect(confirm).toBeHidden();
      await expect(host).toHaveCount(0);
      await expect(bob.getByTestId('hosts-empty')).toBeVisible();
    } finally {
      const bobApi = await api('bob');
      const hosts: { id: string; name: string }[] = await bobApi.all(apiPath('/runner-hosts'));
      for (const left of hosts.filter((h) => h.name === name)) {
        await bobApi.delete(apiPath(`/runner-hosts/${left.id}`), { ok: [204, 404] });
      }
    }
  });

  test('one’s own MCP server is registered, refused at a private address, and switched off @serial', async ({
    pageAs,
    appPath,
    api,
    apiPath,
  }, testInfo) => {
    const name = `Carol notes ${unique(testInfo)}`;
    const carol = await pageAs('carol');
    const admin = await api('admin');
    try {
      await openAccounts(carol, appPath('/accounts'));
      await expect(carol.getByTestId('personal-empty')).toBeVisible();
      await carol.getByRole('button', { name: 'Add your MCP server' }).click();
      const dialog = carol.getByRole('dialog', { name: 'Add your MCP server' });
      await dialog.getByLabel('Name').fill(name);
      // A personal server is never reached at a private address, whatever the operator allows.
      await dialog.getByLabel('Address').fill(`http://127.0.0.1:${ports.stubs}/mcp`);
      await dialog.getByRole('button', { name: 'Add server' }).click();
      await expect(dialog).toContainText('Tinfirm may not call this address');
      await check(carol, testInfo, 'mcp-refused', { dialog: true });

      // A public address (never called before its tools are read) registers it, and its page opens. Not a
      // documentation range: the guard refuses those as special-use, as no internet host is in them.
      await dialog.getByLabel('Address').fill('https://140.82.112.3/mcp');
      await dialog.getByRole('button', { name: 'Add server' }).click();
      // nosemgrep: javascript.lang.security.audit.detect-non-literal-regexp.detect-non-literal-regexp -- built from the test's own values, never input
      await expect(carol).toHaveURL(new RegExp(`${appPath('/systems/')}[0-9a-f-]{36}$`));

      await openAccounts(carol, appPath('/accounts'));
      const row = system(carol, name);
      await expect(row).toContainText('Your MCP server');
      await expect(row.getByTestId('account-chip')).toHaveText('Not connected');
      await expect(row.getByRole('link', { name: `Settings and tools of ${name}` })).toBeVisible();
      await check(carol, testInfo, 'mcp-server');

      // The company switches personal servers off: it stays, binds for nobody, and no new one is offered.
      await admin.put(apiPath('/system-settings'), { personalMcpServers: false });
      await carol.reload();
      await expect(carol.getByTestId('personal-off')).toBeVisible();
      await expect(row.getByTestId('account-chip')).toHaveText('Switched off');
      await expect(carol.getByRole('button', { name: 'Add your MCP server' })).toHaveCount(0);
      await check(carol, testInfo, 'mcp-switched-off');
    } finally {
      await admin.put(apiPath('/system-settings'), { personalMcpServers: true });
      await removeOwnSystems(await api('carol'), apiPath, name);
    }
  });
});

/** Disconnects one's own account on a system, when there is one (the seed holds none for Dave). */
async function disconnect(person: Api, path: string): Promise<void> {
  await person.delete(path, { ok: [204, 404] });
}

async function removeOwnSystems(
  person: Api,
  apiPath: (suffix: string) => string,
  name: string,
): Promise<void> {
  const systems: { id: string; name: string; personal: boolean }[] = await person.all(
    apiPath('/systems'),
  );
  for (const own of systems.filter((s) => s.personal && s.name === name)) {
    await person.delete(apiPath(`/systems/${own.id}`), { ok: [204, 404] });
  }
}
