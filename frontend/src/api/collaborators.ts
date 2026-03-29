import { apiClient } from './client'
import type { DocumentCollaborator, Permission } from '../types/document'

export const collaboratorsApi = {
  list: (documentId: string) =>
    apiClient.get<DocumentCollaborator[]>(`/documents/${documentId}/collaborators`).then(r => r.data),

  add: (documentId: string, userId: string, permission: Permission) =>
    apiClient.post(`/documents/${documentId}/collaborators`, { userId, permission }),

  update: (documentId: string, userId: string, permission: Permission) =>
    apiClient.put(`/documents/${documentId}/collaborators/${userId}`, { permission }),

  remove: (documentId: string, userId: string) =>
    apiClient.delete(`/documents/${documentId}/collaborators/${userId}`),

  transferOwnership: (documentId: string, userId: string) =>
    apiClient.put(`/documents/${documentId}/collaborators/owner`, { newOwnerId: userId }),
}
