import type { Page, TestInfo } from '@playwright/test';
import type { Api } from '../harness/api';
import { urls } from '../harness/config';
import { waitFor } from '../harness/http-util';
import { deliverSlackEvent, slackDirectMessage, slackWorkspace } from '../harness/slack-stub';
import { deliverWebhook } from '../harness/webhook-sender';
import {
  expect,
  expectNoAxeViolations,
  expectNoHorizontalOverflow,
  shoot,
  test,
} from '../fixtures';

/**
 * W6, chats and the webhook (package 5's UI; the plan §5 and §7's W6 row): an admin registers a Slack system, stores
 * its signing secret, sets its bot (which only the bot's own workspace may replace) and sees its channels; a member
 * links their Slack user through the sign-in link the bot sends, sees the link on Accounts and picks the agent of
 * their direct messages, and an admin removes the link on Members; an admin registers a webhook, maps it against a
 * sample, sees a delivery and rotates its secret. Slack is the harness's stub (slack-stub.ts): a bot token
 * `xoxb-e2e-<TAG>-…` is the bot of the workspace `T<TAG>`. Every test makes its own systems, workspace and service
 * identity, and removes them.
 */

const SLICE = 'w6';
const SIGNING_SECRET = 'e2e-signing-secret-0123456789abcdef'; // gitleaks:allow (test fixture)

/** A tag that keeps the names and workspaces of a test apart between the two projects and between runs. */
function unique(testInfo: TestInfo): string {
  return `${testInfo.project.name === 'phone' ? 'P' : 'D'}${Date.now().toString(36).slice(-5).toUpperCase()}`;
}

