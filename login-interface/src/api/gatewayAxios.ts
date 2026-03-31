import axios from 'axios'

export const gatewayApiClient = axios.create({
  baseURL: import.meta.env.VITE_GATEWAY_API_URL,
  headers: {
    'Content-Type': 'application/json',
  },
})

gatewayApiClient.interceptors.request.use((config) => {
  const token = localStorage.getItem('token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})
