import type { ReactElement, ReactNode } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router'
import { render } from '@testing-library/react'

// QueryClient novo por teste, sem retry e sem cache persistente, para que o
// estado de uma query não vaze entre casos.
export function makeQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: 0 },
      mutations: { retry: false },
    },
  })
}

type RenderOptions = {
  route?: string
  routerEntries?: string[]
  client?: QueryClient
}

export function renderWithProviders(
  ui: ReactElement,
  { route = '/', routerEntries, client = makeQueryClient() }: RenderOptions = {}
) {
  const entries = routerEntries ?? [route]
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={entries}>{children}</MemoryRouter>
    </QueryClientProvider>
  )
  return { client, ...render(ui, { wrapper }) }
}

// Wrapper só com QueryClient, para renderHook de hooks que não usam router.
export function queryWrapper(client: QueryClient = makeQueryClient()) {
  return ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  )
}
