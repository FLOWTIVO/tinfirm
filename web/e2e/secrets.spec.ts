import { expect, test } from './fixtures';
import { loginToFirstCompany } from './support';

/**
 * The company's secrets (the plan §6); needs the app's pages (`@app`). They are a section of
 * Settings (the plan §5), where this spec goes through the navigation.
 */
test.describe('secrets', { tag: '@app' }, () => {
  test('creates a write-only secret, then deletes it', async ({ page }) => {
    const name = `E2E_SECRET_${Date.now()}`;
    const value = `e2e-value-${Math.random().toString(36).slice(2)}`;

    await loginToFirstCompany(page);
    // Secrets are a section of Settings (the plan §5).
    await page
      .getByRole('navigation', { name: 'Company', exact: true })
      .getByRole('link', { name: 'Settings' })
      .click();
    await page
      .getByRole('navigation', { name: 'Sections of the settings' })
      .getByRole('link', { name: /Secrets/ })
      .click();
    await expect(page.getByRole('heading', { name: 'Secrets', exact: true })).toBeVisible();
    await expect(page.getByText('Values are write-only')).toBeVisible();

    await page.getByRole('button', { name: 'New secret' }).first().click();
    const dialog = page.getByRole('dialog');
    await dialog.getByLabel('Name').fill(name);
    await dialog.getByLabel('Description').fill('Created by the Playwright suite');
    const valueField = dialog.getByLabel('Value');
    await expect(valueField).toHaveAttribute('type', 'password');
    await valueField.fill(value);
    await dialog.getByRole('button', { name: 'Store secret' }).click();

    const row = page.getByRole('row').filter({ hasText: name });
    await expect(row).toBeVisible();
    await expect(row).toContainText('v1');
    // The plaintext is never rendered anywhere.
    expect(await page.content()).not.toContain(value);

    // A reload shows the secret from the server, still without its value.
    await page.reload();
    await expect(page.getByRole('row').filter({ hasText: name })).toBeVisible();
    expect(await page.content()).not.toContain(value);

    // Clean up.
    await page.getByRole('button', { name: `Delete ${name}` }).click();
    await page.getByRole('alertdialog').getByRole('button', { name: 'Delete secret' }).click();
    await expect(page.getByRole('row').filter({ hasText: name })).toHaveCount(0);
  });
});
