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
  // The WebSocket handshake only - see notificationSocket.ts. Unlike every other endpoint,
  // this one bypasses the gateway on purpose: WS origin-checking is a different mechanism from
  // HTTP CORS (which the gateway owns exclusively - GatewaySecurityConfig), configured instead
  // on notification-service itself via WS_ALLOWED_ORIGINS.
  notificationBaseUrl: readEnv('VITE_NOTIFICATION_BASE_URL'),
}
