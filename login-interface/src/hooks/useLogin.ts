import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router'
import { login } from '../api/authAPI'

export const useLogin = () => {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: login,
    onSuccess: (data) => {
      localStorage.setItem('token', data.token)
      queryClient.invalidateQueries({ queryKey: ['currentUser'] })
      navigate('/dashboard')
    },
  })
}
