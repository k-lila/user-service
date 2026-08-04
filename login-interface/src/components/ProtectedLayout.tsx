import { Navigate, Outlet } from 'react-router'
import { useCurrentUser } from '../hooks/useCurrentUser'

export const ProtectedLayout = () => {
  const { data: user, isLoading, isError } = useCurrentUser()

  if (isLoading) {
    return <div className="text-center mt-10">Carregando...</div>
  }
  // ADR-019: / é o novo lar de <Login/>; /login pertence ao IdP.
  if (isError || !user) {
    return <Navigate to="/" replace />
  }
  return <Outlet />
}
