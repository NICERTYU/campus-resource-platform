import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import App from '../App.vue'
import { useAuth } from '../stores/auth'
import { write, refreshCsrf } from '../lib/api'
import type { Role } from '../lib/types'
vi.mock('../lib/api', async () => {
  const actual = await vi.importActual<typeof import('../lib/api')>('../lib/api')
  return { ...actual, write: vi.fn(), refreshCsrf: vi.fn() }
})
beforeEach(() => vi.resetAllMocks())
async function navigation(roles: Role[]) {
  const pinia = createPinia()
  setActivePinia(pinia)
  useAuth().user = { id: 1, username: 'test', displayName: '校园用户', department: 'CS', roles }
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/login', component: { template: '<div>登录</div>' } },
      {
        path: '/seats',
        component: { template: '<div>空间</div>' },
        meta: { title: '寻找自习座位' },
      },
    ],
  })
  await router.push('/seats')
  await router.isReady()
  return mount(App, { global: { plugins: [pinia, router] } })
}
describe('role navigation and responsive shell', () => {
  it('shows student workflows without admin or approval navigation', async () => {
    const wrapper = await navigation(['STUDENT'])
    const text = wrapper.find('nav').text()
    expect(text).toContain('寻找座位')
    expect(text).toContain('我的预约')
    expect(text).toContain('报修服务')
    expect(text).not.toContain('空间与运营')
    expect(text).not.toContain('教室申请')
    wrapper.unmount()
  })
  it('shows applicant and administrator menus for a multi-role account', async () => {
    const wrapper = await navigation(['STUDENT', 'APPLICANT', 'ADMIN'])
    expect(wrapper.find('nav').text()).toContain('教室申请')
    expect(wrapper.find('nav').text()).toContain('空间与运营')
    expect(wrapper.find('.user-profile').text()).toContain('学生 · 教室申请人 · 管理员')
    wrapper.unmount()
  })
  it('opens and closes the mobile navigation through an overlay', async () => {
    const wrapper = await navigation(['STUDENT'])
    await wrapper.find('[aria-label="展开菜单"]').trigger('click')
    expect(wrapper.find('.sidebar').classes()).toContain('open')
    await wrapper.find('.mobile-overlay').trigger('click')
    expect(wrapper.find('.sidebar').classes()).not.toContain('open')
    wrapper.unmount()
  })
  it('redirects to login after logout even if a subsequent CSRF refresh would be unavailable', async () => {
    const wrapper = await navigation(['STUDENT'])
    vi.mocked(write).mockResolvedValue(undefined)
    vi.mocked(refreshCsrf).mockRejectedValue(new Error('network offline'))
    await wrapper.find('[aria-label="退出登录"]').trigger('click')
    await flushPromises()
    expect(useAuth().user).toBeNull()
    expect(wrapper.vm.$router.currentRoute.value.path).toBe('/login')
    expect(wrapper.find('.app-shell').exists()).toBe(false)
    expect(refreshCsrf).not.toHaveBeenCalled()
    wrapper.unmount()
  })
})
