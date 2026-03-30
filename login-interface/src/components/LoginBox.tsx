import { useState } from 'react'
import { useLogin } from '../hooks/useLogin'
import { useNavigate } from 'react-router'

export default function LoginBox() {
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const navigate = useNavigate()
  const mutation = useLogin()

  function handleSubmit(e: React.SyntheticEvent<HTMLFormElement>) {
    e.preventDefault()
    mutation.mutate({ email: email, password })
  }

  return (
    <div className="bg-white p-8 rounded-lg shadow-md w-80">
      <h2 className="text-2xl font-semibold text-center mb-6">Login</h2>
      <form className="flex flex-col gap-4" onSubmit={handleSubmit}>
        <input
          type="text"
          placeholder="Login"
          className="border border-gray-300 rounded-md p-2 focus:outline-none focus:ring-2 focus:ring-blue-500"
          value={email}
          onChange={(e) => setEmail(e.target.value)}
        />
        <input
          type="password"
          placeholder="Senha"
          className="border border-gray-300 rounded-md p-2 focus:outline-none focus:ring-2 focus:ring-blue-500"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
        />
        {mutation.isError && (
          <p className="text-red-500 text-sm">Dados inválidos!</p>
        )}
        <button
          type="submit"
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
      </form>
    </div>
  )
}
