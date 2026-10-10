import { beforeEach, describe, expect, it, vi } from 'vitest'
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
})
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
  it('offers role accounts and surfaces server errors with request ids', async () => {
    vi.mocked(write).mockRejectedValue(
      new ApiError(401, 'LOGIN_FAILED', '账号或密码错误', 'req-login'),
    )
    const wrapper = await form()
    await wrapper.findAll('.account-chips button')[0].trigger('click')
    expect((wrapper.find('[autocomplete="username"]').element as HTMLInputElement).value).toBe(
      'student',
    )
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('账号或密码错误（请求编号 req-login）')
    wrapper.unmount()
  })
})
