import { api } from './apiAxios'
import type { UserResponse } from './userClient'

export type RegisterRequest = {
  name: string
  email: string
  password: string
}

// BFF: o gateway é o cliente OAuth2. "Login" é um redirect que inicia o
// fluxo authorization_code+PKCE no gateway; o token nunca chega ao browser.
export function login(): void {
  window.location.href = '/oauth2/authorization/gateway-client'
}

function readCookie(name: string): string {
  const match = document.cookie.match(new RegExp('(?:^|; )' + name + '=([^;]+)'))
  return match ? decodeURIComponent(match[1]) : ''
}

// Logout BFF (RP-Initiated): POST top-level via form para o /logout do gateway.
// É navegação (não XHR) para o browser conseguir seguir o redirect até o end_session
// do auth-server e voltar ao SPA já deslogado. O token CSRF vai como parâmetro _csrf
// (num POST top-level não dá para setar o header X-XSRF-TOKEN).
export function logout(): void {
  const form = document.createElement('form')
  form.method = 'POST'
  form.action = '/logout'
  const csrf = document.createElement('input')
  csrf.type = 'hidden'
  csrf.name = '_csrf'
  csrf.value = readCookie('XSRF-TOKEN')
  form.appendChild(csrf)
  document.body.appendChild(form)
  form.submit()
}

// Registro é público (POST /users/register) e não autentica: após criar a
// conta, o usuário ainda passa pelo login OAuth2.
export async function register(
  request: RegisterRequest
): Promise<UserResponse> {
  const response = await api.post('/users/register', request)
  return response.data
}
