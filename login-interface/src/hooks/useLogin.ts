import { useMutation } from '@tanstack/react-query'
import { useNavigate } from 'react-router'
import { login } from '../api/authAPI'

export const useLogin = () => {
  const navigate = useNavigate()
  return useMutation({
    mutationFn: login,
    onSuccess: (data) => {
      localStorage.setItem('token', data.token)
      navigate('/dashboard')
    },
  })
}
