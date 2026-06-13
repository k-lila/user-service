import { describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Navbar } from './NavBar'
import { logout } from '../api/authClient'

vi.mock('../api/authClient', () => ({ logout: vi.fn() }))

describe('Navbar', () => {
  it('renderiza o cabeçalho de usuário autenticado', () => {
    render(<Navbar />)
    expect(screen.getByText('Usuário autenticado')).toBeInTheDocument()
  })

  it('"Logout" dispara o logout RP-initiated (logout())', async () => {
    render(<Navbar />)
    await userEvent.click(screen.getByRole('button', { name: 'Logout' }))
    expect(logout).toHaveBeenCalledOnce()
  })
})
