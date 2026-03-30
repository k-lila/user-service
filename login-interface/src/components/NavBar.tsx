import { Link, useNavigate } from 'react-router'

export const Navbar = () => {
  const navigate = useNavigate()
  const logout = () => {
    localStorage.removeItem('token')
    navigate('/')
  }

  return (
    <nav className="bg-white shadow-md px-6 py-3 flex justify-between items-center absolute top-0 left-0 right-0">
      <h1 className="text-xl font-semibold">Usuário autenticado</h1>
      <div className="flex gap-4">
        <Link
          to="/"
          className="mx-2 px-3 py-1 rounded text-white cursor-pointer bg-blue-600 hover:bg-blue-700"
        >
          Login
        </Link>
        <Link
          to="/dashboard"
          className="mx-2 px-3 py-1 rounded text-white cursor-pointer bg-blue-600 hover:bg-blue-700"
        >
          Perfil
        </Link>
        <Link
          to="/register"
          className="mx-2 px-3 py-1 rounded text-white cursor-pointer bg-green-600 hover:bg-green-700"
        >
          Registro
        </Link>
        <button
          onClick={logout}
          className="mx-2 px-3 py-1 rounded text-white cursor-pointer bg-red-600 hover:bg-red-700"
        >
          Logout
        </button>
      </div>
    </nav>
  )
}
