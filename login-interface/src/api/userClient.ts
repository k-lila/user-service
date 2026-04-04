import { api } from './apiAxios'

export interface UserResponse {
  id: string
  name: string
  email: string
  registrationDate: string
}

export async function getCurrentUser(): Promise<UserResponse> {
  const response = await api.get('/users/me')
  return response.data
}
