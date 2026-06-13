import { describe, expect, it } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '../test/server'
import { getCurrentUser } from './userClient'

describe('userClient.getCurrentUser', () => {
  it('retorna o usuário atual em 200 (GET /v1/users/me)', async () => {
    const user = await getCurrentUser()
    expect(user.email).toBe('ana@example.com')
    expect(user.id).toBe('u-123')
  })

  it('rejeita em 401 (não autenticado — fonte de verdade do estado de auth)', async () => {
    server.use(
      http.get('/v1/users/me', () => new HttpResponse(null, { status: 401 }))
    )
    await expect(getCurrentUser()).rejects.toThrow()
  })
})
