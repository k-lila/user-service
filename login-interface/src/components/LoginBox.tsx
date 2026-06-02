import { useNavigate } from 'react-router'
import { login } from '../api/authClient'

export default function LoginBox() {
  const navigate = useNavigate()

  return (
    <div className="bg-white p-8 rounded-lg shadow-md w-80">
      <h2 className="text-2xl font-semibold text-center mb-6">Login</h2>
      <div className="flex flex-col gap-4">
        <p className="text-gray-500 text-sm text-center">
          Você será redirecionado para a autenticação segura.
        </p>
        <button
          type="button"
          onClick={() => login()}
          className="bg-blue-600 text-white rounded-md py-2 hover:bg-blue-700 cursor-pointer transition"
        >
          Entrar
        </button>
        <button
          type="button"
          className="bg-green-600 text-white rounded-md py-2 hover:bg-green-700 cursor-pointer transition"
          onClick={() => navigate('/register')}
        >
          Registrar
        </button>
      </div>
    </div>
  )
}
