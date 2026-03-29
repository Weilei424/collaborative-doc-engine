import { useState, useEffect, useCallback } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { documentsApi } from '../api/documents'
import { collaboratorsApi } from '../api/collaborators'
import type { Document, DocumentCollaborator, Permission, Visibility } from '../types/document'
import { CollaboratorRow } from '../components/CollaboratorRow'
import { UserSearchCombobox } from '../components/UserSearchCombobox'
import type { UserSummary } from '../api/users'

export function DocumentSettingsPage() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const [doc, setDoc] = useState<Document | null>(null)
  const [collaborators, setCollaborators] = useState<DocumentCollaborator[]>([])
  const [title, setTitle] = useState('')
  const [visibility, setVisibility] = useState<Visibility>('PRIVATE')
  const [saving, setSaving] = useState(false)
  const [addPermission, setAddPermission] = useState<Permission>('READ')
  const [error, setError] = useState<string | null>(null)

  const fetchDoc = useCallback(async () => {
    const d = await documentsApi.get(id!)
    setDoc(d)
    setTitle(d.title)
    setVisibility(d.visibility)
    setCollaborators(d.collaborators)
  }, [id])

  useEffect(() => { fetchDoc() }, [fetchDoc])

  async function handleSave() {
    setSaving(true)
    setError(null)
    try {
      await documentsApi.update(id!, { title, visibility })
    } catch {
      setError('Failed to save changes')
    } finally {
      setSaving(false)
    }
  }

  async function handleAddCollaborator(user: UserSummary) {
    setError(null)
    try {
      await collaboratorsApi.add(id!, user.userId, addPermission)
      await fetchDoc()
    } catch (err: unknown) {
      const code = (err as { response?: { data?: { code?: string } } })?.response?.data?.code
      if (code === 'COLLABORATOR_ALREADY_EXISTS') setError(`${user.username} is already a collaborator`)
      else setError('Failed to add collaborator')
    }
  }

  if (!doc) return <div className="p-8 text-gray-400">Loading…</div>

  return (
    <div className="min-h-screen bg-gray-50">
      <header className="bg-white border-b px-6 py-4 flex items-center gap-4">
        <button onClick={() => navigate('/')} className="text-sm text-blue-600 hover:underline">
          ← Back
        </button>
        <h1 className="text-lg font-semibold truncate">{doc.title} — Settings</h1>
      </header>

      <main className="max-w-2xl mx-auto px-6 py-8 space-y-8">
        <section>
          <h2 className="text-sm font-semibold uppercase text-gray-500 mb-3">Document</h2>
          <div className="bg-white border rounded-lg p-4 space-y-4">
            <div>
              <label className="block text-sm font-medium mb-1">Title</label>
              <input className="w-full border rounded px-3 py-2 text-sm"
                value={title} onChange={e => setTitle(e.target.value)} maxLength={255} />
            </div>
            <div>
              <label className="block text-sm font-medium mb-1">Visibility</label>
              <select className="border rounded px-3 py-2 text-sm"
                value={visibility} onChange={e => setVisibility(e.target.value as Visibility)}>
                <option value="PRIVATE">Private</option>
                <option value="SHARED">Shared</option>
                <option value="PUBLIC">Public</option>
              </select>
            </div>
            <button onClick={handleSave} disabled={saving}
              className="px-4 py-2 bg-blue-600 text-white text-sm rounded hover:bg-blue-700 disabled:opacity-50">
              {saving ? 'Saving…' : 'Save changes'}
            </button>
          </div>
        </section>

        <section>
          <h2 className="text-sm font-semibold uppercase text-gray-500 mb-3">Collaborators</h2>
          <div className="bg-white border rounded-lg p-4">
            {collaborators.length === 0 ? (
              <p className="text-sm text-gray-400 mb-4">No collaborators yet</p>
            ) : (
              <div className="mb-4">
                {collaborators.map(c => (
                  <CollaboratorRow key={c.userId} documentId={id!}
                    collaborator={c} onUpdated={fetchDoc} />
                ))}
              </div>
            )}

            <div className="pt-2 border-t">
              <p className="text-sm font-medium mb-2">Add collaborator</p>
              {error && <p className="text-xs text-red-600 mb-2">{error}</p>}
              <div className="flex gap-2">
                <div className="flex-1">
                  <UserSearchCombobox onSelect={handleAddCollaborator}
                    placeholder="Search by username or email…" />
                </div>
                <select className="border rounded px-2 py-2 text-sm"
                  value={addPermission}
                  onChange={e => setAddPermission(e.target.value as Permission)}>
                  <option value="READ">READ</option>
                  <option value="WRITE">WRITE</option>
                  <option value="ADMIN">ADMIN</option>
                </select>
              </div>
            </div>
          </div>
        </section>
      </main>
    </div>
  )
}
