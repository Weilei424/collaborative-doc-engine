import { useState, useEffect, useCallback } from 'react'
import { useAuth } from '../contexts/AuthContext'
import { documentsApi } from '../api/documents'
import type { Document, DocumentScope } from '../types/document'
import { DocumentCard } from '../components/DocumentCard'
import { CreateDocumentModal } from '../components/CreateDocumentModal'

const TABS: { label: string; scope: DocumentScope }[] = [
  { label: 'Mine', scope: 'owned' },
  { label: 'Shared with me', scope: 'shared' },
  { label: 'Public', scope: 'public' },
]

export function DashboardPage() {
  const { user, logout } = useAuth()
  const [activeScope, setActiveScope] = useState<DocumentScope>('owned')
  const [docs, setDocs] = useState<Document[]>([])
  const [query, setQuery] = useState('')
  const [debouncedQuery, setDebouncedQuery] = useState('')
  const [page, setPage] = useState(0)
  const [totalPages, setTotalPages] = useState(0)
  const [loading, setLoading] = useState(false)
  const [showCreate, setShowCreate] = useState(false)

  useEffect(() => {
    const t = setTimeout(() => {
      setDebouncedQuery(query)
      setPage(0)
    }, 300)
    return () => clearTimeout(t)
  }, [query])

  const fetchDocs = useCallback(async () => {
    setLoading(true)
    try {
      const result = await documentsApi.list(activeScope, { query: debouncedQuery || undefined, page, size: 20 })
      setDocs(result.items)
      setTotalPages(result.totalPages)
    } finally {
      setLoading(false)
    }
  }, [activeScope, debouncedQuery, page])

  useEffect(() => { fetchDocs() }, [fetchDocs])

  function handleTabChange(scope: DocumentScope) {
    setActiveScope(scope)
    setPage(0)
  }

  return (
    <div className="min-h-screen bg-gray-50">
      <header className="bg-white border-b px-6 py-4 flex justify-between items-center">
        <h1 className="text-xl font-bold">Documents</h1>
        <div className="flex items-center gap-4">
          <span className="text-sm text-gray-600">{user?.username}</span>
          <button onClick={() => setShowCreate(true)}
            className="px-4 py-2 bg-blue-600 text-white text-sm rounded hover:bg-blue-700">
            New document
          </button>
          <button onClick={logout} className="text-sm text-red-500 hover:underline">Sign out</button>
        </div>
      </header>

      <main className="max-w-4xl mx-auto px-6 py-8">
        <div className="flex gap-1 mb-6 border-b">
          {TABS.map(({ label, scope }) => (
            <button key={scope} onClick={() => handleTabChange(scope)}
              className={`px-4 py-2 text-sm font-medium border-b-2 -mb-px transition-colors ${
                activeScope === scope
                  ? 'border-blue-600 text-blue-600'
                  : 'border-transparent text-gray-500 hover:text-gray-700'
              }`}>
              {label}
            </button>
          ))}
        </div>

        <div className="mb-4">
          <input
            className="w-full border rounded px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
            placeholder="Search by title…"
            value={query}
            onChange={e => setQuery(e.target.value)}
          />
        </div>

        {loading ? (
          <p className="text-center text-gray-400 py-12">Loading…</p>
        ) : docs.length === 0 ? (
          <p className="text-center text-gray-400 py-12">No documents found</p>
        ) : (
          <div className="space-y-3">
            {docs.map(doc => (
              <DocumentCard key={doc.id} document={doc} onDeleted={fetchDocs} />
            ))}
          </div>
        )}

        {totalPages > 1 && (
          <div className="flex justify-center gap-2 mt-6">
            <button onClick={() => setPage(p => Math.max(0, p - 1))} disabled={page === 0}
              className="px-3 py-1 text-sm border rounded disabled:opacity-40">Previous</button>
            <span className="px-3 py-1 text-sm text-gray-500">{page + 1} / {totalPages}</span>
            <button onClick={() => setPage(p => Math.min(totalPages - 1, p + 1))} disabled={page >= totalPages - 1}
              className="px-3 py-1 text-sm border rounded disabled:opacity-40">Next</button>
          </div>
        )}
      </main>

      {showCreate && (
        <CreateDocumentModal
          onCreated={() => { setShowCreate(false); fetchDocs() }}
          onClose={() => setShowCreate(false)}
        />
      )}
    </div>
  )
}
