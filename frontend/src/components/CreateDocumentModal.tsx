import { useState } from 'react'
import type { FormEvent } from 'react'
import { documentsApi } from '../api/documents'
import type { Visibility } from '../types/document'

interface Props { onCreated: () => void; onClose: () => void }

export function CreateDocumentModal({ onCreated, onClose }: Props) {
  const [title, setTitle] = useState('')
  const [visibility, setVisibility] = useState<Visibility>('PRIVATE')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function handleSubmit(e: FormEvent) {
    e.preventDefault()
    setLoading(true)
    setError(null)
    try {
      await documentsApi.create({ title, visibility })
      onCreated()
    } catch (err: unknown) {
      const msg = (err as { response?: { data?: { message?: string } } })
        ?.response?.data?.message
      setError(msg ?? 'Failed to create document')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="fixed inset-0 bg-black/40 flex items-center justify-center z-50">
      <div className="bg-white rounded-lg shadow-xl w-full max-w-md p-6">
        <h2 className="text-lg font-semibold mb-4">New document</h2>
        {error && <p className="mb-3 text-sm text-red-600">{error}</p>}
        <form onSubmit={handleSubmit} className="space-y-4">
          <div>
            <label className="block text-sm font-medium mb-1">Title</label>
            <input
              className="w-full border rounded px-3 py-2 focus:outline-none focus:ring-2 focus:ring-blue-500"
              value={title} onChange={e => setTitle(e.target.value)} required maxLength={255}
              autoFocus
            />
          </div>
          <div>
            <label className="block text-sm font-medium mb-1">Visibility</label>
            <select
              className="w-full border rounded px-3 py-2"
              value={visibility} onChange={e => setVisibility(e.target.value as Visibility)}
            >
              <option value="PRIVATE">Private</option>
              <option value="SHARED">Shared</option>
              <option value="PUBLIC">Public</option>
            </select>
          </div>
          <div className="flex justify-end gap-3 pt-2">
            <button type="button" onClick={onClose}
              className="px-4 py-2 text-sm rounded border hover:bg-gray-50">
              Cancel
            </button>
            <button type="submit" disabled={loading}
              className="px-4 py-2 text-sm rounded bg-blue-600 text-white hover:bg-blue-700 disabled:opacity-50">
              {loading ? 'Creating…' : 'Create'}
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}
