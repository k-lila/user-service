import { useQuery } from '@tanstack/react-query'
import { getCurrentUser } from '../api/userClient'

export function useCurrentUser() {
  return useQuery({
    queryKey: ['currentUser'],
    queryFn: getCurrentUser,
    staleTime: 1000 * 60 * 10,
    refetchOnWindowFocus: false,
  })
}
