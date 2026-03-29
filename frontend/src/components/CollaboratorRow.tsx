import type { DocumentCollaborator, Permission } from '../types/document'
import { collaboratorsApi } from '../api/collaborators'

const PERMISSIONS: Permission[] = ['READ', 'WRITE', 'ADMIN']

interface Props {
  documentId: string
  collaborator: DocumentCollaborator
  onUpdated: () => void
}

export function CollaboratorRow({ documentId, collaborator: collab, onUpdated }: Props) {
  async function handlePermissionChange(permission: Permission) {
    try {
      await collaboratorsApi.update(documentId, collab.userId, permission)
      onUpdated()
    } catch {
      alert('Failed to update permission')
    }
  }

  async function handleRemove() {
    if (!confirm(`Remove ${collab.username}?`)) return
    try {
      await collaboratorsApi.remove(documentId, collab.userId)
      onUpdated()
    } catch {
      alert('Failed to remove collaborator')
    }
  }

  return (
    <div className="flex items-center justify-between py-2 border-b last:border-0">
      <span className="text-sm font-medium">{collab.username}</span>
      <div className="flex items-center gap-3">
        <select
          className="text-sm border rounded px-2 py-1"
          value={collab.permission}
          onChange={e => handlePermissionChange(e.target.value as Permission)}
        >
          {PERMISSIONS.map(p => <option key={p} value={p}>{p}</option>)}
        </select>
        <button onClick={handleRemove}
          className="text-xs text-red-500 hover:text-red-700">
          Remove
        </button>
      </div>
    </div>
  )
}
