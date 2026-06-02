import { useMutation } from '@tanstack/react-query'
import { useNavigate } from 'react-router'
import { register } from '../api/authClient'

export const useRegister = () => {
  const navigate = useNavigate()
  return useMutation({
    mutationFn: register,
    // Registro não autentica: leva ao login para o fluxo OAuth2.
    onSuccess: () => {
      navigate('/login')
    },
  })
}
