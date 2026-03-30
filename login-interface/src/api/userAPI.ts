import { gatewayApiClient } from './gatewayAxios'

export interface UserResponse {
  id: string
  name: string
  email: string
  registrationDate: string
}

export async function getCurrentUser(): Promise<UserResponse> {
  const response = await gatewayApiClient.get('/users/me')
  return response.data
}
