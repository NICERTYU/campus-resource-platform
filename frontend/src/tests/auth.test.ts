import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAuth } from '../stores/auth'
import { request, refreshCsrf, write, ApiError } from '../lib/api'
vi.mock('../lib/api', async () => {
  const actual = await vi.importActual<typeof import('../lib/api')>('../lib/api')
  return { ...actual, request: vi.fn(), refreshCsrf: vi.fn(), write: vi.fn() }
})
beforeEach(() => {
  setActivePinia(createPinia())
  vi.resetAllMocks()
})
const user = {
  id: 1,
  username: 'student',
  displayName: '学生',
  department: 'CS',
  roles: ['STUDENT'] as const,
}
describe('session lifecycle', () => {
  it('refreshes CSRF before login and after session rotation', async () => {
    const calls: string[] = []
    vi.mocked(refreshCsrf).mockImplementation(async () => {
      calls.push('csrf')
    })
    vi.mocked(write).mockImplementation(async () => {
      calls.push('login')
      return user
    })
    const auth = useAuth()
    await auth.login('student', 'Campus123!')
    expect(calls).toEqual(['csrf', 'login', 'csrf'])
    expect(auth.has('STUDENT')).toBe(true)
    expect(auth.isAdmin).toBe(false)
  })
  it('accepts unauthenticated bootstrap but surfaces backend failures', async () => {
    vi.mocked(request).mockRejectedValueOnce(new ApiError(401, 'UNAUTHENTICATED', '请登录'))
    const auth = useAuth()
    await auth.initialize()
    expect(auth.user).toBeNull()
    expect(auth.initialized).toBe(true)
    setActivePinia(createPinia())
    vi.mocked(request).mockRejectedValueOnce(new ApiError(503, 'UNAVAILABLE', '服务暂不可用'))
    await expect(useAuth().initialize()).rejects.toThrow('服务暂不可用')
  })
  it('clears user after successful logout', async () => {
    vi.mocked(write).mockResolvedValue(user)
    const auth = useAuth()
    await auth.login('student', 'Campus123!')
    await auth.logout()
    expect(auth.user).toBeNull()
    expect(write).toHaveBeenLastCalledWith('/auth/logout')
    expect(refreshCsrf).toHaveBeenCalledTimes(2)
  })
})
