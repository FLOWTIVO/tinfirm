import type { Locator, Page, TestInfo } from '@playwright/test';
import { company, urls } from '../harness/config';
import { linkSlackUser } from '../harness/slack-link';
import {
  expect,
  expectNoAxeViolations,
  expectNoHorizontalOverflow,
  shoot,
  test,
} from '../fixtures';

/**
 * W4b, decisions and the inbox (the plan §4.1, §5 and §8), against the real server: the scripted runner raises an
 * approval and a question in Alice's conversations, and Alice decides them from the inbox: the approval opened in
 * place with the keyboard, the question in its whole card. Each row of what needs her collapses to what she did. At
 * a glance, the inbox also shows a paused trigger of hers (W5) and her Slack link (W6). Her inbox also holds what
 * other specs left, so each test finds its own row by its decision or its conversation.
 */

/** The decision a run's request raised, from its ack. */
function decisionOf(ack: { payload?: unknown }): string {
  const id = (ack.payload as { decisionId?: string } | null)?.decisionId;
  expect(id, 'the ack names the decision').toBeTruthy();
  return id!;
}

/** A decision's row of what needs the reader, folded or open in place. */
function need(page: Page, decisionId: string): Locator {
  return page.locator(`[data-testid=inbox-need][data-decision-id="${decisionId}"]`);
}

/** A decision's whole card, once its row is open. */
function card(page: Page, decisionId: string): Locator {
  return page.locator(`[data-testid=decision][data-decision-id="${decisionId}"]`);
}

/** A review screenshot of the whole page at its full height. */
async function capture(page: Page, testInfo: TestInfo, name: string): Promise<void> {
  if (!process.env['E2E_SCREENSHOTS']) return;
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.evaluate(() => document.fonts.ready);
  await shoot(page, testInfo, 'w4b', name);
}

