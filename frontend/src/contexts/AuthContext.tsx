import { createContext, useContext, useState } from 'react'
import type { ReactNode } from 'react'
import { apiClient } from '../api/client'

interface AuthUser { userId: string; username: string; token: string }

interface AuthContextValue {
  user: AuthUser | null
  login: (username: string, password: string) => Promise<void>
  register: (username: string, email: string, password: string) => Promise<void>
  logout: () => void
}

const AuthContext = createContext<AuthContextValue | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const stored = localStorage.getItem('token')
  const storedUser = localStorage.getItem('auth_user')
  const [user, setUser] = useState<AuthUser | null>(
    stored && storedUser ? JSON.parse(storedUser) : null
  )

  async function login(username: string, password: string) {
    const { data } = await apiClient.post<AuthUser>('/auth/login', { username, password })
    localStorage.setItem('token', data.token)
    localStorage.setItem('auth_user', JSON.stringify(data))
    setUser(data)
  }

  async function register(username: string, email: string, password: string) {
    const { data } = await apiClient.post<AuthUser>('/auth/register', { username, email, password })
    localStorage.setItem('token', data.token)
    localStorage.setItem('auth_user', JSON.stringify(data))
    setUser(data)
  }

  function logout() {
    localStorage.removeItem('token')
    localStorage.removeItem('auth_user')
    setUser(null)
  }

  return (
    <AuthContext.Provider value={{ user, login, register, logout }}>
      {children}
    </AuthContext.Provider>
  )
}

export function useAuth() {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used within AuthProvider')
  return ctx
}
