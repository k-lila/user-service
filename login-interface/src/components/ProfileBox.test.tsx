import { describe, expect, it } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { server } from '../test/server'
import { fakeUser } from '../test/handlers'
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
    expect(screen.getByText('Sim')).toBeInTheDocument()
  })

  it('mostra "Não" quando o e-mail não está verificado', async () => {
    server.use(
      http.get('/v1/users/me', () =>
        HttpResponse.json({ ...fakeUser, emailVerified: false })
      )
    )
    renderWithProviders(<ProfileBox />)
    expect(await screen.findByText('Não')).toBeInTheDocument()
  })

  it('oferece link para o Swagger UI em nova aba', async () => {
    renderWithProviders(<ProfileBox />)
    const link = await screen.findByRole('link', { name: /swagger/i })
    expect(link).toHaveAttribute('href', '/swagger-ui/index.html')
    expect(link).toHaveAttribute('target', '_blank')
    // Sem noopener a aba aberta recebe window.opener e pode navegar esta origem.
    expect(link).toHaveAttribute('rel', 'noopener noreferrer')
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
