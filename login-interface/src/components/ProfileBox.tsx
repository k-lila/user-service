import { useCurrentUser } from '../hooks/useCurrentUser'

export const ProfileBox = () => {
  const { data: user, isLoading } = useCurrentUser()

  if (isLoading) {
    return <div className="text-center mt-10">Carregando usuário...</div>
  }

  if (!user) {
    return <div className="text-center mt-10">Usuário não encontrado</div>
  }

  return (
    <div className="flex justify-center mt-10">
      <div className="bg-white shadow-md rounded-lg p-8 w-96">
        <h2 className="text-2xl font-semibold mb-6 text-center">
          Usuário Logado
        </h2>
        <div className="space-y-4">
          <div>
            <p className="text-gray-500 text-sm">ID</p>
            <p className="text-lg">{user.id}</p>
          </div>
          <div>
            <p className="text-gray-500 text-sm">Nome</p>
            <p className="text-lg">{user.name}</p>
          </div>
          <div>
            <p className="text-gray-500 text-sm">Email</p>
            <p className="text-lg">{user.email}</p>
          </div>
          <div>
            <p className="text-gray-500 text-sm">Data</p>
            <p className="text-lg">{user.registrationDate}</p>
          </div>
          <div>
            <p className="text-gray-500 text-sm">Verificado</p>
            <p className="text-lg">{user.emailVerified ? 'Sim' : 'Não'}</p>
          </div>
        </div>
        {/* ADR-020: /swagger-ui exige a sessão OAuth2 do BFF — quem está aqui já a tem. */}
        <a
          href="/swagger-ui/index.html"
          target="_blank"
          rel="noopener noreferrer"
          className="block mt-6 text-center px-3 py-2 rounded text-white bg-blue-600 hover:bg-blue-700"
        >
          Swagger UI
        </a>
      </div>
    </div>
  )
}
