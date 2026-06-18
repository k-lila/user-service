import { useState } from 'react'
import { useNavigate } from 'react-router'
import { useRegister } from '../hooks/useRegister'

export const RegisterBox = () => {
  const navigate = useNavigate()
  const [name, setName] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [termsAccepted, setTermsAccepted] = useState(false)
  const mutation = useRegister()

  const handleSubmit = (e: React.SyntheticEvent<HTMLFormElement>) => {
    e.preventDefault()
    mutation.mutate({ name, email, password, termsAccepted })
  }

  return (
    <div className="flex justify-center">
      <form
        onSubmit={handleSubmit}
        className="bg-white shadow-md rounded-lg p-8 w-96 space-y-4 flex flex-col"
      >
        <h2 className="text-2xl text-center font-semibold">Registrar</h2>
        <input
          placeholder="Nome"
          className="border border-gray-300 rounded-md p-2 focus:outline-none focus:ring-2 focus:ring-blue-500 mx-2 flex"
          value={name}
          onChange={(e) => setName(e.target.value)}
        />
        <input
          placeholder="Email"
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          className="border border-gray-300 rounded-md p-2 focus:outline-none focus:ring-2 focus:ring-blue-500 mx-2 flex"
        />
        <input
          type="password"
          placeholder="Senha"
          value={password}
          className="border border-gray-300 rounded-md p-2 focus:outline-none focus:ring-2 focus:ring-blue-500 mx-2 flex"
          onChange={(e) => setPassword(e.target.value)}
        />
        <label className="flex items-start gap-2 mx-2 text-sm text-gray-700">
          <input
            type="checkbox"
            className="mt-1"
            checked={termsAccepted}
            onChange={(e) => setTermsAccepted(e.target.checked)}
          />
          <span>
            Li e aceito os{' '}
            <a href="/terms" target="_blank" rel="noopener noreferrer" className="text-blue-600 underline">
              Termos de Serviço
            </a>{' '}
            e a{' '}
            <a href="/privacy" target="_blank" rel="noopener noreferrer" className="text-blue-600 underline">
              Política de Privacidade
            </a>
            .
          </span>
        </label>
        {mutation.isError && (
          <p className="text-red-500 text-center text-sm">Dados inválidos!</p>
        )}
        <button
          className="mx-2 px-3 py-2 rounded text-white cursor-pointer bg-green-600 hover:bg-green-700 disabled:bg-gray-400 disabled:cursor-not-allowed"
          type="submit"
          disabled={!termsAccepted}
        >
          Criar conta
        </button>
        <button
          className="mx-2 px-3 py-2 rounded text-white cursor-pointer bg-blue-600 hover:bg-blue-700"
          type="button"
          onClick={() => navigate('/login')}
        >
          Voltar
        </button>
      </form>
    </div>
  )
}
