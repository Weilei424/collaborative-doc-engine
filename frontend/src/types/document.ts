export type Visibility = 'PRIVATE' | 'SHARED' | 'PUBLIC'
export type Permission = 'READ' | 'WRITE' | 'ADMIN'
export type DocumentScope = 'owned' | 'shared' | 'public'

export interface DocumentOwner {
  id: string
  username: string
}

export interface DocumentCollaborator {
  userId: string
  username: string
  permission: Permission
}

export interface Document {
  id: string
  title: string
  content: string | null
  visibility: Visibility
  currentVersion: number
  owner: DocumentOwner
  collaborators: DocumentCollaborator[]
  currentUserPermission: Permission | null
  createdAt: string
  updatedAt: string
}

export interface DocumentPage {
  items: Document[]
  totalElements: number
  totalPages: number
  page: number
  size: number
}
