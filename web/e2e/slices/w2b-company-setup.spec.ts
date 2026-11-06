import type { Browser, Locator, Page, TestInfo } from '@playwright/test';
import { Api } from '../harness/api.ts';
import { root, spareCompany, urls } from '../harness/config.ts';
import { ScriptedRunner } from '../harness/scripted-runner.ts';
import {
  expect,
  expectNoAxeViolations,
  expectNoHorizontalOverflow,
  shoot,
  test,
} from '../fixtures';

/** W2b's review screenshots go to docs/screenshots/web/w2b/. */
const SLICE = 'w2b';

/** A suffix that keeps the names a test makes apart between the two projects and between runs. */
function unique(testInfo: TestInfo): string {
  return `${testInfo.project.name}-${Date.now().toString(36)}`;
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
    // A dialog covers the viewport only: its shot is of the viewport, not of the whole page under it.
    await shoot(page, testInfo, SLICE, shot, { fullPage: !options.dialog });
  }
}

async function openPage(page: Page, path: string, heading: string): Promise<void> {
  await page.goto(path);
  await expect(page.getByRole('heading', { level: 1, name: heading })).toBeVisible();
}

/** The fake LiteLLM down (every admin call answers 503) or up again. */
async function setLiteLlmDown(down: boolean): Promise<void> {
  const response = await fetch(`${urls.control}/litellm/down`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ down }),
  });
  expect(response.ok).toBe(true);
}

/** The root, the instance admin, as an API client and as a page with the project's viewport. */
async function rootSession(
  browser: Browser,
  page: Page,
  testInfo: TestInfo,
): Promise<{ api: Api; page: Page }> {
  const api = await Api.login(urls.server, root.email, root.password);
  const state = testInfo.outputPath('root-state.json');
  await api.saveStorageState(state);
  const context = await browser.newContext({
    storageState: state,
    viewport: page.viewportSize(),
    isMobile: testInfo.project.use.isMobile,
    hasTouch: testInfo.project.use.hasTouch,
    deviceScaleFactor: testInfo.project.use.deviceScaleFactor,
    baseURL: testInfo.project.use.baseURL,
  });
  return { api, page: await context.newPage() };
}

/** A runner host's card on Runner hosts, by the host's name. */
function hostCard(page: Page, name: string): Locator {
  return page.locator(`li[data-host="${name}"]`);
}

/**
 * W2b, the company's setup (the plan §5, §7 and §8): model access, runner hosts, members and groups, settings and
 * the audit log, as the admin (persona 'admin') unless a test says otherwise. The phone project runs it too. Every
 * test puts back what it changed (the gateway, a budget, the switch, its own hosts, people and groups), so that both
 * projects and the later slices find the seed.
 */
