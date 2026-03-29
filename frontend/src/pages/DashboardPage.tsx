import { useAuth } from '../contexts/AuthContext'

export function DashboardPage() {
  const { user, logout } = useAuth()
  return (
    <div className="min-h-screen bg-gray-50 p-8">
      <div className="max-w-2xl mx-auto">
        <div className="flex justify-between items-center mb-8">
          <h1 className="text-2xl font-bold">Dashboard</h1>
          <button
            onClick={logout}
            className="text-sm text-gray-600 hover:text-gray-900"
          >
            Sign out
          </button>
        </div>
        <p className="text-gray-600">Welcome, {user?.username}!</p>
      </div>
    </div>
  )
}
