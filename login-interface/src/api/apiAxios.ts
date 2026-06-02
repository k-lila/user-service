import axios from 'axios'

// BFF: autenticação por cookie de sessão do gateway (não há token no browser).
// withCredentials envia o cookie; baseURL relativo passa pelo proxy (mesma origem).
export const api = axios.create({
  baseURL: import.meta.env.VITE_API_URL || '/',
  withCredentials: true,
  // CSRF: lê o cookie XSRF-TOKEN (setado pelo gateway) e o reenvia como header
  // X-XSRF-TOKEN nas requisições mutáveis.
  xsrfCookieName: 'XSRF-TOKEN',
  xsrfHeaderName: 'X-XSRF-TOKEN',
  withXSRFToken: true,
  headers: {
    'Content-Type': 'application/json',
  },
})
