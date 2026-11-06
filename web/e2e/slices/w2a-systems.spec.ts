import type { Page, TestInfo } from '@playwright/test';
import { urls } from '../harness/config';
import {
  expect,
  expectNoAxeViolations,
  expectNoHorizontalOverflow,
  shoot,
  test,
} from '../fixtures';

/**
 * W2a, the company's systems, their credentials, service accounts and grants (the plan §5, §7 and §8's W2 row;
 * ADR-0072): an admin registers a system (the kind first, then its fields), confirms an MCP tool's effect, adds a
 * credential on a system's page, lends it to a group and a service account, and suspends the service account. Runs
 * as Adam, the admin. Every test makes its own systems, identity and group
 * with names of their own, and removes them, so that both projects and the later slices find the seed as it was.
 */

const SLICE = 'w2a';

/** A suffix that keeps the names a test makes apart between the two projects and between runs. */
function unique(testInfo: TestInfo): string {
  return `${testInfo.project.name === 'phone' ? 'p' : 'd'}${Date.now().toString(36).slice(-5)}`;
}

/**
 * Everything a page state must pass: axe with no serious or critical finding, and no sideways scroll. A review shot
 * waits for the toasts to go, so that they hide nothing.
 */
async function check(
  page: Page,
  testInfo: TestInfo,
  shot?: string,
  options: { dialog?: boolean } = {},
): Promise<void> {
  await expectNoAxeViolations(page, testInfo);
  await expectNoHorizontalOverflow(page);
  if (shot) {
    if (process.env['E2E_SCREENSHOTS']) {
      await expect(page.locator('[data-sonner-toast]')).toHaveCount(0, { timeout: 15_000 });
    }
    await shoot(page, testInfo, SLICE, shot, { fullPage: !options.dialog });
  }
}

/**
 * Registers a system through the dialog's steps: its kind, its details, then its settings (both kinds the
 * specs register have some); answers the page's URL.
 */
async function register(
  page: Page,
  kind: string,
  fields: { name: string; baseUrl?: string; pollIntervalSeconds?: string },
): Promise<void> {
  await page.getByTestId('register-system').click();
  const dialog = page.getByRole('dialog', { name: 'Register a system' });
  await expect(dialog.getByLabel('Name')).toHaveCount(0);
  await expect(dialog.getByTestId('system-next')).toBeDisabled();
  // nosemgrep: javascript.lang.security.audit.detect-non-literal-regexp.detect-non-literal-regexp -- built from the test's own values, never input
  await dialog.getByRole('radio', { name: new RegExp(`^${kind}`) }).check();
  await dialog.getByTestId('system-next').click();
  await expect(dialog.getByTestId('system-step-count')).toHaveText('Step 2 of 3');
  await dialog.getByLabel('Name').fill(fields.name);
  // No handle to type: Tinfirm makes it from the name (CONVENTIONS §15.2).
  await expect(dialog.getByLabel('Handle')).toHaveCount(0);
  if (fields.baseUrl !== undefined)
    await dialog.getByRole('textbox', { name: /URL|endpoint/ }).fill(fields.baseUrl);
  await dialog.getByTestId('system-next').click();
  await expect(dialog.getByTestId('system-step-count')).toHaveText('Step 3 of 3');
  if (fields.pollIntervalSeconds !== undefined) {
    await dialog.getByLabel('Check for changes every').fill(fields.pollIntervalSeconds);
  }
  await dialog.getByRole('button', { name: 'Register' }).click();
  await expect(dialog).toBeHidden();
  await expect(page.getByRole('heading', { level: 1, name: fields.name })).toBeVisible();
}

/** Opens a tab of a system's page by its label. */
async function openTab(page: Page, label: string): Promise<void> {
  await page.getByRole('tab', { name: label }).click();
  await expect(page.getByRole('tab', { name: label })).toHaveAttribute('aria-selected', 'true');
}

/**
 * Runs an action of a system's page header: a button on a wide screen, behind its ⋯ on a phone; Pause and
 * Remove are always behind it.
 */
async function systemAction(page: Page, testId: string): Promise<void> {
  if (!(await page.getByTestId(testId).isVisible())) await page.getByTestId('system-menu').click();
  await page.getByTestId(testId).click();
}

async function removeSystem(page: Page, name: string): Promise<void> {
  await systemAction(page, 'delete-system');
  const dialog = page.getByRole('alertdialog', { name: `Remove ${name}?` });
  await dialog.getByRole('button', { name: `Remove ${name}` }).click();
  await expect(page.getByRole('heading', { level: 1, name: 'Systems' })).toBeVisible();
  await expect(page.getByText(name, { exact: true })).toHaveCount(0);
}

