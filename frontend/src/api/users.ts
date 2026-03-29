import { apiClient } from './client'

export interface UserSummary { userId: string; username: string }

export const usersApi = {
  search: (q: string) =>
    apiClient.get<UserSummary[]>('/users/search', { params: { q } }).then(r => r.data),
}
