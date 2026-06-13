import { describe, expect, it } from 'vitest'
import { screen } from '@testing-library/react'
import { renderWithProviders } from '../test/utils'
import { Dashboard } from './Dashboard'

describe('Dashboard page', () => {
  it('renderiza a Navbar e o perfil do usuário logado', async () => {
    renderWithProviders(<Dashboard />)
    expect(screen.getByText('Usuário autenticado')).toBeInTheDocument()
    expect(await screen.findByText('Ana Souza')).toBeInTheDocument()
  })
})
