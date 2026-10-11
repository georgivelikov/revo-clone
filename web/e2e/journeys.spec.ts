import { expect, test } from '@playwright/test'
import { collectProblems, login, walletIdOf } from './support'

test.describe('signed-in journeys', () => {
  test.beforeEach(async ({ page }) => {
    await login(page)
  })

  test('vaults lists the seeded vault with a balance (orphaned-vault regression)', async ({
    page,
  }) => {
    const problems = collectProblems(page)
    await page.goto('/vaults')

    await expect(page.getByText('No vaults yet')).toHaveCount(0)
    await expect(page.getByText('Seed round-up vault')).toBeVisible()
    await expect(page.getByText(/of €[\d,.]+ goal/)).toBeVisible()
    expect(problems).toEqual([])
  })

  test('analytics shows a spend total and categories', async ({ page }) => {
    await page.goto('/analytics')
    await expect(page.getByText('Total', { exact: true })).toBeVisible()
    await expect(page.getByRole('button', { name: /^Groceries €[\d,.]+/ })).toBeVisible()
  })

  test('activity detail survives a hard refresh (deep link)', async ({ page }) => {
    await page.goto('/activity')
    await page.locator('a[href^="/activity/"]').first().click()
    await expect(page).toHaveURL(/\/activity\/[0-9a-f-]{36}$/)
    const url = page.url()

    await page.reload()
    await expect(page).toHaveURL(url)
    await expect(page.getByRole('link', { name: 'Home' })).toBeVisible()
    await expect(page.getByRole('button', { name: 'Log in' })).toHaveCount(0)
  })

  test('exchange offers a live rate', async ({ page }) => {
    await page.goto('/exchange')
    await expect(page.getByText(/1 EUR ≈ [\d.]+ GBP/)).toBeVisible()
  })

  test('card shows the seeded card and its spend feed', async ({ page }) => {
    await page.goto('/card')
    await expect(page.getByRole('button', { name: 'Freeze' })).toBeVisible()
    await expect(page.getByText('Spend feed')).toBeVisible()
  })

  test('P2P transfer alice -> bob settles and appears in Activity', async ({ page, request }) => {
    const problems = collectProblems(page)
    const bobWallet = await walletIdOf(request, 'bob@demo.io')
    const note = `e2e ${Date.now()}`

    await page.goto('/transfer')
    await page.getByPlaceholder('00000000-0000-0000-0000-000000000000').fill(bobWallet)
    await page.getByRole('button', { name: 'Continue' }).click()
    await page.getByPlaceholder('0.00').fill('1.00')
    await page.getByPlaceholder("What's it for?").fill(note)
    await page.getByRole('button', { name: 'Review' }).click()
    await page.getByRole('button', { name: 'Confirm & send' }).click()

    // Eventual consistency made visible: accepted as pending, then settled by the saga.
    await expect(page.getByText(/Sent - pending|COMPLETED/)).toBeVisible()
    await page.goto('/activity')
    await expect(page.getByText(note)).toBeVisible()
    await expect(
      page.locator('a', { hasText: note }).getByText('COMPLETED'),
    ).toBeVisible({ timeout: 30_000 })
    expect(problems).toEqual([])
  })

  test('log out returns to the login screen', async ({ page }) => {
    await page.getByRole('button', { name: 'Log out' }).click()
    await expect(page.getByRole('button', { name: 'Log in' })).toBeVisible()
  })
})
