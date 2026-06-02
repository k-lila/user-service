import { Navigate } from 'react-router'
import LoginBox from '../components/LoginBox'
import { useCurrentUser } from '../hooks/useCurrentUser'

export const Login = () => {
  const { data: user, isLoading } = useCurrentUser()

  if (isLoading) {
    return <div className="text-center mt-10">Carregando...</div>
  }
  // Já autenticado: vai direto ao perfil.
  if (user) {
    return <Navigate to="/dashboard" replace />
  }
  return (
    <div className="min-h-screen flex items-center justify-center bg-gray-100">
      <LoginBox />
    </div>
  )
}
