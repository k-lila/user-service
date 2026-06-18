import { afterEach, describe, expect, it, vi } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '../test/server'
import { login, logout, register } from './authClient'

describe('authClient.login', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('inicia o fluxo OAuth2 do gateway via redirect do browser', () => {
    const location = { href: '' } as Location
    vi.stubGlobal('location', location)

    login()

    expect(location.href).toBe('/oauth2/authorization/gateway-client')
  })
})

describe('authClient.logout', () => {
  afterEach(() => {
    document
      .querySelectorAll('form[action="/logout"]')
      .forEach((f) => f.remove())
  })

  it('faz POST top-level /logout via form com _csrf lido do cookie', () => {
    const submitSpy = vi
      .spyOn(HTMLFormElement.prototype, 'submit')
      .mockImplementation(() => {})
    document.cookie = 'XSRF-TOKEN=tok-123'

    logout()

    expect(submitSpy).toHaveBeenCalledOnce()
    const form = document.querySelector<HTMLFormElement>(
      'form[action="/logout"]'
    )
    expect(form).not.toBeNull()
    expect(form!.method).toBe('post')
    const csrf = form!.querySelector<HTMLInputElement>('input[name="_csrf"]')
    expect(csrf).not.toBeNull()
    expect(csrf!.type).toBe('hidden')
    expect(csrf!.value).toBe('tok-123')

    submitSpy.mockRestore()
  })

  it('envia _csrf vazio quando não há cookie XSRF-TOKEN', () => {
    const submitSpy = vi
      .spyOn(HTMLFormElement.prototype, 'submit')
      .mockImplementation(() => {})

    logout()

    const csrf = document.querySelector<HTMLInputElement>(
      'form[action="/logout"] input[name="_csrf"]'
    )
    expect(csrf!.value).toBe('')

    submitSpy.mockRestore()
  })
})

describe('authClient.register', () => {
  it('faz POST /v1/users/register e retorna o usuário criado', async () => {
    const user = await register({
      name: 'Ana Souza',
      email: 'ana@example.com',
      password: 'secret123',
      termsAccepted: true,
    })

    expect(user.email).toBe('ana@example.com')
    expect(user.id).toBeTruthy()
  })

  it('anexa o header X-XSRF-TOKEN a partir do cookie (CSRF end-to-end)', async () => {
    document.cookie = 'XSRF-TOKEN=csrf-abc'
    let received: string | null = null
    server.use(
      http.post('/v1/users/register', ({ request }) => {
        received = request.headers.get('x-xsrf-token')
        return HttpResponse.json({}, { status: 201 })
      })
    )

    await register({ name: 'A', email: 'a@b.com', password: 'x', termsAccepted: true })

    expect(received).toBe('csrf-abc')
  })

  it('rejeita quando o registro falha (ex.: e-mail duplicado)', async () => {
    server.use(
      http.post('/v1/users/register', () =>
        HttpResponse.json({ message: 'duplicado' }, { status: 409 })
      )
    )

    await expect(
      register({ name: 'A', email: 'a@b.com', password: 'x', termsAccepted: true })
    ).rejects.toThrow()
  })
})
