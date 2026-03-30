import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import { AuthProvider } from './contexts/AuthContext'
import { ToastProvider } from './contexts/ToastContext'
import { PrivateRoute } from './components/PrivateRoute'
import { ErrorBoundary } from './components/ErrorBoundary'
import { LoginPage } from './pages/LoginPage'
import { RegisterPage } from './pages/RegisterPage'
import { DashboardPage } from './pages/DashboardPage'
import { DocumentSettingsPage } from './pages/DocumentSettingsPage'
import { EditorPage } from './pages/EditorPage'

function App() {
  return (
    <BrowserRouter>
      <ToastProvider>
        <AuthProvider>
          <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route path="/register" element={<RegisterPage />} />
          <Route
            path="/"
            element={
              <PrivateRoute>
                <ErrorBoundary><DashboardPage /></ErrorBoundary>
              </PrivateRoute>
            }
          />
          <Route path="/documents/:id/settings" element={
            <PrivateRoute><ErrorBoundary><DocumentSettingsPage /></ErrorBoundary></PrivateRoute>
          } />
          <Route path="/documents/:id" element={
            <PrivateRoute><ErrorBoundary><EditorPage /></ErrorBoundary></PrivateRoute>
          } />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
        </AuthProvider>
      </ToastProvider>
    </BrowserRouter>
  )
}

export default App
