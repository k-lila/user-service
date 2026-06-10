import { api } from './apiAxios'

export interface UserResponse {
  id: string
  name: string
  email: string
  registrationDate: string
}

export async function getCurrentUser(): Promise<UserResponse> {
  const response = await api.get('/v1/users/me')
  return response.data
}
