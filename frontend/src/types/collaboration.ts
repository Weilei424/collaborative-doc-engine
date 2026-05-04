export type OperationType =
  | 'INSERT_TEXT'
  | 'DELETE_RANGE'
  | 'FORMAT_RANGE'
  | 'SPLIT_BLOCK'
  | 'MERGE_BLOCK'
  | 'SET_BLOCK_TYPE'
  | 'NO_OP'

export interface SubmitOperationRequest {
  operationId: string
  clientSessionId: string
  baseVersion: number
  operationType: OperationType
  payload: unknown
}

export interface AcceptedOperationResponse {
  operationId: string
  documentId: string
  actorUserId: string
  clientSessionId: string
  serverVersion: number
  operationType: OperationType
  transformedPayload: unknown
}

export interface SessionMember {
  sessionId: string
  documentId: string
  userId: string
  username: string
  joinedAt: string
  lastSeenAt: string
}

export interface SessionSnapshot {
  documentId: string
  sessions: SessionMember[]
}

export interface PresenceEvent {
  userId: string
  username: string
  type: string
  data: unknown
}

export interface OperationErrorPayload {
  error: string
  operationId: string
  currentServerVersion: number
}
