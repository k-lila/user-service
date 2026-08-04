import { api } from './apiAxios'

export interface UserResponse {
  id: string
  name: string
  email: string
  registrationDate: string
  // ADR-015: nunca chega null — o backend normaliza legados sem o campo como
  // verificados (UserResponseDTO.toResponseDTO).
  emailVerified: boolean
}

export async function getCurrentUser(): Promise<UserResponse> {
  const response = await api.get('/v1/users/me')
  return response.data
}
