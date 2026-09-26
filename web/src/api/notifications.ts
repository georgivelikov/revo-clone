import { apiPost } from './http'

// Mirrors notification-service's TicketResponse record. The ticket is single-use and expires
// in expiresInSeconds (30s per plans/phase-03-payments-and-ui.md) - it buys one WebSocket
// handshake, never a page reload's worth of reconnects.
export interface WebSocketTicket {
  ticket: string
  expiresInSeconds: number
}

// POST, not GET, because minting a ticket has a side effect (a Redis write) that must never be
// cached or replayed from a browser history entry - see notification-service's
// NotificationController for the same reasoning. Goes through the gateway like every other
// HTTP call, not straight to notification-service: GatewaySecurityConfig is explicit that CORS
// is configured in exactly one place and no other service may add it, so an HTTP request that
// bypassed the gateway would be blocked by the browser's own CORS check before it ever reached
// notification-service. The WebSocket handshake itself is different - see notificationSocket.ts.
export function fetchWebSocketTicket(accessToken: string): Promise<WebSocketTicket> {
  return apiPost<WebSocketTicket>('/api/notifications/ticket', accessToken, undefined)
}
