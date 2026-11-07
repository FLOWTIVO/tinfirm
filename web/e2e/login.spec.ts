import { expect, test } from './fixtures';
import { login, loginToFirstCompany, owner } from './support';

/** The login page (kept by the plan §6); needs the app's pages (`@app`). */
test.describe('login', { tag: ['@app', '@phone'] }, () => {
  test('sends anonymous visitors to the login page', async ({ page }) => {
    await page.goto('/');
    await expect(page).toHaveURL(/\/login/);
    await expect(page.getByRole('heading', { name: 'Sign in to Tinfirm' })).toBeVisible();
    await expect(page.getByLabel('Email')).toBeVisible();
  });

  test('rejects a wrong password', async ({ page }) => {
    await login(page, owner.email, 'definitely-not-the-password');
    await expect(page.getByText('Wrong email or password.')).toBeVisible();
    await expect(page).toHaveURL(/\/login/);
  });

  test('logs in, shows the company, and logs out again', async ({ page }, testInfo) => {
    await loginToFirstCompany(page);
    // The company's home is its inbox: what needs a person (the plan §3).
    await expect(page.getByRole('heading', { name: 'Inbox', level: 1 })).toBeVisible();
    // On a phone the navigation, the account menu with it, sits in a sheet: open it first.
    if (testInfo.project.use.isMobile) {
      await page.getByRole('button', { name: 'Toggle navigation' }).click();
    }
    await expect(page.getByRole('navigation', { name: 'Company', exact: true })).toBeVisible();

    await page.getByRole('button', { name: 'Account menu' }).click();
    await page.getByRole('menuitem', { name: 'Log out' }).click();
    await expect(page).toHaveURL(/\/login/);

    // The session is really gone: protected pages send us back to login.
    await page.goto('/me');
    await expect(page).toHaveURL(/\/login\?returnUrl=%2Fme/);
  });

  test('returns to the requested page after logging in', async ({ page }) => {
    await page.goto('/me');
    await expect(page).toHaveURL(/\/login\?returnUrl=%2Fme/);
    await page.getByLabel('Email').fill(owner.email);
    await page.getByLabel('Password').fill(owner.password);
    await page.getByRole('button', { name: 'Sign in', exact: true }).click();
    await expect(page).toHaveURL(/\/me$/);
    await expect(page.getByRole('heading', { name: 'Profile' })).toBeVisible();
  });

  test('names the page to come back to when single sign-on starts', async ({ page }) => {
    // The stack has no identity provider: the list of providers is the server's, with one added.
    await page.route('**/api/auth/providers', async (route) => {
      const response = await route.fetch();
      const body = await response.json();
      body.providers = [
        ...body.providers,
        { id: 'e2eidp', name: 'E2E IdP', kind: 'OIDC', loginUrl: '/oauth2/authorization/e2eidp' },
      ];
      await route.fulfill({ response, json: body });
    });

    await page.goto('/me');
    await expect(page).toHaveURL(/\/login\?returnUrl=%2Fme/);
    await expect(page.getByRole('link', { name: 'Continue with E2E IdP' })).toHaveAttribute(
      'href',
      '/oauth2/authorization/e2eidp?returnTo=%2Fme',
    );

    // The server never sends anyone elsewhere: an unknown provider ends on the login page, whatever returnTo says.
    const start = await page.request.get('/oauth2/authorization/e2eidp?returnTo=%2F%2Fevil.example', {
      maxRedirects: 0,
    });
    expect(start.status()).toBe(302);
    expect(start.headers()['location']).toMatch(/\/login\?error=OIDC_LOGIN_FAILED$/);
  });
});
