import type { Page } from '@playwright/test';
import { expect, expectNoAxeViolations, test } from '../fixtures';

/**
 * V1 (the plan §8): Alice and Bob talk to one shared agent, each as themselves. The run is recorded
 * on video as test evidence (the plan §11).
 *
 * Seeded: the agent Analyst is shared with Everyone; Alice and Bob each hold their own GitHub account (the stub
 * knows gh-token-alice as alice and gh-token-bob as bob); the runner holds every turn until the test drives it.
 */
// Recorded as test evidence (the plan §11).
test.use({ video: 'on' });

/** Starts a conversation from the new conversation page; answers its id once the page opened it. */
async function startFromThePage(page: Page, path: string, message: string): Promise<string> {
  await page.goto(path);
  const panel = page.getByTestId('readiness').getByTestId('who-acts-who-pays');
  // Who acts and who pays, before the first message goes.
  await expect(panel.locator('[data-part=acts]')).toContainText('GitHub: your own account');
  await expect(panel.locator('[data-part=pays]')).toContainText('Your budget');
  await page.getByTestId('first-message').fill(message);
  await page.getByTestId('start-conversation').click();
  await expect(page).toHaveURL(/\/conversations\/[0-9a-f-]{36}$/);
  return page.url().split('/').at(-1)!;
}

test.describe('V1: Alice and Bob talk to one shared agent', { tag: '@app' }, () => {
  test('each conversation acts as its own person and pays from their own budget @serial', async ({
    alicePage,
    bobPage,
    appPath,
    runner,
    stubs,
    seed,
  }, testInfo) => {
    const tag = `${testInfo.project.name} ${Date.now().toString(36)}`;
    const newPath = appPath(`/conversations/new?agentId=${seed.agent.id}`);
    const aliceId = await startFromThePage(alicePage, newPath, `Alice reads acme/shop#1 (${tag})`);
    const bobId = await startFromThePage(bobPage, newPath, `Bob reads acme/shop#1 (${tag})`);

    // Each conversation's header says the same, once started, in its one line.
    for (const page of [alicePage, bobPage]) {
      const panel = page.getByTestId('who-acts-who-pays');
      await expect(panel.locator('[data-part=acts]')).toContainText('GitHub: your own account');
      await expect(panel.locator('[data-part=pays]')).toContainText('Pays from your budget');
      await expect(page.getByTestId('live-turn-working')).toContainText('Working');
    }
    await expectNoAxeViolations(alicePage, testInfo);

    // Each run calls GitHub; the stub sees each person's own token.
    await stubs.clearCalls();
    const aliceRun = await runner.awaitRun({ conversationId: aliceId });
    const bobRun = await runner.awaitRun({ conversationId: bobId });
    const aliceAck = await runner.tool(aliceRun.runId, { tool: 'github_get', endTurn: true });
    const bobAck = await runner.tool(bobRun.runId, { tool: 'github_get', endTurn: true });
    expect(aliceAck.status).toBe('DONE');
    expect(bobAck.status).toBe('DONE');
    const identities = (await stubs.calls('github')).map((call) => call.identity);
    expect(identities).toContain('alice');
    expect(identities).toContain('bob');
    expect(identities.indexOf('alice')).toBeLessThan(identities.indexOf('bob'));

    await expect(alicePage.getByTestId('turn-answer').last()).toContainText(
      'github_get answered DONE',
    );
    await expect(bobPage.getByTestId('turn-answer').last()).toContainText(
      'github_get answered DONE',
    );
  });

  test('neither sees the other’s conversation', async ({
    bobPage,
    appPath,
    api,
    apiPath,
    runner,
    startConversation,
  }, testInfo) => {
    const tag = `${testInfo.project.name} ${Date.now().toString(36)}`;
    const alice = await startConversation('alice', `Alice’s own question (${tag})`);
    const bob = await startConversation('bob', `Bob’s own question (${tag})`);
    await runner.ask(alice.run.runId, { question: `Alice, which release? (${tag})` });
    await runner.ask(bob.run.runId, { question: `Bob, which release? (${tag})` });

    // Bob opens Alice's conversation: the not-found page, nothing more (the plan §2, rule 4).
    await bobPage.goto(appPath(`/conversations/${alice.conversation.id}`));
    await expect(bobPage.getByRole('heading', { name: 'Page not found' })).toBeVisible();
    await expect(bobPage.getByTestId('thread')).toHaveCount(0);
    await expect(bobPage.getByText(`Alice’s own question (${tag})`)).toHaveCount(0);

    // Neither's list or inbox has the other's conversation or question.
    const asBob = await api('bob');
    const asAlice = await api('alice');
    const bobs: { id: string }[] = await asBob.all(apiPath('/conversations'));
    expect(bobs.map((c) => c.id)).toContain(bob.conversation.id);
    expect(bobs.map((c) => c.id)).not.toContain(alice.conversation.id);
    // A question's item names its conversation in the decision.
    const inboxIds = async (who: typeof asBob) =>
      (
        (await who.get(apiPath('/inbox'))) as {
          items: {
            conversation?: { id: string } | null;
            decision?: { conversationId: string } | null;
          }[];
        }
      ).items.map((item) => item.conversation?.id ?? item.decision?.conversationId);
    await expect.poll(() => inboxIds(asAlice)).toContain(alice.conversation.id);
    expect(await inboxIds(asAlice)).not.toContain(bob.conversation.id);
    await expect.poll(() => inboxIds(asBob)).toContain(bob.conversation.id);
    expect(await inboxIds(asBob)).not.toContain(alice.conversation.id);

    // Clean: both questions closed with their conversations.
    await asAlice.post(apiPath(`/conversations/${alice.conversation.id}/end`));
    await asBob.post(apiPath(`/conversations/${bob.conversation.id}/end`));
  });
});