test.describe('W4b: decisions and the inbox', { tag: ['@app', '@phone'] }, () => {
  test('approve a tool call from the inbox, with the keyboard @serial', async ({
    alicePage: page,
    appPath,
    runner,
    startConversation,
    stubs,
  }, testInfo) => {
    const { conversation, run } = await startConversation(
      'alice',
      `Comment on acme/shop#1 (${testInfo.project.name})`,
    );
    const decisionId = decisionOf(await runner.approval(run.runId, { tool: 'github_comment' }));

    await page.goto(appPath('/inbox'));
    await expect(page.getByRole('heading', { level: 1, name: 'Inbox' })).toBeVisible();
    await expect(page.getByTestId('inbox-filters').locator('[data-filter=needs]')).toBeVisible();
    const row = need(page, decisionId);
    await expect(row).toBeVisible();
    await expect(row.getByTestId('inbox-row-conversation')).toContainText(conversation.key);
    await expect(row.getByTestId('inbox-quick-APPROVED')).toBeVisible();
    await expectNoAxeViolations(page, testInfo);
    await expectNoHorizontalOverflow(page);

    // The keyboard alone: open it in place, the note, then Approve.
    await row.getByRole('button', { name: 'Add a note' }).focus();
    await page.keyboard.press('Enter');
    const approval = card(page, decisionId);
    await expect(approval).toBeVisible();
    await expect(approval).toHaveAttribute('data-subject', 'TOOL_CALL');
    await expect(approval.getByTestId('decision-what')).toContainText('on GitHub');
    await expect(approval.getByTestId('decision-arguments')).toBeHidden();
    await expect(approval.getByRole('textbox', { name: /^Note for/ })).toBeFocused();
    await expectNoAxeViolations(page, testInfo);
    await page.keyboard.type('Go ahead.');
    await page.keyboard.press('Tab');
    await expect(approval.getByTestId('decide-APPROVED')).toBeFocused();
    await stubs.clearCalls();
    await page.keyboard.press('Enter');

    const done = page
      .locator('li')
      .filter({ has: page.getByTestId('inbox-done') })
      .filter({
        hasText: conversation.key,
      });
    await expect(done.getByTestId('inbox-done')).toContainText('Approved');
    await expect(done.getByTestId('inbox-done')).toBeFocused();
    await expect(approval).toBeHidden();
    await expect
      .poll(async () => (await stubs.calls('github')).filter((call) => call.method === 'POST'))
      .toEqual([
        expect.objectContaining({
          path: '/repos/acme/shop/issues/1/comments',
          identity: 'alice',
          status: 201,
        }),
      ]);
    const turn = await runner.awaitRun({ conversationId: conversation.id, state: 'running' });
    expect(turn.runId).not.toBe(run.runId);
    await runner.finish(turn.runId);
  });

  test('answer a question from the inbox', async ({
    alicePage: page,
    appPath,
    runner,
    startConversation,
  }, testInfo) => {
    const { conversation, run } = await startConversation(
      'alice',
      `Draft the brief for SHOP-1 (${testInfo.project.name})`,
    );
    const decisionId = decisionOf(
      await runner.ask(run.runId, {
        question: 'Should the brief cover the **mobile app** too?',
        options: ['Yes', 'No, the web shop only'],
      }),
    );

    await page.goto(appPath('/inbox'));
    const row = need(page, decisionId);
    await expect(row).toHaveAttribute('data-kind', 'DECISION');
    // Its options answer in one click; Reply… opens the whole card.
    await expect(row.getByRole('button', { name: 'Answer No, the web shop only' })).toBeVisible();
    await row.getByRole('button', { name: 'Reply…' }).click();
    const question = card(page, decisionId);
    await expect(question).toHaveAttribute('data-kind', 'QUESTION');
    await expect(question.locator('strong')).toHaveText('mobile app');
    const send = question.getByTestId('decide-ANSWERED');
    await expect(send).toBeEnabled();
    await send.click();
    await expect(question.getByTestId('decision-missing')).toHaveText(
      'Pick an option or write an answer.',
    );
    await question.getByRole('button', { name: 'No, the web shop only' }).click();
    await expect(question.getByRole('button', { name: 'No, the web shop only' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    await question.getByLabel('Add an answer').fill('The app follows next quarter.');
    await send.click();

    await expect(
      page.getByTestId('inbox-done').filter({ hasText: 'No, the web shop only' }),
    ).toContainText('Answered');
    await expect(question).toBeHidden();
    const turn = await runner.awaitRun({ conversationId: conversation.id, state: 'running' });
    expect(turn.runId).not.toBe(run.runId);
    expect(turn.request).toContain('No, the web shop only');
    expect(turn.request).toContain('The app follows next quarter.');
    await runner.finish(turn.runId);
  });

  test('retry a failed turn from the inbox', async ({
    alicePage: page,
    appPath,
    runner,
    startConversation,
  }, testInfo) => {
    const { conversation, run } = await startConversation(
      'alice',
      `Summarise SHOP-2 (${testInfo.project.name})`,
    );
    await runner.finish(run.runId, {
      outcome: 'FAILED',
      failureClass: 'INFRA',
      failureMessage: 'The runner host lost its workspace',
      summary: null,
      result: null,
    });

    await page.goto(appPath('/inbox'));
    const row = page
      .locator('[data-testid=inbox-row][data-kind=FAILED]')
      .filter({ hasText: conversation.key });
    await expect(row).toBeVisible();
    await expect(row.getByTestId('inbox-row-body')).toContainText('lost its workspace');
    await row.getByTestId('inbox-retry').click();
    await expect(row).toBeHidden();
    await expect(
      page.getByTestId('inbox-done').filter({ hasText: conversation.key }),
    ).toContainText('Queued again');
    const turn = await runner.awaitRun({ conversationId: conversation.id, state: 'running' });
    expect(turn.runId).not.toBe(run.runId);
    await runner.finish(turn.runId);
  });

  test('the inbox at a glance: a question, an approval, a failed turn, a paused trigger and a chat link', async ({
    alicePage: page,
    api,
    apiPath,
    appPath,
    runner,
    seed,
    slack,
    startConversation,
  }, testInfo) => {
    const alice = await api('alice');
    const admin = await api('admin');
    // A trigger of Alice's, paused: its item is hers (W5).
    const agentVersion = (await alice.get(apiPath(`/agents/${seed.agent.id}`))).version;
    const trigger = await alice.post(apiPath('/triggers'), {
      name: `Release digest ${testInfo.project.name} ${Date.now().toString(36)}`,
      source: 'SCHEDULE',
      schedule: { cron: '0 9 * * 1', zone: 'Europe/Prague', overlap: 'SKIP', catchUp: 'NONE' },
      agentId: seed.agent.id,
      agentVersion,
      instruction: 'Write the release digest.',
      settings: { model: company.agent.model },
      accounts: [{ systemId: seed.systems.jira.id, accountId: seed.serviceIdentity.accounts.jira }],
      payerPrincipalId: seed.serviceIdentity.id,
      teamGroupId: seed.groups['Analysts'],
      results: { comment: false, review: false, onSuccess: [], onFailure: [] },
    });
    await alice.post(apiPath(`/triggers/${trigger.id}/pause`), {});
    // Her Slack user, linked: the chat item tells her, with its unlink (W6).
    const link = await linkSlackUser({
      admin,
      member: alice,
      path: apiPath,
      serverUrl: urls.server,
      slack,
      tag: `${testInfo.project.name === 'phone' ? 'P' : 'D'}${Date.now().toString(36).slice(-5).toUpperCase()}`,
      displayName: 'Alice Analyst',
    }).catch(async (error: unknown) => {
      await admin.delete(apiPath(`/triggers/${trigger.id}`)).catch(() => undefined);
      throw error;
    });
    const asked = await startConversation('alice', `Plan the release (${testInfo.project.name})`);
    const questionId = decisionOf(
      await runner.ask(asked.run.runId, {
        question: 'Which release should the notes cover?',
        options: ['2.4', '2.4 and 2.5'],
      }),
    );
    const approved = await startConversation(
      'alice',
      `Comment on acme/shop#1 (${testInfo.project.name})`,
    );
    const approvalId = decisionOf(
      await runner.approval(approved.run.runId, { tool: 'github_comment' }),
    );
    const failed = await startConversation('alice', `Summarise SHOP-3 (${testInfo.project.name})`);
    await runner.finish(failed.run.runId, {
      outcome: 'FAILED',
      failureClass: 'USAGE_LIMIT',
      failureMessage: 'The model provider’s usage limit was reached',
      summary: null,
      result: null,
    });

    try {
      await page.goto(appPath('/inbox'));
      await expect(need(page, questionId)).toBeVisible();
      await expect(need(page, approvalId)).toBeVisible();
      await expect(
        page.locator('[data-kind=FAILED]').filter({ hasText: failed.conversation.key }),
      ).toBeVisible();
      await expect(page.getByTestId('inbox-needs')).toBeVisible();
      await expect(page.getByTestId('inbox-turns')).toBeVisible();
      const notices = page.getByTestId('inbox-notices');
      await expect(
        notices.getByTestId('inbox-trigger').filter({ hasText: trigger.name }),
      ).toBeVisible();
      // The notice; once dismissed, its Undo line keeps the kind (inbox.ts) but is not the notice.
      const chat = notices
        .locator('[data-kind=CHAT_LINKED]:not([data-testid=inbox-dismissed])')
        .filter({ hasText: link.systemName });
      await expect(chat).toBeVisible();
      await expect(chat.getByTestId('inbox-chat-unlink')).toBeVisible();
      await expectNoHorizontalOverflow(page);
      await expectNoAxeViolations(page, testInfo);
      await capture(page, testInfo, 'inbox');
      // A notice that only informs is dismissed for Alice alone, at once, and Undo brings it back.
      await chat.getByTestId('inbox-notice-dismiss').click();
      const dismissed = notices
        .getByTestId('inbox-dismissed')
        .filter({ hasText: `A ${link.systemName} account was linked to you` });
      await expect(dismissed).toBeVisible();
      await expect(chat).toHaveCount(0);
      await dismissed.getByTestId('inbox-dismissed-undo').click();
      await expect(chat).toBeVisible();
    } finally {
      // Leave nothing open in Alice's inbox for the next spec.
      await link.remove();
      await admin.delete(apiPath(`/triggers/${trigger.id}`)).catch(() => undefined);
      await alice.post(apiPath(`/decisions/${questionId}/decide`), { option: '2.4' });
      await alice.post(apiPath(`/decisions/${approvalId}/decide`), { outcome: 'DENIED' });
      for (const conversationId of [
        asked.conversation.id,
        approved.conversation.id,
        failed.conversation.id,
      ]) {
        await alice
          .post(apiPath(`/conversations/${conversationId}/end`), {})
          .catch(() => undefined);
      }
    }
  });

  test('nothing needs Dave', async ({ pageAs, appPath }, testInfo) => {
    // Dave holds no conversation of his own unless another spec gave him one; the empty state is his then.
    const page = await pageAs('dave');
    await page.goto(appPath('/inbox'));
    await expect(page.getByRole('heading', { level: 1, name: 'Inbox' })).toBeVisible();
    const empty = page.getByTestId('inbox-empty');
    await expect(empty.or(page.getByTestId('inbox-filters'))).toBeVisible();
    await expectNoHorizontalOverflow(page);
    await expectNoAxeViolations(page, testInfo);
    if (await empty.isVisible()) {
      await expect(empty).toContainText('Nothing needs you');
      await capture(page, testInfo, 'inbox-empty');
    }
  });
});
