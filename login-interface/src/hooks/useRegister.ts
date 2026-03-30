import { useMutation } from '@tanstack/react-query'
import { useNavigate } from 'react-router'
import { register } from '../api/authAPI'

export const useRegister = () => {
  const navigate = useNavigate()
  return useMutation({
    mutationFn: register,
    onSuccess: (data) => {
      localStorage.setItem('token', data.token)
      navigate('/dashboard')
    },
  })
}
