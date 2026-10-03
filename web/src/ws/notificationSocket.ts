import { Client, ReconnectionTimeMode } from '@stomp/stompjs'
import type { IMessage } from '@stomp/stompjs'
import { fetchWebSocketTicket } from '../api/notifications'
import { env } from '../config/env'
import type { ClientEvent } from './clientEvent'
import type { RateTickFrame } from './rateTick'

// The literal "/user" segment is Spring's user-destination convention, not this user's id
// (see notification-service's WebSocketConfig: setUserDestinationPrefix + convertAndSendToUser).
// The server rewrites a subscription here into a session-specific queue behind the scenes and
// remembers the mapping from this session's principal (set during the ticket handshake) to it -
// subscribing to anything else receives nothing, silently.
const USER_QUEUE = '/user/queue/events'

// Plain broadcast, unlike USER_QUEUE: every connected client subscribes to this same
// destination and the simple broker fans a push out to all of them, per
// RateTickBroadcaster/WebSocketConfig (plans/phase-04-fx-multicurrency.md task 9). A rate is
// nobody's secret, so no per-user routing is involved at all.
const RATE_TICKS_TOPIC = '/topic/rates'

const INITIAL_RECONNECT_DELAY_MS = 1_000
const MAX_RECONNECT_DELAY_MS = 30_000

/** task 09.6: "detect a dropped socket and show a subtle reconnecting indicator." `'connecting'`
 * covers both the first-ever handshake and every automatic retry after a drop - stompjs's own
 * `reconnectDelay`/`maxReconnectDelay` above already drive the retry loop; this just reports
 * which phase of it the UI is currently in. */
export type ConnectionStatus = 'connecting' | 'connected'

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

  constructor(
    getAccessToken: () => string | undefined,
    onEvent: (event: ClientEvent) => void,
    onRateTick?: (tick: RateTickFrame) => void,
    onStatusChange?: (status: ConnectionStatus) => void,
  ) {
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
        onStatusChange?.('connected')
        this.client.subscribe(USER_QUEUE, (message: IMessage) => {
          onEvent(JSON.parse(message.body) as ClientEvent)
        })
        if (onRateTick) {
          this.client.subscribe(RATE_TICKS_TOPIC, (message: IMessage) => {
            onRateTick(JSON.parse(message.body) as RateTickFrame)
          })
        }
      },
      // Fires on the very first handshake attempt too, so the indicator's default ('connecting')
      // state and this are consistent - but set explicitly anyway rather than relying on that
      // default, since a reconnect after a real drop is exactly the case worth being correct
      // about on purpose, not by coincidence.
      onWebSocketClose: () => onStatusChange?.('connecting'),
    })
  }

  connect(): void {
    this.client.activate()
  }

  disconnect(): void {
    void this.client.deactivate()
  }
}
