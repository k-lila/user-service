import { Navigate } from 'react-router'
import { RegisterBox } from '../components/RegisterBox'
import { useCurrentUser } from '../hooks/useCurrentUser'

export const Register = () => {
  const { data: user, isLoading } = useCurrentUser()

  if (isLoading) {
    return <div className="text-center mt-10">Carregando...</div>
  }
  // Já autenticado: não faz sentido registrar; vai ao perfil.
  if (user) {
    return <Navigate to="/dashboard" replace />
  }
  return (
    <div className="min-h-screen flex items-center justify-center bg-gray-100">
      <RegisterBox />
    </div>
  )
}
