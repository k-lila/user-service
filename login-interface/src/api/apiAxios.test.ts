import { describe, expect, it } from 'vitest'
import { api } from './apiAxios'

describe('apiAxios', () => {
  it('envia cookies de sessão (withCredentials) — BFF', () => {
    expect(api.defaults.withCredentials).toBe(true)
  })

  it('configura CSRF por cookie/header (XSRF-TOKEN → X-XSRF-TOKEN)', () => {
    expect(api.defaults.xsrfCookieName).toBe('XSRF-TOKEN')
    expect(api.defaults.xsrfHeaderName).toBe('X-XSRF-TOKEN')
    expect(api.defaults.withXSRFToken).toBe(true)
  })

  it('usa baseURL relativo (proxy/mesma origem) por padrão', () => {
    // VITE_API_URL vazio em dev → baseURL '/'
    expect(api.defaults.baseURL).toBe('/')
  })

  it('envia Content-Type application/json', () => {
    expect(api.defaults.headers['Content-Type']).toBe('application/json')
  })
})
