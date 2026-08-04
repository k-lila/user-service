import { http, HttpResponse } from 'msw'
import type { UserResponse } from '../api/userClient'

export const fakeUser: UserResponse = {
  id: 'u-123',
  name: 'Ana Souza',
  email: 'ana@example.com',
  registrationDate: '2026-06-13',
  emailVerified: true,
}

// Handlers default = "feliz". Cada teste sobrescreve com server.use(...) para
// cenários 401/erro. Caminhos relativos resolvem contra a origin do jsdom.
export const handlers = [
  http.get('/v1/users/me', () => HttpResponse.json(fakeUser)),
  http.post('/v1/users/register', () =>
    HttpResponse.json(fakeUser, { status: 201 })
  ),
]
