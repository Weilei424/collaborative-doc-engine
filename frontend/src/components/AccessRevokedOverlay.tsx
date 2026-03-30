import { useNavigate } from 'react-router-dom'

export function AccessRevokedOverlay() {
  const navigate = useNavigate()
  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-labelledby="access-revoked-title"
      className="fixed inset-0 bg-black/60 flex items-center justify-center z-50"
    >
      <div className="bg-white rounded-lg shadow-xl p-8 max-w-sm text-center">
        <div className="text-4xl mb-4">🔒</div>
        <h2 id="access-revoked-title" className="text-lg font-semibold mb-2">Access removed</h2>
        <p className="text-sm text-gray-500 mb-6">
          Your access to this document has been removed by the owner.
        </p>
        <button
          onClick={() => navigate('/')}
          className="px-6 py-2 bg-blue-600 text-white rounded hover:bg-blue-700 text-sm"
        >
          Return to dashboard
        </button>
      </div>
    </div>
  )
}
