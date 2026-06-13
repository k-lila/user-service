import { afterEach, describe, expect, it } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '../test/server'
import { makeQueryClient } from '../test/utils'
import { Router } from './router'

// Router usa BrowserRouter; controlamos a rota inicial via history do jsdom.
function renderAt(path: string) {
  window.history.pushState({}, '', path)
  return render(
    <QueryClientProvider client={makeQueryClient()}>
      <Router />
    </QueryClientProvider>
  )
}

describe('Router (integração)', () => {
  afterEach(() => window.history.pushState({}, '', '/'))

  it('"/" redireciona para /login (mostra o LoginBox quando não autenticado)', async () => {
    server.use(
      http.get('/v1/users/me', () => new HttpResponse(null, { status: 401 }))
    )
    renderAt('/')
    expect(
      await screen.findByRole('button', { name: 'Entrar' })
    ).toBeInTheDocument()
  })

  it('/dashboard autenticado renderiza o perfil', async () => {
    renderAt('/dashboard')
    expect(await screen.findByText('Ana Souza')).toBeInTheDocument()
  })

  it('/dashboard sem sessão (401) redireciona para /login', async () => {
    server.use(
      http.get('/v1/users/me', () => new HttpResponse(null, { status: 401 }))
    )
    renderAt('/dashboard')
    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Entrar' })).toBeInTheDocument()
    )
  })
})
