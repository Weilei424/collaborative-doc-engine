import { useNavigate } from 'react-router-dom'
import type { Document } from '../types/document'
import { documentsApi } from '../api/documents'
import { useToast } from '../contexts/ToastContext'

interface Props {
  document: Document
  onDeleted: (id: string) => void
  onDeleteFailed: () => void
}

const VISIBILITY_COLORS: Record<string, string> = {
  PRIVATE: 'bg-gray-100 text-gray-600',
  SHARED: 'bg-blue-100 text-blue-700',
  PUBLIC: 'bg-green-100 text-green-700',
}

export function DocumentCard({ document: doc, onDeleted, onDeleteFailed }: Props) {
  const navigate = useNavigate()
  const { addToast } = useToast()

  async function handleDelete(e: React.MouseEvent) {
    e.stopPropagation()
    if (!confirm(`Delete "${doc.title}"?`)) return
    onDeleted(doc.id)
    try {
      await documentsApi.delete(doc.id)
    } catch {
      onDeleteFailed()
      addToast('Failed to delete document', 'error')
    }
  }

  return (
    <div
      className="p-4 bg-white border rounded-lg cursor-pointer hover:shadow-md transition-shadow"
      onClick={() => navigate(`/documents/${doc.id}`)}
    >
      <div className="flex justify-between items-start">
        <div className="flex-1 min-w-0">
          <h3 className="font-semibold truncate">{doc.title}</h3>
          <p className="text-sm text-gray-500 mt-1">
            {doc.owner.username} - {new Date(doc.updatedAt).toLocaleDateString()}
          </p>
        </div>
        <div className="flex items-center gap-2 ml-4 shrink-0">
          <span className={`text-xs px-2 py-0.5 rounded-full font-medium ${VISIBILITY_COLORS[doc.visibility]}`}>
            {doc.visibility}
          </span>
          {doc.currentUserPermission === 'OWNER' && (
            <>
              <button
                onClick={e => { e.stopPropagation(); navigate(`/documents/${doc.id}/settings`) }}
                className="text-xs text-gray-500 hover:text-gray-700 px-2 py-1 rounded hover:bg-gray-100"
              >
                Settings
              </button>
              <button
                onClick={handleDelete}
                className="text-xs text-red-500 hover:text-red-700 px-2 py-1 rounded hover:bg-red-50"
              >
                Delete
              </button>
            </>
          )}
        </div>
      </div>
    </div>
  )
}
