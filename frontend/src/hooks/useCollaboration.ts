import { useCallback, useEffect, useRef, useState } from 'react'
import type { MutableRefObject } from 'react'
import { Client } from '@stomp/stompjs'
import type { StompSubscription } from '@stomp/stompjs'
import SockJS from 'sockjs-client'
import type {
  AcceptedOperationResponse,
  OperationErrorPayload,
  PresenceEvent,
  SessionSnapshot,
  SubmitOperationRequest,
} from '../types/collaboration'

interface Options {
  documentId: string
  token: string | null
  sessionId: string
  lastServerVersionRef: MutableRefObject<number>
  onOperation: (op: AcceptedOperationResponse) => void
  onSession: (snapshot: SessionSnapshot) => void
  onPresence: (event: PresenceEvent) => void
  onAccessRevoked: () => void
  onError: (payload: OperationErrorPayload) => void
}

export function useCollaboration({
  documentId,
  token,
  sessionId,
  lastServerVersionRef,
  onOperation,
  onSession,
  onPresence,
  onAccessRevoked,
  onError,
}: Options) {
  const clientRef = useRef<Client | null>(null)
  const [connected, setConnected] = useState(false)

  const submitOperation = useCallback(
    (req: SubmitOperationRequest) => {
      clientRef.current?.publish({
        destination: `/app/documents/${documentId}/operations.submit`,
        body: JSON.stringify(req),
      })
    },
    [documentId],
  )

  const updatePresence = useCallback(
    (data: unknown) => {
      clientRef.current?.publish({
        destination: `/app/documents/${documentId}/presence.update`,
        body: JSON.stringify({ sessionId, type: 'CURSOR', payload: data }),
      })
    },
    [documentId, sessionId],
  )

  // Callbacks (onOperation, onSession, onPresence, onAccessRevoked) are not in the
  // dependency array intentionally — reconnecting on every callback change would
  // break the connection. Callers MUST pass stable references (useCallback or setState).
  useEffect(() => {
    if (!token) return

    const userId = parseUserIdFromToken(token)

    const client = new Client({
      webSocketFactory: () => new SockJS(`http://${window.location.host}/ws?token=${token}`),
      reconnectDelay: 3000,
      onConnect: () => {
        setConnected(true)
        const subs: StompSubscription[] = []

        // Subscribe to catchup FIRST — the server triggers replay on this subscribe event,
        // so the subscription must exist before the server sends any catchup frames.
        subs.push(
          client.subscribe(
            `/user/queue/catchup.${documentId}`,
            msg => onOperation(JSON.parse(msg.body)),
            { 'X-Last-Server-Version': String(lastServerVersionRef.current) },
          ),
        )

        subs.push(
          client.subscribe(`/user/queue/errors.${documentId}`, msg => {
            try {
              onError(JSON.parse(msg.body))
            } catch {
              console.warn('[collab] Failed to parse error message:', msg.body)
            }
          }),
        )

        subs.push(
          client.subscribe(`/topic/documents/${documentId}/sessions`, msg =>
            onSession(JSON.parse(msg.body)),
          ),
        )
        subs.push(
          client.subscribe(`/topic/documents/${documentId}/operations`, msg =>
            onOperation(JSON.parse(msg.body)),
          ),
        )
        subs.push(
          client.subscribe(`/topic/documents/${documentId}/presence`, msg =>
            onPresence(JSON.parse(msg.body)),
          ),
        )
        if (userId) {
          subs.push(
            client.subscribe(
              `/topic/documents/${documentId}/access/${userId}`,
              () => onAccessRevoked(),
            ),
          )
        }

        client.publish({
          destination: `/app/documents/${documentId}/sessions.join`,
          body: JSON.stringify({ sessionId }),
        })
      },
      onDisconnect: () => setConnected(false),
    })

    clientRef.current = client
    client.activate()

    return () => {
      if (client.connected) {
        client.publish({
          destination: `/app/documents/${documentId}/sessions.leave`,
          body: JSON.stringify({ sessionId }),
        })
      }
      client.deactivate()
      setConnected(false)
      clientRef.current = null
    }
  }, [documentId, token])

  return { connected, submitOperation, updatePresence }
}

function parseUserIdFromToken(token: string): string | null {
  try {
    const payload = token.split('.')[1]
    const decoded = JSON.parse(atob(payload))
    return decoded.sub ?? null
  } catch {
    return null
  }
}
