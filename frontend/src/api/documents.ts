import { apiClient } from './client'
import type { Document, DocumentPage, DocumentScope, Visibility } from '../types/document'

export interface CreateDocumentBody { title: string; visibility: Visibility }
export interface UpdateDocumentBody { title?: string; visibility?: Visibility }

export const documentsApi = {
  list: (scope: DocumentScope, params?: { query?: string; page?: number; size?: number }) =>
    apiClient.get<DocumentPage>('/documents', { params: { scope, ...params } }).then(r => r.data),

  get: (id: string) =>
    apiClient.get<Document>(`/documents/${id}`).then(r => r.data),

  create: (body: CreateDocumentBody) =>
    apiClient.post<Document>('/documents', body).then(r => r.data),

  update: (id: string, body: UpdateDocumentBody) =>
    apiClient.put<Document>(`/documents/${id}`, body).then(r => r.data),

  delete: (id: string) =>
    apiClient.delete(`/documents/${id}`),
}
