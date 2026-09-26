import { Client, ReconnectionTimeMode } from '@stomp/stompjs'
import type { IMessage } from '@stomp/stompjs'
import { fetchWebSocketTicket } from '../api/notifications'
import { env } from '../config/env'
import type { ClientEvent } from './clientEvent'

// The literal "/user" segment is Spring's user-destination convention, not this user's id
// (see notification-service's WebSocketConfig: setUserDestinationPrefix + convertAndSendToUser).
// The server rewrites a subscription here into a session-specific queue behind the scenes and
// remembers the mapping from this session's principal (set during the ticket handshake) to it -
// subscribing to anything else receives nothing, silently.
const USER_QUEUE = '/user/queue/events'

const INITIAL_RECONNECT_DELAY_MS = 1_000
const MAX_RECONNECT_DELAY_MS = 30_000

function wsUrl(path: string, ticket: string): string {
  // env.notificationBaseUrl is http(s):// because it also serves the plain HTTP ticket
  // endpoint; the socket itself needs the ws(s) scheme.
  const base = env.notificationBaseUrl.replace(/^http/, 'ws')
  return `${base}${path}?ticket=${encodeURIComponent(ticket)}`
}

/**
 * One STOMP-over-WebSocket connection to notification-service, with reconnect/backoff.
 *
 * Ticket-based auth (plans/phase-03-payments-and-ui.md) means a fresh, single-use ticket has
 * to be minted right before *every* connection attempt, not just the first - a reconnect is a
 * brand new handshake, and the previous ticket was consumed (or has expired; its TTL is 30s)
 * long before a backed-off retry fires. `beforeConnect` is stompjs's hook for exactly this: it
 * runs before each attempt, including reconnects, and supports async work.
 *
 * `beforeConnect` must never throw or reject - `Client._connect` awaits it with no `.catch`,
 * so a rejection becomes an unhandled promise rejection that skips `onWebSocketClose` entirely
 * and silently kills the reconnect loop. If the ticket fetch fails (notification-service or the
 * network is down), this points the socket at a deliberately unusable URL instead: the
 * handshake fails at the WebSocket layer, which *does* flow into `onWebSocketClose` and the
 * library's own exponential backoff.
 */
export class NotificationSocket {
  private readonly client: Client

  constructor(getAccessToken: () => string | undefined, onEvent: (event: ClientEvent) => void) {
    this.client = new Client({
      reconnectDelay: INITIAL_RECONNECT_DELAY_MS,
      reconnectTimeMode: ReconnectionTimeMode.EXPONENTIAL,
      maxReconnectDelay: MAX_RECONNECT_DELAY_MS,
      beforeConnect: async (client) => {
        const accessToken = getAccessToken()
        if (!accessToken) {
          client.brokerURL = wsUrl('/ws', 'no-access-token')
          return
        }
        try {
          const issued = await fetchWebSocketTicket(accessToken)
          client.brokerURL = wsUrl('/ws', issued.ticket)
        } catch {
          client.brokerURL = wsUrl('/ws', 'ticket-fetch-failed')
        }
      },
      onConnect: () => {
        this.client.subscribe(USER_QUEUE, (message: IMessage) => {
          onEvent(JSON.parse(message.body) as ClientEvent)
        })
      },
    })
  }

  connect(): void {
    this.client.activate()
  }

  disconnect(): void {
    void this.client.deactivate()
  }
}
