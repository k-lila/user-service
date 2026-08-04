import { useMutation } from '@tanstack/react-query'
import { useNavigate } from 'react-router'
import { register } from '../api/authClient'

export const useRegister = () => {
  const navigate = useNavigate()
  return useMutation({
    mutationFn: register,
    // Registro não autentica: leva a / onde <Login/> inicia o fluxo OAuth2.
    // ADR-019: /login pertence ao IdP; / é o novo lar do LoginBox.
    onSuccess: () => {
      navigate('/')
    },
  })
}
