import { BrowserRouter, Route, Routes } from 'react-router'
import { Login } from '../pages/Login'
import { Dashboard } from '../pages/Dashboard'
import { ProtectedLayout } from '../components/ProtectedLayout'
import { Register } from '../pages/Register'

// ADR-019: /login pertence ao IdP (authorization-server). O SPA mora em /.
// <Login/> reside diretamente em / — sem Navigate intermediário.
export function Router() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<Login />} />
        <Route element={<ProtectedLayout />}>
          <Route path="/dashboard" element={<Dashboard />} />
        </Route>
        <Route path="/register" element={<Register />} />
      </Routes>
    </BrowserRouter>
  )
}