test.describe('W2a: systems, service accounts and grants', { tag: ['@app', '@phone'] }, () => {
  test.use({ persona: 'admin' });

  test('the systems by section, and the seeded GitHub with its repository and fixed tools', async ({
    page,
    appPath,
  }, testInfo) => {
    await page.goto(appPath('/systems'));
    await expect(page.getByRole('heading', { level: 1, name: 'Systems' })).toBeVisible();
    await expect(page.getByTestId('systems-tracker').getByTestId('system-row-jira')).toContainText(
      'Jira Data Center',
    );
    await expect(page.getByTestId('systems-code').getByTestId('system-row-github')).toBeVisible();
    await check(page, testInfo, 'systems');

    await page.getByTestId('system-row-github').click();
    await expect(page.getByRole('heading', { level: 1, name: 'GitHub' })).toBeVisible();
    // Triggers poll its issues at the default, which the overview names.
    await expect(page.getByTestId('system-poll-interval')).toHaveText('Every minute');
    await expect(page.getByTestId('system-app')).toHaveAttribute('data-has-app', 'false');
    await expect(page.getByTestId('app-callback')).toContainText('/api/oauth/callback');
    await expect(page.getByTestId('health-app')).toContainText('Sign-in app: not set up');
    await check(page, testInfo, 'system-github');

    await openTab(page, 'Repositories');
    await expect(
      page.getByTestId('system-repositories').getByText('acme/shop', { exact: true }),
    ).toBeVisible();
    // A tracker's tools are Tinfirm's own: each with its effect, nothing to confirm.
    await openTab(page, 'Tools');
    const tools = page.getByTestId('system-tools');
    await expect(tools.locator('[data-effect]').first()).toBeVisible();
    await expect(tools.locator('[data-effect="UNKNOWN"]')).toHaveCount(0);
    await expect(tools.getByTestId('tools-to-confirm')).toHaveCount(0);
    await expect(tools.getByTestId('tools-handle')).toHaveText('github_…');
    await check(page, testInfo, 'system-github-tools');

    // A repository of its own, added and removed.
    await openTab(page, 'Repositories');
    const name = `acme/w2a-${unique(testInfo)}`;
    await page.getByTestId('add-repository').click();
    const add = page.getByRole('dialog', { name: 'Add a repository' });
    await add.getByLabel('URL').fill(`https://127.0.0.1/${name}`);
    await add.getByRole('button', { name: 'Add' }).click();
    await expect(add).toBeHidden();
    const row = page.getByTestId(`repository-${name}`);
    await expect(row).toBeVisible();
    await row.getByRole('button', { name: `Remove ${name}` }).click();
    await page.getByRole('alertdialog').getByRole('button', { name: 'Remove repository' }).click();
    await expect(row).toBeHidden();
  });

  test('an admin registers a system: the kind first, then its fields, its test and its app', async ({
    page,
    appPath,
  }, testInfo) => {
    const name = `Support Jira ${unique(testInfo)}`;
    await page.goto(appPath('/systems'));
    await register(page, 'Jira Data Center', {
      name,
      baseUrl: `${urls.stubs}/jira`,
      pollIntervalSeconds: '300',
    });
    await expect(page.getByTestId('system-status')).toHaveText('Not tested yet');
    await expect(page.getByTestId('system-untested')).toBeVisible();
    await expect(page.getByTestId('system-poll-interval')).toHaveText('Every 5 minutes');

    // Its poll interval changed: out of range is said on the field, in range is saved.
    await systemAction(page, 'edit-system');
    const change = page.getByRole('dialog', { name: `Change ${name}` });
    const poll = change.getByLabel('Check for changes every');
    await expect(poll).toHaveValue('300');
    await poll.fill('10');
    await change.getByRole('button', { name: 'Save' }).click();
    await expect(change).toContainText('A whole number of seconds from 30 to 3600 (an hour).');
    await poll.fill('90');
    await check(page, testInfo, 'system-poll-interval', { dialog: true });
    await change.getByRole('button', { name: 'Save' }).click();
    await expect(change).toBeHidden();
    await expect(page.getByTestId('system-poll-interval')).toHaveText('Every 90 seconds');
    // Before any app, the server names the callback URL to register at Jira: its public URL's.
    await expect(page.getByTestId('app-callback')).toHaveText(`${urls.app}/api/oauth/callback`);

    // Adam holds no account on it, so the test reaches Jira without one.
    await systemAction(page, 'test-system');
    await expect(page.getByTestId('test-result')).toHaveAttribute('data-ok', 'true');
    await expect(page.getByTestId('test-result')).toContainText('without an account');
    await expect(page.getByTestId('system-status')).toHaveText('Ready');

    await page.getByTestId('set-app').click();
    const app = page.getByRole('dialog', { name: 'Set up the app' });
    await app.getByLabel('Client ID').fill('w2a-client');
    await app.getByLabel('Client secret').fill('w2a-secret-fixture');
    await app.getByRole('button', { name: 'Save the app' }).click();
    await expect(app).toBeHidden();
    await expect(page.getByTestId('app-client-id')).toContainText('w2a-client');
    await expect(page.getByTestId('app-client-secret')).toContainText('Stored');
    await check(page, testInfo, 'system-registered');

    await removeSystem(page, name);
  });

  test('an admin confirms an MCP tool’s effect', async ({ page, appPath }, testInfo) => {
    const id = unique(testInfo);
    const name = `Notes ${id}`;
    await page.goto(appPath('/systems'));
    await register(page, 'MCP server', { name, baseUrl: `${urls.stubs}/mcp/${id}` });

    await openTab(page, 'Tools');
    const tools = page.getByTestId('system-tools');
    await expect(tools.getByTestId('tools-empty')).toBeVisible();
    await tools.getByTestId('refresh-tools').click();
    await expect(tools.getByTestId('refresh-result')).toContainText('3 tools added');

    // Every new tool counts as a write until confirmed: first, orange, for the admin who must act.
    await expect(tools.getByTestId('tools-to-confirm')).toHaveText('3 to confirm');
    await expect(page.getByRole('tab', { name: 'Tools' })).toContainText('3 to confirm');
    const search = tools.getByTestId('tool-search_notes');
    await expect(search).toHaveAttribute('data-effect', 'UNKNOWN');
    await expect(search.getByTestId('tool-effect')).toHaveText('Counts as a write until you say');
    await expect(search.getByTestId('tool-effect')).toHaveClass(/text-human/);
    await expect(search.getByTestId('tool-suggestion')).toHaveText('Its hints say it only reads.');
    await expect(
      tools.getByTestId('tool-summarise_note').getByTestId('tool-suggestion'),
    ).toHaveText('It sends no hints.');
    await check(page, testInfo, 'mcp-unconfirmed');

    await search.getByTestId('confirm-effect').click();
    await search.getByTestId('confirm-READ').click();
    await expect(search).toHaveAttribute('data-effect', 'READ');
    await expect(search.getByTestId('tool-effect')).toHaveText('Reads');
    await expect(search.getByTestId('tool-effect')).not.toHaveClass(/text-human/);
    await expect(tools.getByTestId('tools-to-confirm')).toHaveText('2 to confirm');

    const create = tools.getByTestId('tool-create_note');
    await create.getByTestId('confirm-effect').click();
    await create.getByTestId('confirm-WRITE').click();
    await expect(create).toHaveAttribute('data-effect', 'WRITE');
    const summary = tools.getByTestId('tool-summarise_note');
    await summary.getByTestId('confirm-effect').click();
    await summary.getByTestId('confirm-READ').click();
    await expect(tools.getByTestId('tools-all-confirmed')).toBeVisible();
    await expect(tools.getByTestId('tools-to-confirm')).toHaveCount(0);

    // A tool switched off is offered to no agent.
    const summarise = tools.getByTestId('tool-summarise_note');
    await summarise.getByRole('switch').click();
    await expect(summarise).toHaveAttribute('data-enabled', 'false');

    // A refresh that finds the same catalogue keeps the confirmations.
    await tools.getByTestId('refresh-tools').click();
    await expect(tools.getByTestId('refresh-result')).toContainText('Nothing was added or removed');
    await expect(search).toHaveAttribute('data-effect', 'READ');
    await expect(page.locator('main .text-human')).toHaveCount(0);
    await check(page, testInfo, 'mcp-confirmed');

    await removeSystem(page, name);
  });

  test('svc-dev: the credentials it borrows, and each one on its system, lent to Analysts read-only', async ({
    page,
    appPath,
    seed,
  }, testInfo) => {
    await page.goto(appPath('/service-identities'));
    await expect(page.getByRole('heading', { level: 1, name: 'Service accounts' })).toBeVisible();
    const row = page.getByTestId('identity-row-svc-dev');
    await expect(row).toContainText('Used by Analysts');
    await expect(row.getByTestId('identity-status')).toHaveText('Active');
    await check(page, testInfo, 'service-identities');

    await row.click();
    await expect(page.getByRole('heading', { level: 1, name: 'svc-dev' })).toBeVisible();
    for (const key of ['jira', 'github'] as const) {
      const borrowed = page.locator(
        `[data-testid=borrowed-credential][data-system="${seed.systems[key].handle}"]`,
      );
      await expect(borrowed.getByTestId('borrowed-name')).toHaveText('svc-dev');
      await expect(borrowed.getByTestId('borrowed-state')).toHaveText('Working');
      await expect(borrowed.getByTestId('borrowed-access')).toContainText('Read and write');
      await expect(borrowed.getByTestId('borrowed-since')).toContainText('Lent since');
    }
    await expect(page.getByTestId('identity-users-list')).toHaveText('Analysts');
    await expect(page.getByTestId('identity-budget-line')).toContainText('$50.00 per 30d');
    await check(page, testInfo, 'service-identity-svc-dev');

    // Lent on the system's page: the credential, its grant to Analysts and svc-dev that borrows it.
    await page.goto(appPath(`/systems/${seed.systems.jira.id}`));
    const credential = page.locator('[data-testid=credential]', { hasText: 'svc-dev' });
    await expect(
      credential
        .locator('[data-testid=credential-grant][data-group=Analysts]')
        .getByRole('radio', { name: 'Read-only' }),
    ).toBeChecked();
    await expect(
      credential.locator('[data-testid=credential-lending][data-service-account=svc-dev]'),
    ).toContainText('Lent since');
    // The members who hold their own account there, counted (ADR-0072, decision 11).
    await expect(page.getByTestId('credentials-member-count')).toContainText(
      `own account on ${seed.systems.jira.name}`,
    );
    await check(page, testInfo, 'system-credentials');
  });

  test('an admin adds a credential on its system, lends it, and suspends a service account @serial', async ({
    page,
    appPath,
    api,
    apiPath,
    stubs,
    seed,
  }, testInfo) => {
    const id = unique(testInfo);
    const name = `svc-w2a-${id}`;
    const token = `jira-token-${name}`;
    const admin = await api('admin');
    const dave = await api('dave');
    // A group of its own, with Dave, who holds no Jira account: what the grant lends shows in his accounts.
    const group = await admin.post(apiPath('/groups'), {
      name: `W2a ${id}`,
      description: 'A group of the W2a spec',
      memberIds: [seed.people.dave.id],
    });
    await stubs.addIdentity('jira', token, name);
    const daveJira = async () =>
      (
        (await dave.get(apiPath('/me/accounts'))) as {
          items: { system: { id: string }; binding: string }[];
        }
      ).items.find((item) => item.system.id === seed.systems.jira.id)?.binding;
    let identityId: string | undefined;
    let credentialId: string | undefined;
    try {
      expect(await daveJira()).toBe('CONNECT');

      await page.goto(appPath('/service-identities'));
      await page.getByTestId('new-identity').click();
      const create = page.getByRole('dialog', { name: 'New service account' });
      await create.getByLabel('Name').fill(name);
      await create.getByLabel('Description').fill('Reads Jira for the W2a spec');
      await create.getByRole('button', { name: 'Create' }).click();
      await expect(page.getByRole('heading', { level: 1, name })).toBeVisible();
      identityId = page.url().split('/').pop();
      await expect(page.getByTestId('identity-users-list')).toHaveText('Only the Owner');
      await expect(page.getByTestId('borrowed-empty')).toBeVisible();

      // On Jira's page: a credential by token; the stub knows the token as the company's own user.
      await page.goto(appPath(`/systems/${seed.systems.jira.id}`));
      await page.getByTestId('add-credential').click();
      const add = page.getByRole('dialog', {
        name: `Add a credential to ${seed.systems.jira.name}`,
      });
      await add.getByLabel('Name').fill(name);
      await add.getByLabel('Token').fill('jira-token-nobody-knows');
      await add.getByTestId('credential-save').click();
      await expect(add.getByTestId('credential-error')).toHaveText(
        'Jira answered 401 when asked whose the token is.',
      );
      await add.getByLabel('Token').fill(token);
      await check(page, testInfo, 'add-credential', { dialog: true });
      await add.getByTestId('credential-save').click();
      await expect(add).toBeHidden();
      const credential = page.locator('[data-testid=credential]', { hasText: name });
      await expect(credential.getByTestId('credential-state')).toHaveText('Working');
      await expect(credential.getByTestId('credential-login')).toHaveText(name);
      credentialId = (await credential.getAttribute('data-credential')) ?? undefined;

      // Lend it to the group, read-only.
      await credential.getByTestId('lend-group').click();
      const lend = page.getByRole('dialog', { name: `Lend ${name} to a group` });
      await lend.getByLabel('Group').selectOption({ label: `W2a ${id}` });
      await check(page, testInfo, 'lend-group', { dialog: true });
      await lend.getByRole('button', { name: 'Lend it' }).click();
      await expect(lend).toBeHidden();
      const grant = credential.locator(`[data-testid=credential-grant][data-group="W2a ${id}"]`);
      await expect(grant.getByRole('radio', { name: 'Read-only' })).toBeChecked();
      await expect.poll(daveJira).toBe('GRANTED');

      // And to the new service account, with writes.
      await credential.getByTestId('lend-service-account').click();
      const lendTo = page.getByRole('dialog', { name: `Lend ${name} to a service account` });
      await lendTo.getByLabel('Service account').selectOption({ label: name });
      await lendTo.getByRole('button', { name: 'Lend it' }).click();
      await expect(lendTo).toBeHidden();
      await expect(
        credential.locator(`[data-testid=credential-lending][data-service-account="${name}"]`),
      ).toBeVisible();
      await check(page, testInfo, 'credential-lent');

      // Its access changes in place.
      await grant.getByText('Read and write', { exact: true }).click();
      await expect(grant.getByRole('radio', { name: 'Read and write' })).toBeChecked();

      // Suspend the service account: what acts through it stops; the group's grant goes on.
      await page.goto(appPath(`/service-identities/${identityId}`));
      await expect(
        page.locator(
          `[data-testid=borrowed-credential][data-system="${seed.systems.jira.handle}"]`,
        ),
      ).toContainText(name);
      await page.getByTestId('suspend-identity').click();
      const suspend = page.getByRole('alertdialog', { name: `Suspend ${name}?` });
      await expect(suspend).toContainText('Everything that acts through it or on its budget stops');
      await suspend.getByLabel('Reason').fill('The W2a spec checks the kill switch');
      await check(page, testInfo, 'suspend', { dialog: true });
      await suspend.getByRole('button', { name: 'Suspend' }).click();
      await expect(suspend).toBeHidden();
      await expect(page.getByTestId('identity-status')).toHaveText('Suspended');
      await expect(page.getByTestId('identity-suspended')).toBeVisible();
      await expect(page.getByTestId('identity-suspended-at')).toContainText(/Suspended \S/);
      await expect(page.getByTestId('identity-suspension-reason')).toHaveText(
        'Reason: The W2a spec checks the kill switch',
      );
      await expect.poll(daveJira).toBe('GRANTED');
      await check(page, testInfo, 'service-identity-suspended');
      await page.getByTestId('resume-identity').click();
      await expect(page.getByTestId('identity-status')).toHaveText('Active');

      // Removed with its grants and lendings: Dave has nothing to act with on Jira again.
      await page.goto(appPath(`/systems/${seed.systems.jira.id}`));
      await credential.getByTestId('credential-menu').click();
      await page.getByTestId('credential-remove').click();
      const remove = page.getByRole('alertdialog', { name: `Remove ${name}?` });
      await expect(remove).toContainText(`W2a ${id} no longer acts with it`);
      // The new service account has no trigger: nothing pauses.
      await expect(remove).toContainText(`${name} no longer borrows it; no trigger acts with it.`);
      await remove.getByRole('button', { name: `Remove ${name}` }).click();
      await expect(remove).toBeHidden();
      await expect(credential).toHaveCount(0);
      credentialId = undefined;
      await expect.poll(daveJira).toBe('CONNECT');

      // The service account is deleted; it borrowed nothing any more.
      await page.goto(appPath(`/service-identities/${identityId}`));
      await page.getByTestId('delete-identity').click();
      await page
        .getByRole('alertdialog', { name: `Delete ${name}?` })
        .getByRole('button', { name: `Delete ${name}` })
        .click();
      await expect(page.getByRole('heading', { level: 1, name: 'Service accounts' })).toBeVisible();
      await expect(page.getByTestId(`identity-row-${name}`)).toHaveCount(0);
      identityId = undefined;
    } finally {
      if (credentialId) {
        await admin
          .delete(apiPath(`/systems/${seed.systems.jira.id}/credentials/${credentialId}`))
          .catch(() => undefined);
      }
      if (identityId) {
        await admin.delete(apiPath(`/service-identities/${identityId}`)).catch(() => undefined);
      }
      await admin.delete(apiPath(`/groups/${group.id}`)).catch(() => undefined);
    }
  });
});
