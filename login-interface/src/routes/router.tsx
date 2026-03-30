import { BrowserRouter, Navigate, Route, Routes } from 'react-router'
import { Login } from '../pages/Login'
import { Dashboard } from '../pages/Dashboard'
import { ProtectedLayout } from '../components/ProtectedLayout'
import { Register } from '../pages/Register'

export function Router() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<Navigate to="/login" />} />
        <Route path="/login" element={<Login />} />
        <Route element={<ProtectedLayout />}>
          <Route path="/dashboard" element={<Dashboard />} />
        </Route>
        <Route path="register" element={<Register />} />
      </Routes>
    </BrowserRouter>
  )
}
