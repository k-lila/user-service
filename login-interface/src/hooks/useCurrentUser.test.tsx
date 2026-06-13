import { describe, expect, it } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { server } from '../test/server'
import { queryWrapper } from '../test/utils'
import { useCurrentUser } from './useCurrentUser'

describe('useCurrentUser', () => {
  it('popula data quando autenticado (200)', async () => {
    const { result } = renderHook(() => useCurrentUser(), {
      wrapper: queryWrapper(),
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data?.email).toBe('ana@example.com')
  })

  it('vira isError em 401 sem re-tentar (retry:false)', async () => {
    let calls = 0
    server.use(
      http.get('/v1/users/me', () => {
        calls += 1
        return new HttpResponse(null, { status: 401 })
      })
    )

    const { result } = renderHook(() => useCurrentUser(), {
      wrapper: queryWrapper(),
    })

    await waitFor(() => expect(result.current.isError).toBe(true))
    // retry:false ⇒ uma única chamada (sem back-off de retentativas).
    expect(calls).toBe(1)
    expect(result.current.data).toBeUndefined()
  })
})
