import { describe, expect, it } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router'
import { http, HttpResponse } from 'msw'
import { server } from '../test/server'
import { makeQueryClient } from '../test/utils'
import { Login } from './Login'

// ADR-019: página Login mora em / (não /login — o path /login pertence ao IdP).
function renderLoginRoute() {
  return render(
    <QueryClientProvider client={makeQueryClient()}>
      <MemoryRouter initialEntries={['/']}>
        <Routes>
          <Route path="/" element={<Login />} />
          <Route path="/dashboard" element={<div>DASHBOARD</div>} />
          <Route path="/register" element={<div>REGISTER</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
  )
}

describe('Login page', () => {
  it('exibe o LoginBox quando não autenticado (401)', async () => {
    server.use(
      http.get('/v1/users/me', () => new HttpResponse(null, { status: 401 }))
    )
    renderLoginRoute()
    expect(
      await screen.findByRole('button', { name: 'Entrar' })
    ).toBeInTheDocument()
  })

  it('redireciona para /dashboard quando já autenticado', async () => {
    renderLoginRoute()
    await waitFor(() => expect(screen.getByText('DASHBOARD')).toBeInTheDocument())
  })
})
