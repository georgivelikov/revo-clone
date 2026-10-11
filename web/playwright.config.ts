import { defineConfig } from '@playwright/test'

// Runs against an already-running compose stack (`..\start.ps1`), not a dev server: the point is
// to exercise the real gateway, Keycloak and Kafka-fed read models through a real browser.
// The same suite runs once per browser origin, because the page may be opened on either host and
// each must pass CORS + OIDC (the bug that started this: login worked on localhost, then the
// first API call was blocked).
//
// Uses the system Chrome (`channel: 'chrome'`) so no browser download is needed.
export default defineConfig({
  testDir: './e2e',
  timeout: 60_000,
  expect: { timeout: 15_000 },
  fullyParallel: false,
  workers: 1,
  reporter: [['list']],
  use: {
    channel: 'chrome',
    viewport: { width: 430, height: 900 },
    trace: 'retain-on-failure',
  },
  projects: [
    { name: 'localhost', use: { baseURL: 'http://localhost:5173' } },
    { name: '127.0.0.1', use: { baseURL: 'http://127.0.0.1:5173' } },
  ],
})
