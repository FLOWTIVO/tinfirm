import type { Page, TestInfo } from '@playwright/test';
import { expect, expectNoAxeViolations, expectNoHorizontalOverflow, test } from '../fixtures';
import { Api } from '../harness/api';
import { password, root, urls } from '../harness/config';

/** The items of each group of the sidebar, in order (src/app/shell/navigation.ts). */
const WORK = ['Inbox', 'Conversations', 'Agents', 'Skills', 'Triggers', 'Accounts'];
const COMPANY = [
  'Systems',
  'Service accounts',
  'Model access',
  'Runner hosts',
  'Members',
  'Kill switches',
  'Audit log',
  'Retention',
  'Settings',
];

function nav(page: Page, name: string) {
  return page.getByRole('navigation', { name, exact: true });
}

/** The labels of a group's links, without the inbox's count for a screen reader. */
function links(page: Page, group: string) {
  return nav(page, group).locator('a > span:not(.sr-only)');
}

/** The page at `path`, with the sidebar shown: on a phone it is a sheet behind the header's button. */
async function openNavigation(page: Page, path: string, title: string, testInfo: TestInfo) {
  await page.goto(path);
  await expect(page.getByRole('heading', { level: 1, name: title })).toBeVisible();
  if (testInfo.project.use.isMobile) {
    await page.getByRole('button', { name: 'Toggle navigation' }).click();
  }
}

/** axe, and on the phone no sideways scroll. */
async function check(page: Page, testInfo: TestInfo) {
  await expectNoAxeViolations(page, testInfo);
  if (testInfo.project.use.isMobile) await expectNoHorizontalOverflow(page);
}

/**
 * W0, the foundation (the plan §7): the shell and its navigation for the three roles (a member, an admin or the
 * owner, and an instance admin who is no member), and the shell while a long page scrolls.
 */
test.describe('W0: the shell', { tag: ['@app', '@phone'] }, () => {
  test('a member sees the six items of Work, and nothing of Company', async ({
    alicePage,
    appPath,
  }, testInfo) => {
    await openNavigation(alicePage, appPath('/inbox'), 'Inbox', testInfo);
    await expect(links(alicePage, 'Work')).toHaveText(WORK);
    await expect(nav(alicePage, 'Company')).toHaveCount(0);
    await expect(nav(alicePage, 'Instance administration')).toHaveCount(0);
    await check(alicePage, testInfo);
  });

  test('an admin and the owner see the Company group too', async ({
    adminPage,
    ownerPage,
    appPath,
  }, testInfo) => {
    for (const page of [adminPage, ownerPage]) {
      await openNavigation(page, appPath('/inbox'), 'Inbox', testInfo);
      await expect(links(page, 'Work')).toHaveText(WORK);
      await expect(links(page, 'Company')).toHaveText(COMPANY);
      await expect(nav(page, 'Instance administration')).toHaveCount(0);
    }
    await check(adminPage, testInfo);

    await adminPage.goto(appPath('/settings'));
    await expect(adminPage.getByRole('heading', { level: 1, name: 'Settings' })).toBeVisible();
    await expect(adminPage.getByTestId('personal-mcp')).toBeVisible();
  });

  test('an instance admin who is no member reaches the instance pages only', async ({
    browser,
    page,
  }, testInfo) => {
    // An instance admin of its own: the root comes to own companies that other specs make.
    const rootApi = await Api.login(urls.server, root.email, root.password);
    const suffix = `${testInfo.project.name}-${Date.now().toString(36)}`;
    const email = `ivy-${suffix}@tinfirm.test`;
    const ivy = await rootApi.post('/api/admin/users', {
      email,
      displayName: `Ivy ${suffix}`,
      password,
      instanceAdmin: true,
    });
    const api = await Api.login(urls.server, email, password);
    const state = testInfo.outputPath('instance-admin-state.json');
    await api.saveStorageState(state);
    await api.dispose();
    const context = await browser.newContext({
      storageState: state,
      viewport: page.viewportSize(),
      isMobile: testInfo.project.use.isMobile,
      hasTouch: testInfo.project.use.hasTouch,
      deviceScaleFactor: testInfo.project.use.deviceScaleFactor,
      baseURL: testInfo.project.use.baseURL,
    });
    try {
      const adminPage = await context.newPage();
      await openNavigation(adminPage, '/admin/users', 'Users', testInfo);
      await expect(links(adminPage, 'Instance administration')).toHaveText([
        'Users',
        'Companies',
        'Instance audit log',
        'Errors',
        'Licence',
      ]);
      await expect(nav(adminPage, 'Work')).toHaveCount(0);
      await expect(nav(adminPage, 'Company')).toHaveCount(0);
      await check(adminPage, testInfo);
      for (const [path, title] of [
        ['/admin/companies', 'Companies'],
        ['/admin/audit', 'Instance audit log'],
      ]) {
        await adminPage.goto(path);
        await expect(adminPage.getByRole('heading', { level: 1, name: title })).toBeVisible();
      }
    } finally {
      await context.close();
      await rootApi.raw('DELETE', `/api/admin/users/${ivy.id}`);
      await rootApi.dispose();
    }
  });

  test.describe('the shell while a long page scrolls', () => {
    test.use({ persona: 'admin' });

    /** The page scrolled to its end, and how far that was. */
    async function scrollToEnd(page: import('@playwright/test').Page): Promise<number> {
      await page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight));
      await expect.poll(() => page.evaluate(() => window.scrollY)).toBeGreaterThan(0);
      return page.evaluate(() => window.scrollY);
    }

    test('the sidebar and the header stay at the top of the viewport', async ({
      page,
      appPath,
    }, testInfo) => {
      // The audit log of the seeded company is many pages tall.
      await page.goto(appPath('/audit'));
      await expect(page.getByRole('heading', { level: 1, name: 'Audit log' })).toBeVisible();
      await expect(page.locator('main li[data-state]').nth(10)).toBeVisible();
      const viewport = page.viewportSize()!;
      const scrolled = await scrollToEnd(page);
      expect(scrolled, 'the page is longer than the viewport').toBeGreaterThan(100);

      const header = await page.locator('main > header').boundingBox();
      expect(header?.y).toBe(0);

      if (testInfo.project.use.isMobile) {
        // On a phone the navigation is a sheet; its trigger in the sticky header stays reachable.
        const toggle = page.getByRole('button', { name: 'Toggle navigation' });
        await expect(toggle).toBeInViewport();
        await toggle.click();
        await expect(
          page.getByRole('navigation', { name: 'Company', exact: true }),
        ).toBeInViewport();
        return;
      }
      const sidebar = await page.locator('[data-slot="sidebar-container"]').boundingBox();
      expect(sidebar?.y).toBe(0);
      expect(sidebar!.y + sidebar!.height).toBeLessThanOrEqual(viewport.height);
      expect(sidebar!.height).toBe(viewport.height);
      await expect(
        page.getByRole('navigation', { name: 'Work', exact: true }).getByRole('link', {
          name: 'Inbox',
        }),
      ).toBeInViewport();
      // The sidebar's own items scroll inside it when they are taller than the viewport, and the page stays.
      const settings = page
        .getByRole('navigation', { name: 'Company', exact: true })
        .getByRole('link', { name: 'Settings' });
      await settings.scrollIntoViewIfNeeded();
      await expect(settings).toBeInViewport();
      expect(await page.evaluate(() => window.scrollY)).toBe(scrolled);
    });
  });
});
