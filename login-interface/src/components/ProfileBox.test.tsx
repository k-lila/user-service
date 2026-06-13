import { describe, expect, it } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { server } from '../test/server'
import { renderWithProviders } from '../test/utils'
import { ProfileBox } from './ProfileBox'

describe('ProfileBox', () => {
  it('mostra estado de carregamento enquanto busca o usuário', () => {
    renderWithProviders(<ProfileBox />)
    expect(screen.getByText('Carregando usuário...')).toBeInTheDocument()
  })

  it('renderiza os dados do usuário logado', async () => {
    renderWithProviders(<ProfileBox />)
    expect(await screen.findByText('Ana Souza')).toBeInTheDocument()
    expect(screen.getByText('u-123')).toBeInTheDocument()
    expect(screen.getByText('ana@example.com')).toBeInTheDocument()
    expect(screen.getByText('2026-06-13')).toBeInTheDocument()
  })

  it('mostra "Usuário não encontrado" quando não autenticado (401)', async () => {
    server.use(
      http.get('/v1/users/me', () => new HttpResponse(null, { status: 401 }))
    )
    renderWithProviders(<ProfileBox />)
    await waitFor(() =>
      expect(screen.getByText('Usuário não encontrado')).toBeInTheDocument()
    )
  })
})
