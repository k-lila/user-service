import { describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import LoginBox from './LoginBox'
import { login } from '../api/authClient'

vi.mock('../api/authClient', () => ({ login: vi.fn() }))

function renderLoginBox() {
  return render(
    <MemoryRouter initialEntries={['/login']}>
      <Routes>
        <Route path="/login" element={<LoginBox />} />
        <Route path="/register" element={<div>REGISTER PAGE</div>} />
      </Routes>
    </MemoryRouter>
  )
}

describe('LoginBox', () => {
  it('renderiza os botões Entrar e Registrar', () => {
    renderLoginBox()
    expect(screen.getByRole('button', { name: 'Entrar' })).toBeInTheDocument()
    expect(
      screen.getByRole('button', { name: 'Registrar' })
    ).toBeInTheDocument()
  })

  it('"Entrar" inicia o fluxo OAuth2 do gateway (login())', async () => {
    renderLoginBox()
    await userEvent.click(screen.getByRole('button', { name: 'Entrar' }))
    expect(login).toHaveBeenCalledOnce()
  })

  it('"Registrar" navega para /register', async () => {
    renderLoginBox()
    await userEvent.click(screen.getByRole('button', { name: 'Registrar' }))
    expect(screen.getByText('REGISTER PAGE')).toBeInTheDocument()
  })
})