/** Axe with no serious or critical finding, no sideways scroll, and a review shot once the toasts are gone. */
async function check(page: Page, testInfo: TestInfo, shot?: string, dialog = false): Promise<void> {
  await expectNoAxeViolations(page, testInfo);
  await expectNoHorizontalOverflow(page);
  if (shot) {
    if (process.env['E2E_SCREENSHOTS']) {
      await expect(page.locator('[data-sonner-toast]')).toHaveCount(0, { timeout: 15_000 });
    }
    await shoot(page, testInfo, SLICE, shot, { fullPage: !dialog });
  }
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

async function newServiceIdentity(admin: Api, path: (s: string) => string, name: string) {
  return admin.post(path('/service-identities'), { name, description: 'Holds the Slack bot' });
}

test.describe('W6: chats', { tag: ['@app', '@phone'] }, () => {
  test('an admin registers a Slack system, stores its secret and sets its bot', async ({
    adminPage: page,
    api,
    apiPath,
    appPath,
  }, testInfo) => {
    const tag = unique(testInfo);
    const workspace = slackWorkspace(tag);
    const other = slackWorkspace(`${tag}X`);
    const name = `Slack ${tag}`;
    const admin = await api('admin');
    const identity = await newServiceIdentity(admin, apiPath, `svc-slack-${tag.toLowerCase()}`);
    let systemId: string | undefined;
    try {
      await page.goto(appPath('/systems'));
      await page.getByTestId('register-system').click();
      const dialog = page.getByRole('dialog', { name: 'Register a system' });
      await dialog.getByRole('radio', { name: /^Slack/ }).check();
      await dialog.getByTestId('system-next').click();
      await expect(dialog.getByLabel('Handle')).toHaveCount(0);
      await dialog.getByLabel('Name').fill(name);
      await dialog.getByTestId('system-next').click();
      await expect(dialog.getByRole('radio', { name: /^Events API/ })).toBeChecked();
      await check(page, testInfo, 'register-slack', true);
      await dialog.getByRole('button', { name: 'Register' }).click();
      await expect(dialog).toBeHidden();
      await expect(page.getByRole('heading', { level: 1, name })).toBeVisible();
      systemId = page.url().split('/').pop();

      // A new system: no bot, the URLs to set at Slack, and the signing secret to store first.
      await expect(page.getByTestId('health-bot')).toContainText('Bot: not set');
      await expect(page.getByTestId('chat-url-0')).toHaveText(
        `${urls.app}/api/chat/slack/${systemId}/events`,
      );
      await expect(page.getByTestId('secret-signingSecret')).toHaveAttribute('data-set', 'false');
      await check(page, testInfo, 'slack-new');

      // The signing secret, written and never shown again.
      await page.getByTestId('set-signingSecret').click();
      const secret = page.getByRole('dialog', { name: 'Store the signing secret' });
      await secret.getByLabel('Signing secret').fill(SIGNING_SECRET);
      await secret.getByRole('button', { name: 'Store' }).click();
      await expect(secret).toBeHidden();
      await expect(page.getByTestId('secret-signingSecret')).toHaveAttribute('data-set', 'true');

      // The bot: the service account that holds it, and the bot token Slack checks.
      await openTab(page, 'Bot');
      await expect(page.getByTestId('bot-missing')).toBeVisible();
      await page.getByTestId('set-bot').click();
      const bot = page.getByRole('dialog', { name: 'Set the bot' });
      await bot.getByLabel('Service account').selectOption({ label: identity.name });
      await bot.getByLabel('Bot token').fill(workspace.botToken);
      await check(page, testInfo, 'set-bot', true);
      await bot.getByRole('button', { name: 'Check and set' }).click();
      await expect(bot).toBeHidden();
      await expect(page.getByTestId('chat-bot')).toHaveAttribute('data-bot-set', 'true');
      await expect(page.getByTestId('bot-holder')).toContainText(identity.name);
      await expect(page.getByTestId('bot-workspace')).toContainText(workspace.teamId);

      // Test checks the bot and the secret.
      await systemAction(page, 'test-system');
      await expect(page.getByTestId('test-result')).toHaveAttribute('data-ok', 'true');
      await expect(page.getByTestId('test-result')).toContainText("the bot's token is accepted");
      // The channels the bot is in: public ones; a private one only to a member in it.
      await openTab(page, 'Channels');
      const channels = page.getByTestId('chat-channels');
      await expect(channels.getByTestId('channel')).toHaveCount(2);
      await expect(channels).toContainText('support');
      await expect(channels).not.toContainText('e2e-private');
      await check(page, testInfo, 'slack-ready');

      // Only the bot of this workspace replaces it: another workspace's token is refused in words.
      await openTab(page, 'Bot');
      await page.getByTestId('replace-bot').click();
      const replace = page.getByRole('dialog', { name: 'Replace the bot token of the bot' });
      await expect(replace.getByTestId('bot-principal-fixed')).toHaveText(identity.name);
      await replace.getByLabel('Bot token').fill(other.botToken);
      await replace.getByRole('button', { name: 'Check and set' }).click();
      await expect(replace.getByTestId('bot-form-error')).toContainText(
        'This token belongs to another workspace or another bot than the system’s.',
      );
      await check(page, testInfo, 'bot-mismatch', true);
      await replace.getByRole('button', { name: 'Cancel' }).click();

      // Another service account cannot take the bot while one holds it.
      const second = await newServiceIdentity(admin, apiPath, `svc-other-${tag.toLowerCase()}`);
      try {
        const refused = await admin.raw('PUT', apiPath(`/systems/${systemId}/bot`), {
          principalId: second.id,
          token: workspace.botToken,
        });
        expect(refused.status()).toBe(409);
        expect((await refused.json()).code).toBe('BOT_ACCOUNT_IN_USE');
      } finally {
        await admin.delete(apiPath(`/service-identities/${second.id}`));
      }

      // Removing the bot leaves the system without one.
      await page.getByTestId('remove-bot').click();
      const remove = page.getByRole('alertdialog', { name: `Remove the bot of ${name}?` });
      await remove.getByRole('button', { name: 'Remove the bot' }).click();
      await expect(page.getByTestId('bot-missing')).toBeVisible();
    } finally {
      if (systemId) await admin.delete(apiPath(`/systems/${systemId}`)).catch(() => undefined);
      await admin.delete(apiPath(`/service-identities/${identity.id}`)).catch(() => undefined);
    }
  });

  test('a member links their Slack user through the bot’s link, and an admin can remove it', async ({
    pageAs,
    adminPage,
    api,
    apiPath,
    appPath,
    seed,
    slack,
  }, testInfo) => {
    const tag = unique(testInfo);
    const workspace = slackWorkspace(tag);
    const user = `U${tag}A`;
    const admin = await api('admin');
    const identity = await newServiceIdentity(admin, apiPath, `svc-slack-${tag.toLowerCase()}`);
    const system = await admin.post(apiPath('/systems'), {
      name: `Slack ${tag}`,
      kind: 'SLACK',
      slack: { transport: 'EVENTS_API' },
    });
    try {
      await admin.put(apiPath(`/systems/${system.id}/chat/secrets`), {
        signingSecret: SIGNING_SECRET,
      });
      await admin.put(apiPath(`/systems/${system.id}/bot`), {
        principalId: identity.id,
        token: workspace.botToken,
      });
      await slack.setUser(user, 'Alice Novak');

      // Alice writes to the bot; it answers with her sign-in link.
      const status = await deliverSlackEvent(
        urls.server,
        system.id,
        SIGNING_SECRET,
        slackDirectMessage(workspace, user, 'Hello, Tinfirm'),
      );
      expect(status).toBe(200);
      const link = await waitFor('the bot’s sign-in link', async () => {
        for (const post of await slack.posts(`D${user}`)) {
          const found = /\/link\/[0-9a-f-]+#[A-Za-z0-9_-]+/.exec(JSON.stringify(post.body));
          if (found) return found[0];
        }
        return undefined;
      });
      expect(link.startsWith(`/link/${seed.companyId}#`)).toBe(true);

      const page = await pageAs('alice');
      await page.goto(link);
      await expect(page.getByTestId('link-title')).toHaveText(
        `Link the Slack user ${user} to your account?`,
      );
      // The token left the address.
      // nosemgrep: javascript.lang.security.audit.detect-non-literal-regexp.detect-non-literal-regexp -- built from the test's own values, never input
      await expect(page).toHaveURL(new RegExp(`/link/${seed.companyId}$`));
      await expect(page.getByTestId('link-who')).toContainText(
        `of the workspace ${workspace.teamName} (${workspace.teamId}), on Slack ${tag}, to your account alice@acme.test`,
      );
      await expect(page.getByTestId('link-warning')).toContainText(
        'Its display name, Alice Novak, is one anyone can set.',
      );
      await expect(page.getByTestId('link-owner-notice')).toBeVisible();
      await check(page, testInfo, 'link-confirm');

      await page.getByTestId('link-confirm').click();
      await expect(page.getByTestId('link-title')).toHaveText('Your chat account is linked');
      await check(page, testInfo, 'link-done');

      // Both sides are told: the bot in the direct message.
      await waitFor('the bot’s “Linked to …”', async () =>
        (await slack.posts(`D${user}`)).some((post) => /Linked to/.test(JSON.stringify(post.body)))
          ? true
          : undefined,
      );

      // The link is used: opened again (a new page load, not a change of the hash), it says so.
      await page.goto('about:blank');
      await page.goto(link);
      await expect(page.getByTestId('link-refused')).toHaveAttribute('data-code', 'CHAT_LINK_USED');
      await check(page, testInfo, 'link-used');

      // Alice's Accounts lists the link, with Unlink.
      await page.getByRole('link', { name: 'Go to Tinfirm' }).click();
      await page.goto(appPath('/accounts?tab=systems'));
      const row = page.locator(`#chat-${system.id}`);
      await expect(row).toHaveAttribute('data-linked', 'true');
      await expect(row.getByTestId('chat-link-says')).toContainText(`Slack user`);
      await expect(row.getByTestId('chat-unlink')).toBeVisible();
      await row.scrollIntoViewIfNeeded();
      // Their direct messages go to the agent they pick.
      await row
        .page()
        .getByLabel('Your direct messages go to')
        .selectOption({ label: 'Analyst (@analyst)' });
      await expect
        .poll(async () => (await (await api('alice')).get(apiPath('/me/chat'))).defaultAgentId)
        .toBe(seed.agent.id);
      await check(page, testInfo, 'accounts-chat-links');
      await (await api('alice')).put(apiPath('/me/chat'), { defaultAgentId: null });

      // An admin sees the link on Members, and removes it.
      await adminPage.goto(appPath('/members'));
      await adminPage.getByTestId('member-alice@acme.test').getByRole('button').click();
      const panel = adminPage.getByTestId('member-dialog');
      const chip = panel.locator(`[data-testid="member-account-Slack ${tag}"]`);
      await expect(chip).toBeVisible();
      await check(adminPage, testInfo, 'members-chat-link');
      await chip.getByTestId('remove-member-chat-account').click();
      const dialog = adminPage.getByRole('alertdialog', {
        name: `Remove the Slack ${tag} link of Alice Analyst?`,
      });
      await dialog.getByRole('button', { name: 'Remove the link' }).click();
      await expect(chip).toHaveCount(0);

      await page.reload();
      await expect(page.locator(`#chat-${system.id}`)).toHaveAttribute('data-linked', 'false');
    } finally {
      await admin.delete(apiPath(`/systems/${system.id}`)).catch(() => undefined);
      await admin.delete(apiPath(`/service-identities/${identity.id}`)).catch(() => undefined);
    }
  });
  test('an admin registers a webhook, maps it against a sample, sees a delivery and rotates its secret', async ({
    adminPage: page,
    api,
    apiPath,
    appPath,
  }, testInfo) => {
    const tag = unique(testInfo);
    const name = `Ops alerts ${tag}`;
    const admin = await api('admin');
    let systemId: string | undefined;
    try {
      await page.goto(appPath('/systems'));
      await page.getByTestId('register-system').click();
      const dialog = page.getByRole('dialog', { name: 'Register a system' });
      await dialog.getByRole('radio', { name: /^Webhook/ }).check();
      await dialog.getByTestId('system-next').click();
      await dialog.getByLabel('Name').fill(name);
      await dialog.getByTestId('system-next').click();
      await expect(dialog.getByRole('radio', { name: /^Signed/ })).toBeChecked();
      await check(page, testInfo, 'register-webhook', true);
      await dialog.getByRole('button', { name: 'Register' }).click();

      // The secret, once, before the page.
      const shown = page.getByRole('dialog', { name: `${name} is registered` });
      const secret = (await shown.getByTestId('webhook-secret').textContent())!.trim();
      expect(secret).toMatch(/^whsec_/);
      await check(page, testInfo, 'webhook-secret', true);
      await shown.getByRole('button', { name: 'I have copied the secret' }).click();
      await expect(page.getByRole('heading', { level: 1, name })).toBeVisible();
      systemId = page.url().split('/').pop()!;
      await expect(page.getByTestId('webhook-url')).toHaveText(`${urls.app}/api/hooks/${systemId}`);

      // The mapping, saved, then tried against a sample delivery.
      await openTab(page, 'Mapping');
      await page.getByLabel('Event').fill('/action');
      await page.getByLabel('Subject key').fill('/issue/key');
      await page.getByLabel('Subject title').fill('/issue/title');
      await page.getByTestId('mapping-save').click();
      // Saved, not only saving: the button is disabled while the request is out as well, and a try made
      // before the server has the mapping reads the old one. The hint goes once the saved mapping is back.
      await expect(page.getByText('Not saved yet: a try uses the saved one.')).toBeHidden();
      await expect(page.getByTestId('mapping-save')).toBeDisabled();
      const sample = { action: 'opened', issue: { key: `OPS-${tag}`, title: 'Disk full' } };
      await page.locator('#mapping-sample').fill(JSON.stringify(sample, null, 2));
      await page.getByTestId('mapping-try-run').click();
      await expect(page.getByTestId('mapping-result')).toContainText(`OPS-${tag}`);
      await expect(page.getByTestId('mapping-triggers')).toContainText('None on this system yet');
      await check(page, testInfo, 'webhook-mapped');

      // A signed delivery arrives and is listed, with what the mapping read.
      const answer = await deliverWebhook(urls.server, systemId, secret, sample);
      expect(answer.status).toBe(202);
      const forged = await deliverWebhook(
        urls.server,
        systemId,
        'whsec_bm90IHRoZSBzZWNyZXQ',
        sample,
      );
      expect(forged.status).toBe(401);
      await openTab(page, 'Deliveries');
      await page.getByTestId('deliveries-reload').click();
      const delivery = page.getByTestId('delivery');
      await expect(delivery).toHaveCount(1);
      await expect(delivery).toHaveAttribute('data-status', 'NO_TRIGGER');
      await expect(delivery).toContainText(`OPS-${tag}`);
      await delivery.getByRole('button').click();
      await expect(page.getByTestId('delivery-body')).toContainText('Disk full');
      await page.getByTestId('webhook-deliveries').scrollIntoViewIfNeeded();
      await check(page, testInfo, 'webhook-deliveries');

      // Rotate: the new secret once; the old one still accepted meanwhile.
      await openTab(page, 'Overview');
      await page.getByTestId('rotate-secret').click();
      await page
        .getByRole('alertdialog', { name: `Rotate the secret of ${name}?` })
        .getByRole('button', { name: 'Rotate' })
        .click();
      const rotated = page.getByRole('dialog', { name: `The new secret of ${name}` });
      const next = (await rotated.getByTestId('webhook-secret').textContent())!.trim();
      expect(next).not.toBe(secret);
      await check(page, testInfo, 'webhook-rotated', true);
      await rotated.getByRole('button', { name: 'I have copied the secret' }).click();
      expect((await deliverWebhook(urls.server, systemId, next, { ...sample, n: 2 })).status).toBe(
        202,
      );
      expect(
        (await deliverWebhook(urls.server, systemId, secret, { ...sample, n: 3 })).status,
      ).toBe(202);

      // The company's limits on chats and webhooks, a section of Settings.
      await page.goto(appPath('/settings/limits'));
      const limits = page.getByTestId('chat-rates');
      await expect(limits.getByLabel('Deliveries a minute, at most')).not.toHaveValue('');
      await limits.scrollIntoViewIfNeeded();
      await check(page, testInfo, 'settings-limits');
    } finally {
      if (systemId) await admin.delete(apiPath(`/systems/${systemId}`)).catch(() => undefined);
    }
  });
});
