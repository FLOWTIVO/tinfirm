import { expect, expectNoAxeViolations, test } from '../fixtures';

/**
 * V5 (the plan §8): Carol, without a GitHub account of her own, is in Analysts, to whom svc-dev's GitHub account is
 * lent read-only. On video.
 *
 * Seeded: Carol is in Analysts and holds no account; svc-dev's GitHub and Jira accounts are granted to Analysts
 * with READ; the agent lists acme/shop with WRITE.
 */
// Recorded as test evidence (the plan §11).
test.use({ video: 'on' });

test.describe('V5: Carol acts through the Analysts grant, read-only', { tag: '@app' }, () => {
  test.use({ persona: 'carol' });

  test('the header and the workspace say "via Analysts, read-only"', async ({
    page,
    appPath,
    runner,
    seed,
  }, testInfo) => {
    await page.goto(appPath(`/conversations/new?agentId=${seed.agent.id}`));
    const before = page.getByTestId('readiness').getByTestId('who-acts-who-pays');
    await expect(before.locator('[data-part=acts]')).toContainText(
      'GitHub: via Analysts, read-only',
    );
    await page
      .getByTestId('first-message')
      .fill(`Carol reads acme/shop (${testInfo.project.name} ${Date.now().toString(36)})`);
    await page.getByTestId('start-conversation').click();
    await expect(page).toHaveURL(/\/conversations\/[0-9a-f-]{36}$/);
    const conversationId = page.url().split('/').at(-1)!;

    await expect(page.getByTestId('who-acts-who-pays').locator('[data-part=acts]')).toContainText(
      'GitHub: via Analysts, read-only',
    );
    // The side column marks the repository read-only, and says why.
    const repository = page
      .locator('aside')
      .locator(`[data-repository="${seed.repository.id}"]`)
      .getByTestId('repository-access');
    await expect(repository).toHaveText('Read-only: the grant of Analysts is read-only');
    await expectNoAxeViolations(page, testInfo);

    // The run's workspace is read-only too.
    const run = await runner.awaitRun({ conversationId, state: 'running' });
    expect(run.workspaces.map((w) => w.readOnly)).toEqual([true]);
    await runner.finish(run.runId, { summary: 'Read only.' });
  });

  test('a write call ends in the refusal that names the grant, and no approval card appears @serial', async ({
    page,
    api,
    apiPath,
    appPath,
    runner,
    stubs,
    startConversation,
  }, testInfo) => {
    const { conversation, run } = await startConversation(
      'carol',
      `Carol comments on acme/shop#1 (${testInfo.project.name} ${Date.now().toString(36)})`,
    );
    await page.goto(appPath(`/conversations/${conversation.id}`));
    await stubs.clearCalls();

    const ack = await runner.tool(run.runId, { tool: 'github_comment', endTurn: true });
    expect(ack.status).toBe('REJECTED');
    expect(String(ack.message)).toMatch(/Analysts/);

    await expect(page.getByTestId('turn-answer').last()).toContainText(
      'github_comment answered REJECTED',
    );
    // No approval card, and nothing in Carol's inbox for it.
    await expect(page.locator('app-decision-slot')).toHaveCount(0);
    await expect(page.getByTestId('conversation-state')).not.toHaveText(/Needs you/);
    const inbox = (await (await api('carol')).get(apiPath('/inbox'))) as {
      items: {
        conversation?: { id: string } | null;
        decision?: { conversationId: string } | null;
      }[];
    };
    expect(
      inbox.items.filter(
        (item) => (item.conversation?.id ?? item.decision?.conversationId) === conversation.id,
      ),
    ).toEqual([]);
    // GitHub saw no write.
    expect((await stubs.calls('github')).filter((call) => call.method !== 'GET')).toEqual([]);
  });
});
