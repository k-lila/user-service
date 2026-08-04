import { describe, expect, it } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router'
import { http, HttpResponse } from 'msw'
import { server } from '../test/server'
import { fakeUser } from '../test/handlers'
import { makeQueryClient } from '../test/utils'
import { RegisterBox } from './RegisterBox'

// ADR-019: sucesso e "Voltar" navegam para / (não /login — pertence ao IdP).
function renderRegisterBox() {
  return render(
    <QueryClientProvider client={makeQueryClient()}>
      <MemoryRouter initialEntries={['/register']}>
        <Routes>
          <Route path="/register" element={<RegisterBox />} />
          <Route path="/" element={<div>LOGIN PAGE</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
  )
}

describe('RegisterBox', () => {
  it('renderiza os campos nome, email, senha e o consentimento', () => {
    renderRegisterBox()
    expect(screen.getByPlaceholderText('Nome')).toBeInTheDocument()
    expect(screen.getByPlaceholderText('Email')).toBeInTheDocument()
    expect(screen.getByPlaceholderText('Senha')).toBeInTheDocument()
    expect(screen.getByRole('checkbox')).toBeInTheDocument()
  })

  it('mantém "Criar conta" desabilitado até aceitar os termos', async () => {
    renderRegisterBox()
    expect(screen.getByRole('button', { name: 'Criar conta' })).toBeDisabled()

    await userEvent.click(screen.getByRole('checkbox'))

    expect(screen.getByRole('button', { name: 'Criar conta' })).toBeEnabled()
  })

  it('submete os dados com termsAccepted e, no sucesso, navega para /', async () => {
    let body: unknown = null
    server.use(
      http.post('/v1/users/register', async ({ request }) => {
        body = await request.json()
        return HttpResponse.json(fakeUser, { status: 201 })
      })
    )
    renderRegisterBox()

    await userEvent.type(screen.getByPlaceholderText('Nome'), 'Ana Souza')
    await userEvent.type(screen.getByPlaceholderText('Email'), 'ana@example.com')
    await userEvent.type(screen.getByPlaceholderText('Senha'), 'secret123')
    await userEvent.click(screen.getByRole('checkbox'))
    await userEvent.click(screen.getByRole('button', { name: 'Criar conta' }))

    await waitFor(() =>
      expect(screen.getByText('LOGIN PAGE')).toBeInTheDocument()
    )
    expect(body).toEqual({
      name: 'Ana Souza',
      email: 'ana@example.com',
      password: 'secret123',
      termsAccepted: true,
    })
  })

  // AC-10: e-mail duplicado (409) exibe mensagem específica, não "Dados inválidos!".
  it('exibe "E-mail já cadastrado." quando o registro retorna 409', async () => {
    server.use(
      http.post('/v1/users/register', () =>
        HttpResponse.json({ message: 'duplicado' }, { status: 409 })
      )
    )
    renderRegisterBox()

    await userEvent.type(screen.getByPlaceholderText('Email'), 'a@b.com')
    await userEvent.click(screen.getByRole('checkbox'))
    await userEvent.click(screen.getByRole('button', { name: 'Criar conta' }))

    expect(await screen.findByText('E-mail já cadastrado.')).toBeInTheDocument()
  })

  // AC-11: rate limit (429) exibe mensagem específica.
  it('exibe "Muitas tentativas. Tente novamente mais tarde." quando o registro retorna 429', async () => {
    server.use(
      http.post('/v1/users/register', () =>
        HttpResponse.json({ message: 'rate limit' }, { status: 429 })
      )
    )
    renderRegisterBox()

    await userEvent.type(screen.getByPlaceholderText('Email'), 'a@b.com')
    await userEvent.click(screen.getByRole('checkbox'))
    await userEvent.click(screen.getByRole('button', { name: 'Criar conta' }))

    expect(
      await screen.findByText('Muitas tentativas. Tente novamente mais tarde.')
    ).toBeInTheDocument()
  })

  // AC-12: outros erros (ex.: 400) exibem o fallback genérico.
  it('exibe "Dados inválidos!" para erros que não são 409 nem 429 (ex.: 400)', async () => {
    server.use(
      http.post('/v1/users/register', () =>
        HttpResponse.json({ message: 'bad request' }, { status: 400 })
      )
    )
    renderRegisterBox()

    await userEvent.type(screen.getByPlaceholderText('Email'), 'a@b.com')
    await userEvent.click(screen.getByRole('checkbox'))
    await userEvent.click(screen.getByRole('button', { name: 'Criar conta' }))

    expect(await screen.findByText('Dados inválidos!')).toBeInTheDocument()
  })

  it('"Voltar" navega para / sem registrar', async () => {
    renderRegisterBox()
    await userEvent.click(screen.getByRole('button', { name: 'Voltar' }))
    expect(screen.getByText('LOGIN PAGE')).toBeInTheDocument()
  })
})
