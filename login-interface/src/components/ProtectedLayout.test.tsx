import { describe, expect, it } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router'
import { http, HttpResponse } from 'msw'
import { server } from '../test/server'
import { makeQueryClient } from '../test/utils'
import { ProtectedLayout } from './ProtectedLayout'

function renderProtected() {
  return render(
    <QueryClientProvider client={makeQueryClient()}>
      <MemoryRouter initialEntries={['/dashboard']}>
        <Routes>
          <Route element={<ProtectedLayout />}>
            <Route path="/dashboard" element={<div>PROTECTED CONTENT</div>} />
          </Route>
          <Route path="/login" element={<div>LOGIN PAGE</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
  )
}

describe('ProtectedLayout', () => {
  it('mostra "Carregando..." enquanto valida a sessão', () => {
    renderProtected()
    expect(screen.getByText('Carregando...')).toBeInTheDocument()
  })

  it('renderiza o conteúdo protegido (Outlet) quando autenticado', async () => {
    renderProtected()
    expect(await screen.findByText('PROTECTED CONTENT')).toBeInTheDocument()
  })

  it('redireciona para /login quando não autenticado (401)', async () => {
    server.use(
      http.get('/v1/users/me', () => new HttpResponse(null, { status: 401 }))
    )
    renderProtected()
    await waitFor(() =>
      expect(screen.getByText('LOGIN PAGE')).toBeInTheDocument()
    )
  })
})
