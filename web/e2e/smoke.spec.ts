import { expect, test } from '@playwright/test'
import { collectProblems, login } from './support'

// The test that would have caught the CORS mismatch: runs once per browser origin (see
// playwright.config.ts), logs in for real and requires Home to load with no failed API call.
test('login lands on Home with balances and no failed requests', async ({ page }) => {
  const problems = collectProblems(page)

  await login(page)

  await expect(page.getByText('Alice Demo')).toBeVisible()
  await expect(page.getByText('Total balance')).toBeVisible()
  await expect(page.getByText('Recent activity')).toBeVisible()
  await expect(page.getByText('Could not load your accounts.')).toHaveCount(0)
  // The notification WebSocket connects on load; a rejected origin shows a reconnecting banner.
  await expect(page.getByText(/reconnecting/i)).toHaveCount(0)
  expect(problems).toEqual([])
})

test('unauthenticated visit shows the login screen', async ({ page }) => {
  await page.goto('/')
  await expect(page.getByRole('button', { name: 'Log in' })).toBeVisible()
})
