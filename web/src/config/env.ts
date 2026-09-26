function readEnv(name: string): string {
  const value = import.meta.env[name]
  if (!value) {
    throw new Error(`Missing required environment variable: ${name}`)
  }
  return value
}

export const env = {
  oidcAuthority: readEnv('VITE_OIDC_AUTHORITY'),
  oidcClientId: readEnv('VITE_OIDC_CLIENT_ID'),
  apiBaseUrl: readEnv('VITE_API_BASE_URL'),
}
