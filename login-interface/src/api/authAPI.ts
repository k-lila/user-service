import { authApiClient } from './authAxios'

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
  const response = await authApiClient.post('/auth/login', request)
  return response.data
}

export async function register(
  request: RegisterRequest
): Promise<RegisterResponse> {
  const response = await authApiClient.post('/auth/register', request)
  return response.data
}
