import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import router from '../router'
import { useAuth } from '../stores/auth'

beforeEach(() => {
  setActivePinia(createPinia())
  useAuth().initialized = true
})
describe('registration and account route access', () => {
  it('lets unauthenticated students open registration and protects account administration', async () => {
    await router.push('/register')
    expect(router.currentRoute.value.path).toBe('/register')
    await router.push('/admin/accounts')
    expect(router.currentRoute.value.path).toBe('/login')
  })
  it('denies account management to student roles and redirects authenticated registration to seats', async () => {
    useAuth().user = {
      id: 2,
      username: 'student',
      displayName: '学生',
      department: 'CS',
      roles: ['STUDENT'],
    }
    await router.push('/admin/accounts')
    expect(router.currentRoute.value.path).toBe('/seats')
    await router.push('/register')
    expect(router.currentRoute.value.path).toBe('/seats')
  })
  it('allows administrators to open the dedicated account page', async () => {
    useAuth().user = {
      id: 1,
      username: 'admin',
      displayName: '管理员',
      department: '',
      roles: ['ADMIN'],
    }
    await router.push('/admin/accounts')
    expect(router.currentRoute.value.path).toBe('/admin/accounts')
    expect(router.currentRoute.value.meta.admin).toBe(true)
  })
})
