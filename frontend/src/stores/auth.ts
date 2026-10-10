import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { request, refreshCsrf, write, ApiError } from '../lib/api'
import type { User, Role } from '../lib/types'
export const useAuth = defineStore('auth', () => {
  const user = ref<User | null>(null)
  const initialized = ref(false)
  const isAdmin = computed(() => user.value?.roles.includes('ADMIN') ?? false)
  const has = (role: Role) => user.value?.roles.includes(role) ?? false
  async function initialize() {
    if (initialized.value) return
    await refreshCsrf()
    try {
      user.value = await request<User>('/auth/me')
    } catch (error) {
      if (!(error instanceof ApiError && error.status === 401)) throw error
    } finally {
      initialized.value = true
    }
  }
  async function login(username: string, password: string) {
    await refreshCsrf()
    user.value = await write<User>('/auth/login', { username, password })
    await refreshCsrf()
    initialized.value = true
  }
  async function logout() {
    await write('/auth/logout')
    user.value = null
  }
  return { user, initialized, isAdmin, has, initialize, login, logout }
})
