import { describe, expect, it, vi } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { server } from '../test/server'
import { queryWrapper } from '../test/utils'
import { useRegister } from './useRegister'

const navigateMock = vi.fn()
vi.mock('react-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-router')>()
  return { ...actual, useNavigate: () => navigateMock }
})

describe('useRegister', () => {
  it('navega para /login no sucesso (registro não autentica)', async () => {
    const { result } = renderHook(() => useRegister(), {
      wrapper: queryWrapper(),
    })

    result.current.mutate({ name: 'A', email: 'a@b.com', password: 'x', termsAccepted: true })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(navigateMock).toHaveBeenCalledWith('/login')
  })

  it('expõe isError quando o registro falha', async () => {
    server.use(
      http.post('/v1/users/register', () =>
        HttpResponse.json({ message: 'duplicado' }, { status: 409 })
      )
    )

    const { result } = renderHook(() => useRegister(), {
      wrapper: queryWrapper(),
    })

    result.current.mutate({ name: 'A', email: 'a@b.com', password: 'x', termsAccepted: true })

    await waitFor(() => expect(result.current.isError).toBe(true))
    expect(navigateMock).not.toHaveBeenCalledWith('/login')
  })
})
