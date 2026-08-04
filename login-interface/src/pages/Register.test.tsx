import { describe, expect, it } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router'
import { http, HttpResponse } from 'msw'
import { server } from '../test/server'
import { makeQueryClient } from '../test/utils'
import { Register } from './Register'

// ADR-019: destino de fallback é / (não /login — pertence ao IdP).
function renderRegisterRoute() {
  return render(
    <QueryClientProvider client={makeQueryClient()}>
      <MemoryRouter initialEntries={['/register']}>
        <Routes>
          <Route path="/register" element={<Register />} />
          <Route path="/dashboard" element={<div>DASHBOARD</div>} />
          <Route path="/" element={<div>LOGIN</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
  )
}

describe('Register page', () => {
  it('exibe o RegisterBox quando não autenticado (401)', async () => {
    server.use(
      http.get('/v1/users/me', () => new HttpResponse(null, { status: 401 }))
    )
    renderRegisterRoute()
    expect(await screen.findByPlaceholderText('Nome')).toBeInTheDocument()
  })

  it('redireciona para /dashboard quando já autenticado', async () => {
    renderRegisterRoute()
    await waitFor(() => expect(screen.getByText('DASHBOARD')).toBeInTheDocument())
  })
})
