import { api } from './apiAxios'

type LoginRequest = {
  email: string
  password: string
}

type LoginResponse = {
  token: string
}

type RegisterRequest = {
  name: string
  email: string
  rawPassword: string
}

type RegisterResponse = {
  id: string
  email: string
  token: string
}

export async function login(request: LoginRequest): Promise<LoginResponse> {
  const response = await api.post('/authentication/login', request)
  return response.data
}

export async function register(
  request: RegisterRequest
): Promise<RegisterResponse> {
  const response = await api.post('/authentication/register', request)
  return response.data
}