test.describe('W2b: company setup', { tag: ['@app', '@phone'] }, () => {
  test.use({ persona: 'admin' });

  test('model access: the bundled gateway is configured and tested @serial', async ({
    page,
    appPath,
    api,
    apiPath,
  }, testInfo) => {
    await openPage(page, appPath('/model-access'), 'Model access');
    const sections = page.getByTestId('model-access-sections');
    const status = page.getByTestId('gateway-status');
    await expect(status).toHaveAttribute('data-state', 'READY');
    await expect(status).toContainText('turns paid by a budget may start');
    await expect(page.getByRole('radio', { name: 'Bundled' })).toBeChecked();
    // Nothing waits on this admin, so nothing is orange.
    await expect(page.locator('main .text-human')).toHaveCount(0);

    await sections.locator('[data-section="defaults"]').click();
    await expect(page.locator('#default-budget')).toHaveValue('50');
    await expect(page.locator('#budget-period-count')).toHaveValue('30');
    await sections.locator('[data-section="budgets"]').click();
    await expect(page.getByTestId('budgets')).toContainText('$50.00 per 30 days');
    const svcDev = page.getByTestId('identity-budget-svc-dev');
    await expect(svcDev.getByTestId('budget')).toHaveText('The default');

    await sections.locator('[data-section="gateway"]').click();
    await page.getByTestId('test-gateway').click();
    await expect(page.getByTestId('test-result')).toContainText(
      'LiteLLM answered and serves 3 models',
    );
    await expect(page.getByTestId('served')).toContainText('claude-sonnet-4-5');
    await check(page, testInfo, 'model-access');

    // With LiteLLM down the test fails in words, and the relay is off until a test passes.
    await setLiteLlmDown(true);
    try {
      await page.getByTestId('test-gateway').click();
      await expect(page.getByTestId('test-result')).toContainText(
        'Tinfirm could not reach the model gateway.',
      );
      await expect(status).toHaveAttribute('data-state', 'FAILED');
      await expect(status).toContainText('Nothing is paid by a budget until a test passes.');
      await check(page, testInfo, 'model-access-test-failed');
    } finally {
      await setLiteLlmDown(false);
      const admin = await api('admin');
      await admin.post(apiPath('/model-settings/test'));
      expect((await admin.get(apiPath('/model-settings'))).relayEnabled).toBe(true);
    }
    await page.getByTestId('test-gateway').click();
    await expect(status).toHaveAttribute('data-state', 'READY');
  });

  test('model access: a service account’s budget, its own and back to the default @serial', async ({
    page,
    appPath,
    api,
    apiPath,
    seed,
  }, testInfo) => {
    const admin = await api('admin');
    const access = apiPath(`/principals/${seed.serviceIdentity.id}/model-access`);
    try {
      await openPage(page, appPath('/model-access?tab=budgets'), 'Model access');
      const svcDev = page.getByTestId('identity-budget-svc-dev');
      await svcDev.getByRole('button', { name: 'Change budget of svc-dev' }).click();
      const dialog = page.getByRole('dialog', { name: 'Budget of svc-dev' });
      await dialog.getByLabel('Its own').check();
      await dialog.getByLabel('USD per 30 days').fill('20');
      await dialog.getByLabel('Only these').check();
      await dialog.getByLabel('claude-haiku-4-5').check();
      await check(page, testInfo, 'model-access-budget', { dialog: true });
      await dialog.getByRole('button', { name: 'Save' }).click();
      await expect(dialog).toBeHidden();
      await expect(svcDev.getByTestId('budget')).toHaveText('Own budget');
      await expect(svcDev.getByTestId('models')).toHaveText('claude-haiku-4-5 only');
      const own = await admin.get(access);
      expect(own).toMatchObject({
        budgetUsd: 20,
        budgetOverridden: true,
        models: ['claude-haiku-4-5'],
      });

      await svcDev.getByRole('button', { name: 'Change budget of svc-dev' }).click();
      await dialog.locator('#budget-default').check();
      await dialog.locator('#models-default').check();
      await dialog.getByRole('button', { name: 'Save' }).click();
      await expect(dialog).toBeHidden();
      await expect(svcDev.getByTestId('budget')).toHaveText('The default');
    } finally {
      const now = await admin.get(access);
      if (now.budgetOverridden || now.modelsOverridden) {
        await admin.patch(access, { clearBudget: true, clearModels: true });
      }
    }
    expect(await admin.get(access)).toMatchObject({
      budgetOverridden: false,
      modelsOverridden: false,
    });
  });

  test('model access: until the gateway is set up, the page says every new conversation is refused', async ({
    browser,
    page,
  }, testInfo) => {
    // The spare company the stack declares, which the root owns and nothing sets up (ADR-0075):
    // Acme's gateway stays as the seed set it.
    const { api: rootApi, page: rootPage } = await rootSession(browser, page, testInfo);
    try {
      const company = (
        await rootApi.all<{ id: string; key?: string | null }>('/api/companies')
      ).find((entry) => entry.key === spareCompany.key);
      if (!company) throw new Error(`The stack declares no company ${spareCompany.key}`);
      await openPage(rootPage, `/c/${company.id}/model-access`, 'Model access');
      const status = rootPage.getByTestId('gateway-status');
      await expect(status).toHaveAttribute('data-state', 'NOT_CONFIGURED');
      await expect(status).toContainText('Model access is not set up');
      await expect(status).toContainText('every new conversation is refused');
      await expect(rootPage.getByTestId('gateway-steps').locator('li')).toHaveCount(3);
      await expect(rootPage.getByTestId('test-gateway')).toBeDisabled();
      await expect(rootPage.getByTestId('save-model-settings')).toHaveText('Set up model access');
      await expect(rootPage.getByTestId('models-note')).toHaveText(
        'The gateway lists its models once it passes its test.',
      );
      await check(rootPage, testInfo, 'model-access-not-set-up');
    } finally {
      await rootPage.context().close();
      await rootApi.dispose();
    }
  });

  test('runner hosts: the seeded host online, one too old for budget runs, one never connected @serial', async ({
    page,
    appPath,
    api,
    apiPath,
    seed,
  }, testInfo) => {
    const admin = await api('admin');
    const suffix = unique(testInfo);
    const oldName = `old-${suffix}`;
    const spareName = `spare-${suffix}`;
    const oldRunner = new ScriptedRunner(urls.server, '0.1.4');
    const made: string[] = [];
    try {
      await openPage(page, appPath('/runner-hosts'), 'Runner hosts');
      const seeded = hostCard(page, seed.runnerHost.name);
      await expect(seeded.getByTestId('host-state')).toHaveText('Online');
      await expect(seeded).toContainText('v0.2.0');
      await expect(seeded.getByTestId('host-too-old')).toHaveCount(0);
      await expect(page.getByTestId('none-takes-turns')).toHaveCount(0);

      // Register a company host: its token is shown once, with the commands of a company host.
      await page.getByTestId('open-register-host').click();
      const dialog = page.getByRole('dialog', { name: 'Register a company runner host' });
      await dialog.getByLabel('Name').fill(oldName);
      await dialog.getByTestId('register-runner-host').click();
      const setUp = page.getByRole('dialog', { name: `Set up ${oldName}` });
      const token = (await setUp.getByTestId('runner-token').textContent())!.trim();
      expect(token).toMatch(/^tfr_/);
      await expect(setUp.getByTestId('token-once')).toContainText('Copy the token now');
      await expect(setUp.locator('ol')).not.toContainText('credential add');
      await check(page, testInfo, 'runner-host-token', { dialog: true });
      await setUp.getByTestId('runner-host-done').click();
      await expect(setUp).toBeHidden();
      const old = hostCard(page, oldName);
      await expect(old.getByTestId('host-state')).toHaveText('Offline · never connected');
      made.push(oldName);

      // An old runner connects with it: online, and too old for turns paid by a budget.
      await oldRunner.connect(token);
      await page.reload();
      await expect(old.getByTestId('host-state')).toHaveText('Online');
      await expect(old.getByTestId('host-too-old')).toHaveText(
        'Runner 0.1.4 is too old for turns paid by a budget: upgrade it to 0.2.0 or later. Until then it runs nothing for the company.',
      );

      // A host that never connected keeps its number of runs until it does.
      const spare = await admin.post(apiPath('/runner-hosts'), {
        name: spareName,
        scope: 'COMPANY',
      });
      made.push(spareName);
      await page.reload();
      const spareCard = hostCard(page, spareName);
      await spareCard.getByLabel(`Runs at a time on ${spareName}`).selectOption('3');
      await expect(spareCard.getByTestId('host-pending')).toHaveText(
        `${spareName} applies the change when it connects.`,
      );
      const hosts = await admin.all(apiPath('/runner-hosts'));
      expect(hosts.find((h: { id: string }) => h.id === spare.runnerHost.id)).toMatchObject({
        desiredMaxConcurrentRuns: 3,
        configPending: true,
      });
      await check(page, testInfo, 'runner-hosts');

      // Remove both through the page.
      oldRunner.disconnect();
      for (const name of [spareName, oldName]) {
        await hostCard(page, name).getByTestId('host-remove').click();
        const confirm = page.getByRole('alertdialog', { name: `Remove ${name}?` });
        await confirm.getByTestId('confirm-remove-host').click();
        await expect(confirm).toBeHidden();
        await expect(hostCard(page, name)).toHaveCount(0);
        made.splice(made.indexOf(name), 1);
      }
    } finally {
      oldRunner.disconnect();
      for (const host of await admin.all<{ id: string; name: string }>(apiPath('/runner-hosts'))) {
        if (made.includes(host.name)) await admin.delete(apiPath(`/runner-hosts/${host.id}`));
      }
    }
  });

  test('runner hosts: a host that runs a turn cannot be removed @serial', async ({
    page,
    appPath,
    api,
    apiPath,
    seed,
    startConversation,
    runner,
  }, testInfo) => {
    const { run } = await startConversation('alice', `Hold a turn [e2e:hold] ${unique(testInfo)}`);
    try {
      const admin = await api('admin');
      const seeded = (await admin.all(apiPath('/runner-hosts'))).find(
        (h: { id: string }) => h.id === seed.runnerHost.id,
      );
      // Asserted before the click: a host that runs nothing would be removed for good.
      expect(seeded.activeRuns).toBeGreaterThan(0);

      await openPage(page, appPath('/runner-hosts'), 'Runner hosts');
      const card = hostCard(page, seed.runnerHost.name);
      await card.getByTestId('host-remove').click();
      const confirm = page.getByRole('alertdialog', { name: `Remove ${seed.runnerHost.name}?` });
      await confirm.getByTestId('confirm-remove-host').click();
      await expect(confirm.getByTestId('remove-host-error')).toHaveText(
        'Runs are queued for or running on this runner host. Cancel them or wait for them to finish, then try again.',
      );
      await check(page, testInfo, 'runner-host-busy', { dialog: true });
      await confirm.getByRole('button', { name: 'Cancel' }).click();
      await expect(card.getByTestId('host-state')).toHaveText('Online');
    } finally {
      await runner.finish(run.runId, { summary: 'Done.' });
    }
  });

  test('members and groups: four roles, the only owner, accounts, a group’s members', async ({
    page,
    pageAs,
    appPath,
    api,
    apiPath,
    seed,
  }, testInfo) => {
    const suffix = unique(testInfo);
    const rootApi = await Api.login(urls.server, root.email, root.password);
    const admin = await api('admin');
    const email = `erin-${suffix}@acme.test`;
    let erinId: string | null = null;
    const groupName = `QA ${suffix}`;
    try {
      await openPage(page, appPath('/members'), 'Members');
      for (const person of Object.values(seed.people)) {
        await expect(page.getByTestId(`member-${person.email}`)).toBeVisible();
      }
      // A calm row: no role select and no icon buttons; the whole row opens the member.
      const olivia = page.getByTestId(`member-${seed.people.owner.email}`);
      await expect(olivia.getByRole('combobox')).toHaveCount(0);
      await expect(olivia.getByRole('button')).toHaveCount(1);
      await olivia.getByRole('button').click();
      const oliviaDialog = page.getByTestId('member-dialog');
      await expect(oliviaDialog.getByTestId('only-owner')).toHaveText(
        'The only owner: make someone else an owner first.',
      );
      await expect(oliviaDialog.getByTestId('role-cards')).toContainText('Owner');
      await expect(oliviaDialog.getByTestId('role-cards')).toContainText('Admin');
      await expect(oliviaDialog.getByTestId('role-cards')).toContainText('Member');
      await expect(oliviaDialog.getByTestId('role-cards')).toContainText('Viewer');
      await expect(oliviaDialog.getByRole('radio')).toHaveCount(4);
      for (const radio of await oliviaDialog.getByRole('radio').all()) {
        await expect(radio).toBeDisabled();
      }
      await oliviaDialog.getByRole('button', { name: 'Close' }).click();
      await expect(oliviaDialog).toBeHidden();

      // A member's accounts: which system and its status, never more.
      const alice = page.getByTestId(`member-${seed.people.alice.email}`);
      await expect(alice.getByTestId('accounts-summary')).toContainText('GitHub connected');
      await alice.getByRole('button').click();
      const aliceDialog = page.getByTestId('member-dialog');
      const aliceGithub = aliceDialog.getByTestId('member-account-GitHub');
      await expect(aliceGithub).toContainText('Connected');
      await expect(aliceGithub).not.toContainText('alice@');
      await aliceDialog.getByRole('button', { name: 'Close' }).click();
      await expect(aliceDialog).toBeHidden();
      const carol = page.getByTestId(`member-${seed.people.carol.email}`);
      await expect(carol.getByTestId('accounts-summary')).toHaveText('No account yet');
      await check(page, testInfo, 'members');

      // The owner reads the same guard, and the server agrees: the only owner keeps the role.
      const owner = await pageAs('owner');
      await openPage(owner, appPath('/members'), 'Members');
      await owner.getByTestId(`member-${seed.people.owner.email}`).getByRole('button').click();
      await expect(owner.getByTestId('member-dialog').getByTestId('only-owner')).toBeVisible();
      const refused = await (
        await api('owner')
      ).raw('PATCH', apiPath(`/members/${seed.people.owner.id}`), { role: 'ADMIN' });
      expect(refused.status()).toBe(409);
      expect((await refused.json()).code).toBe('LAST_OWNER');

      // Add a member, change the role, remove them.
      const erin = await rootApi.post('/api/admin/users', {
        email,
        displayName: `Erin ${suffix}`,
        password: seed.password,
        instanceAdmin: false,
      });
      erinId = erin.id;
      await page.getByRole('button', { name: 'Add member' }).click();
      const add = page.getByRole('dialog', { name: 'Add member' });
      await add.getByLabel('Email').fill(email);
      await add.getByRole('button', { name: 'Add member' }).click();
      await expect(add).toBeHidden();
      const erinRow = page.getByTestId(`member-${email}`);
      await erinRow.getByRole('button').click();
      const erinDialog = page.getByTestId('member-dialog');
      // Picking a role only selects it; Save asks first.
      await erinDialog.getByTestId('role-card-ADMIN').click();
      await expect(erinDialog.getByTestId('role-footer')).toContainText('Role changes to Admin');
      await erinDialog.getByTestId('save-role').click();
      await page
        .getByRole('alertdialog', { name: `Make Erin ${suffix} an admin?` })
        .getByRole('button', { name: 'Make admin' })
        .click();
      await expect
        .poll(
          async () =>
            (await admin.all(apiPath('/members'))).find(
              (m: { userId: string }) => m.userId === erinId,
            )?.role,
        )
        .toBe('ADMIN');
      await erinDialog.getByTestId('remove-member').click();
      await page
        .getByRole('alertdialog', { name: `Remove Erin ${suffix}?` })
        .getByRole('button', { name: 'Remove member' })
        .click();
      // Removing a member opens the checklist of what they left (W7); back to Members.
      await expect(
        page.getByRole('heading', { level: 1, name: `Erin ${suffix} left` }),
      ).toBeVisible();
      await openPage(page, appPath('/members'), 'Members');
      await expect(erinRow).toHaveCount(0);

      // Groups: Analysts with its members and what it may use.
      await page
        .getByTestId('members-tabs')
        .getByRole('link', { name: /Groups/ })
        .click();
      await expect(page).toHaveURL(/tab=groups/);
      const analysts = page.locator('li[data-group="Analysts"]');
      await expect(analysts.getByTestId('group-uses')).toContainText(
        'GitHub and Jira with svc-dev, read-only',
      );
      await check(page, testInfo, 'groups');
      const details = page.getByTestId('group-dialog');
      await page.locator('li[data-group="Everyone"]').getByRole('button').click();
      await expect(details.getByTestId('group-agents')).toContainText(seed.agent.name);
      await details.getByRole('button', { name: 'Close' }).click();
      await expect(details).toBeHidden();
      await analysts.getByRole('button').click();
      await expect(details.getByTestId('group-member-list')).toContainText('Alice Analyst');
      await expect(details.getByTestId('group-member-list')).toContainText('Carol Clerk');
      await expect(details.getByTestId('group-grants')).toContainText(
        'GitHub with svc-dev, read-only',
      );
      await expect(details.getByTestId('group-grants')).toContainText(
        'Jira with svc-dev, read-only',
      );
      await expect(details.getByTestId('group-identities')).toHaveText('svc-dev');
      await details.getByRole('button', { name: 'Close' }).click();
      await expect(details).toBeHidden();

      // A group created with a member, then deleted.
      await page.getByTestId('new-group').click();
      const create = page.getByRole('dialog', { name: 'New group' });
      await create.getByLabel('Name', { exact: true }).fill(groupName);
      await create.getByTestId('add-group-member').click();
      await create.getByRole('button', { name: 'Dave Developer' }).click();
      await create.getByTestId('save-group').click();
      await expect(create).toBeHidden();
      const qa = page.locator(`li[data-group="${groupName}"]`);
      await expect(qa.getByTestId('group-members')).toHaveText('1 member');
      await qa.getByRole('button').click();
      await page.getByTestId('group-dialog').getByTestId('delete-group').click();
      await page
        .getByRole('alertdialog', { name: `Delete ${groupName}?` })
        .getByRole('button', { name: 'Delete group' })
        .click();
      await expect(qa).toHaveCount(0);
    } finally {
      if (erinId) {
        const left = (await admin.all(apiPath('/members'))).some(
          (m: { userId: string }) => m.userId === erinId,
        );
        if (left) await admin.delete(apiPath(`/members/${erinId}`));
      }
      for (const group of await admin.all<{ id: string; name: string }>(apiPath('/groups'))) {
        if (group.name === groupName) await admin.delete(apiPath(`/groups/${group.id}`));
      }
      await rootApi.dispose();
    }
  });

  test('settings: members’ own MCP servers switch off and on, and sign-in shows the providers and SCIM @serial', async ({
    page,
    appPath,
    api,
    apiPath,
  }, testInfo) => {
    const admin = await api('admin');
    try {
      await openPage(page, appPath('/settings'), 'Settings');
      const setting = page.getByTestId('personal-mcp');
      const toggle = setting.getByRole('switch', { name: 'Members’ own MCP servers' });
      await expect(toggle).toHaveAttribute('aria-checked', 'true');
      // Turning it off asks first, and the question says what off does.
      await toggle.click();
      const question = page.getByRole('alertdialog');
      await expect(question).toContainText('The servers members registered stay');
      await question.getByRole('button', { name: 'Turn off' }).click();
      await expect(toggle).toHaveAttribute('aria-checked', 'false');
      await expect(setting.getByTestId('personal-mcp-state')).toHaveText('Off');
      // The switch shows the choice at once; the server has it when the save returns.
      await expect
        .poll(async () => (await admin.get(apiPath('/system-settings'))).personalMcpServers)
        .toBe(false);
      await check(page, testInfo, 'settings');
      await toggle.click();
      await expect(toggle).toHaveAttribute('aria-checked', 'true');

      // How people sign in is the deployment's configuration (ADR-0075): the Sign-in section shows the company's
      // providers read-only (ADR-0086), then SCIM provisioning (ADR-0087), which this deployment does not allow for
      // the company.
      const sections = page.getByRole('navigation', { name: 'Sections of the settings' });
      await sections.getByRole('link', { name: /Sign-in/ }).click();
      await expect(
        page.getByRole('heading', { level: 2, name: 'Identity providers' }),
      ).toBeVisible();
      await expect(
        page.getByRole('heading', { level: 2, name: /SCIM provisioning/ }),
      ).toBeVisible();
      await expect(page.getByTestId('scim-not-enabled')).toContainText('companies[].scim.enabled');
      await check(page, testInfo);
    } finally {
      await admin.put(apiPath('/system-settings'), { personalMcpServers: true });
    }
  });

  test('the audit log: filtered by action, then by an event’s person', async ({
    page,
    appPath,
    seed,
  }, testInfo) => {
    await openPage(page, appPath('/audit?action=runner_host.'), 'Audit log');
    const filters = page.getByTestId('audit-filters');
    await expect(filters.getByTestId('filter-chip')).toHaveText(['Action runner_host.']);
    await expect(filters.getByLabel('Action', { exact: true })).toHaveValue('runner_host.');
    const rows = page.getByTestId('audit-event');
    await expect(rows.first()).toContainText('runner_host.');
    for (const action of await rows.getByTestId('audit-action').allTextContents()) {
      expect(action).toMatch(/^runner_host\./);
    }
    await check(page, testInfo, 'audit-filtered');

    // An event's person as a filter, on top of the action: Adam registered the seeded host (others register hosts of
    // their own, so the newest registration may be someone else's).
    const registered = rows
      .filter({ hasText: 'runner_host.registered' })
      .filter({ hasText: seed.people.admin.displayName })
      .first();
    await registered.getByRole('button').first().click();
    await registered
      .getByTestId('quick-filter')
      .filter({ hasText: `By ${seed.people.admin.displayName}` })
      .click();
    // nosemgrep: javascript.lang.security.audit.detect-non-literal-regexp.detect-non-literal-regexp -- built from the test's own values, never input
    await expect(page).toHaveURL(new RegExp(`actorId=${seed.people.admin.id}`));
    await expect(filters.getByTestId('filter-chip')).toHaveCount(2);
    await expect(filters.getByLabel('Who', { exact: true })).toHaveValue(
      `USER:${seed.people.admin.id}`,
    );
    await expect(rows.first()).toContainText(seed.people.admin.displayName);
    // The server names the target: the seeded host by its name.
    await expect(
      rows.filter({ hasText: 'runner_host.registered' }).filter({ hasText: seed.runnerHost.name }),
    ).not.toHaveCount(0);

    // Everything about a person: what they did, what was done to them, what names them.
    await filters.getByTestId('filter-chip').first().getByRole('button').click();
    await filters.getByTestId('filter-chip').first().getByRole('button').click();
    await expect(filters.getByTestId('filter-chip')).toHaveCount(0);
    await filters.getByTestId('toggle-more-filters').click();
    await filters.getByLabel('About', { exact: true }).selectOption(seed.people.admin.principalId);
    // nosemgrep: javascript.lang.security.audit.detect-non-literal-regexp.detect-non-literal-regexp -- built from the test's own values, never input
    await expect(page).toHaveURL(new RegExp(`principalId=${seed.people.admin.principalId}`));
    await expect(filters.getByTestId('filter-chip')).toHaveText([
      `About ${seed.people.admin.displayName}`,
    ]);
    await expect(rows.filter({ hasText: 'member.added' }).first()).toContainText(
      seed.people.admin.displayName,
    );

    // A filter that matches nothing says so, and clears.
    await filters.getByLabel('Action', { exact: true }).fill('nothing.at.all');
    await filters.getByLabel('Action', { exact: true }).press('Enter');
    await expect(page.getByTestId('audit-empty')).toContainText('No event matches');
    await page
      .getByTestId('audit-empty')
      .getByRole('button', { name: 'Clear the filters' })
      .click();
    await expect(filters.getByTestId('filter-chip')).toHaveCount(0);
    await expect(rows.first()).toBeVisible();
  });
});
