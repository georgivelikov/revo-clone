import { expect, type APIRequestContext, type Page } from '@playwright/test'

// Demo users are seeded by tools/seed (README: alice@demo.io / demo). Local-dev values only;
// override for a different stack.
export const DEMO_PASSWORD = process.env.E2E_PASSWORD ?? 'demo'
const KEYCLOAK = process.env.E2E_KEYCLOAK ?? 'http://127.0.0.1:8180'
const GATEWAY = process.env.E2E_GATEWAY ?? 'http://127.0.0.1:8080'

/** Full login through the Keycloak hosted form, ending on the authenticated Home screen. */
export async function login(page: Page, email = 'alice@demo.io'): Promise<void> {
  await page.goto('/')
  await page.getByRole('button', { name: 'Log in' }).click()
  await page.locator('#username').fill(email)
  await page.locator('#password').fill(DEMO_PASSWORD)
  await page.locator('#kc-login').click()
  await expect(page.getByRole('button', { name: 'Log out' })).toBeVisible()
}

/** Failed API calls and console errors seen by `page`; assert it is empty at the end of a test. */
export function collectProblems(page: Page): string[] {
  const problems: string[] = []
  page.on('console', (message) => {
    if (message.type() === 'error') problems.push(`console: ${message.text()}`)
  })
  page.on('pageerror', (error) => problems.push(`pageerror: ${error.message}`))
  page.on('requestfailed', (request) =>
    problems.push(`request failed: ${request.method()} ${request.url()} ${request.failure()?.errorText}`),
  )
  page.on('response', (response) => {
    if (response.url().startsWith(GATEWAY) && response.status() >= 400) {
      problems.push(`HTTP ${response.status()}: ${response.request().method()} ${response.url()}`)
    }
  })
  return problems
}

interface AccountDto {
  id: string
  currency: string
  type: string
}

/** Another demo user's EUR wallet id, read via the API (a transfer needs a raw recipient id). */
export async function walletIdOf(
  request: APIRequestContext,
  email: string,
  currency = 'EUR',
): Promise<string> {
  const token = await request.post(`${KEYCLOAK}/realms/revolut/protocol/openid-connect/token`, {
    form: {
      grant_type: 'password',
      client_id: 'revolut-web',
      username: email,
      password: DEMO_PASSWORD,
      scope: 'openid',
    },
  })
  expect(token.ok(), `token for ${email}`).toBeTruthy()
  const { access_token } = (await token.json()) as { access_token: string }
  const accounts = await request.get(`${GATEWAY}/api/accounts/me`, {
    headers: { Authorization: `Bearer ${access_token}` },
  })
  expect(accounts.ok(), 'GET /api/accounts/me').toBeTruthy()
  const wallet = ((await accounts.json()) as AccountDto[]).find(
    (a) => a.type === 'WALLET' && a.currency === currency,
  )
  expect(wallet, `${email} ${currency} wallet`).toBeDefined()
  return wallet!.id
}
