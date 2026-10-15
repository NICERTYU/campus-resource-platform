import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import ElementPlus from 'element-plus'
import Login from '../pages/Login.vue'
import { write, ApiError } from '../lib/api'
vi.mock('../lib/api', async () => {
  const actual = await vi.importActual<typeof import('../lib/api')>('../lib/api')
  return { ...actual, write: vi.fn(), refreshCsrf: vi.fn() }
})
beforeEach(() => {
  setActivePinia(createPinia())
  vi.clearAllMocks()
  vi.stubEnv('DEV', true)
})
afterEach(() => vi.unstubAllEnvs())
async function form() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/login', component: Login }],
  })
  await router.push('/login')
  await router.isReady()
  return mount(Login, { global: { plugins: [router, ElementPlus] } })
}
describe('login form validation and feedback', () => {
  it('requires both credentials before sending a login request', async () => {
    const wrapper = await form()
    await wrapper.find('form').trigger('submit')
    expect(wrapper.text()).toContain('请填写账号和密码')
    expect(write).not.toHaveBeenCalled()
    wrapper.unmount()
  })
  it('offers role accounts in development and surfaces server errors with request ids', async () => {
    vi.mocked(write).mockRejectedValue(
      new ApiError(401, 'LOGIN_FAILED', '账号或密码错误', 'req-login'),
    )
    const wrapper = await form()
    await wrapper.findAll('.account-chips button')[0].trigger('click')
    expect((wrapper.find('[autocomplete="username"]').element as HTMLInputElement).value).toBe(
      'student',
    )
    expect(
      (wrapper.find('[autocomplete="current-password"]').element as HTMLInputElement).value,
    ).toBe('Campus123!')
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('账号或密码错误（请求编号 req-login）')
    wrapper.unmount()
  })
  it('hides demo accounts in production and submits only manually entered credentials', async () => {
    vi.stubEnv('DEV', false)
    vi.mocked(write).mockRejectedValue(new ApiError(401, 'LOGIN_FAILED', '账号或密码错误'))
    const wrapper = await form()
    expect(wrapper.find('.demo-accounts').exists()).toBe(false)
    expect(wrapper.findAll('.account-chips button')).toHaveLength(0)
    expect(wrapper.text()).not.toContain('Campus123!')
    const username = wrapper.find('[autocomplete="username"]')
    const password = wrapper.find('[autocomplete="current-password"]')
    expect((username.element as HTMLInputElement).value).toBe('')
    expect((password.element as HTMLInputElement).value).toBe('')
    await username.setValue('admin')
    await password.setValue('manually-entered-test-password')
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(write).toHaveBeenCalledWith('/auth/login', {
      username: 'admin',
      password: 'manually-entered-test-password',
    })
    wrapper.unmount()
  })
})
