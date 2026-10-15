import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import ElementPlus from 'element-plus'
import Accounts from '../pages/Accounts.vue'
import { useAuth } from '../stores/auth'
import { ApiError, request, write } from '../lib/api'
import type { Account } from '../lib/types'

vi.mock('../lib/api', async () => {
  const actual = await vi.importActual<typeof import('../lib/api')>('../lib/api')
  return { ...actual, request: vi.fn(), write: vi.fn() }
})
const admin: Account = {
  id: 1,
  username: 'admin',
  displayName: '管理员',
  department: null,
  roles: ['ADMIN'],
  enabled: true,
}
const student: Account = {
  id: 2,
  username: 'student',
  displayName: '学生',
  department: 'CS',
  roles: ['STUDENT'],
  enabled: true,
}
beforeEach(() => {
  setActivePinia(createPinia())
  useAuth().user = { ...admin, department: '' }
  useAuth().initialized = true
  vi.resetAllMocks()
  vi.mocked(request).mockResolvedValue([admin, student])
})
async function accountPage() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/admin/accounts', component: Accounts },
      { path: '/login', component: { template: '<div>登录</div>' } },
    ],
  })
  await router.push('/admin/accounts')
  await router.isReady()
  const wrapper = mount(Accounts, {
    global: {
      plugins: [router, ElementPlus],
      stubs: {
        ElDialog: {
          props: ['modelValue', 'title'],
          template:
            '<section v-if="modelValue" class="test-dialog"><h2>{{ title }}</h2><slot/><slot name="footer"/></section>',
        },
      },
    },
  })
  await flushPromises()
  return { wrapper, router }
}
function button(wrapper: ReturnType<typeof mount>, label: string) {
  const found = wrapper.findAll('button').find((item) => item.text() === label)
  if (!found) throw new Error(`Missing button ${label}`)
  return found
}
function row(wrapper: ReturnType<typeof mount>, id: number) {
  return wrapper.find(`[data-testid="account-row-${id}"]`).element.closest('tr')!
}
async function rowButton(wrapper: ReturnType<typeof mount>, id: number, label: string) {
  const element = Array.from(row(wrapper, id).querySelectorAll('button')).find(
    (item) => item.textContent?.trim() === label,
  )!
  element.click()
  await flushPromises()
}
describe('administrator account management', () => {
  it('searches on the server and preserves the filter across pagination, including disabled accounts', async () => {
    vi.mocked(request).mockResolvedValue(
      Array.from({ length: 20 }, (_, index) => ({
        ...student,
        id: index + 2,
        username: `student${index}`,
        enabled: false,
      })),
    )
    const { wrapper } = await accountPage()
    expect(request).toHaveBeenLastCalledWith('/admin/accounts?page=1&pageSize=20&query=')
    expect(wrapper.text()).toContain('停用')
    await wrapper.find('[aria-label="搜索账号或姓名"]').setValue('  student  ')
    await wrapper.find('form.account-search').trigger('submit')
    await flushPromises()
    expect(request).toHaveBeenLastCalledWith('/admin/accounts?page=1&pageSize=20&query=student')
    await button(wrapper, '下一页').trigger('click')
    await flushPromises()
    expect(request).toHaveBeenLastCalledWith('/admin/accounts?page=2&pageSize=20&query=student')
    await wrapper.find('[aria-label="搜索账号或姓名"]').setValue('none')
    await wrapper.find('form.account-search').trigger('submit')
    await flushPromises()
    expect(request).toHaveBeenLastCalledWith('/admin/accounts?page=1&pageSize=20&query=none')
    wrapper.unmount()
  })
  it('creates an account with selected roles and clears its password after success', async () => {
    const { wrapper } = await accountPage()
    await button(wrapper, '新增账号').trigger('click')
    await wrapper.find('[aria-label="校园账号"]').setValue('staff_user')
    await wrapper.find('[aria-label="姓名"]').setValue('  新维修员  ')
    await wrapper.find('[aria-label="初始密码"]').setValue('Staff-Test123!')
    await wrapper.find('input[value="TECHNICIAN"]').setValue(true)
    vi.mocked(write).mockResolvedValue({ ...student, id: 3 })
    await button(wrapper, '创建账号').trigger('click')
    await flushPromises()
    expect(write).toHaveBeenCalledWith(
      '/admin/accounts',
      {
        username: 'staff_user',
        displayName: '新维修员',
        department: null,
        password: 'Staff-Test123!',
        roles: ['STUDENT', 'TECHNICIAN'],
        enabled: true,
      },
      'POST',
    )
    expect(wrapper.find('.test-dialog').exists()).toBe(false)
    await button(wrapper, '新增账号').trigger('click')
    expect((wrapper.find('[aria-label="初始密码"]').element as HTMLInputElement).value).toBe('')
    wrapper.unmount()
  })
  it('requires a role and valid password and retains the form after a server rejection', async () => {
    const { wrapper } = await accountPage()
    await button(wrapper, '新增账号').trigger('click')
    await wrapper.find('[aria-label="校园账号"]').setValue('staff_user')
    await wrapper.find('[aria-label="姓名"]').setValue('新维修员')
    await wrapper.find('[aria-label="初始密码"]').setValue('short')
    await button(wrapper, '创建账号').trigger('click')
    expect(wrapper.text()).toContain('密码至少需要8个字符')
    await wrapper.find('[aria-label="初始密码"]').setValue('Staff-Test123!')
    await wrapper.find('input[value="STUDENT"]').setValue(false)
    await button(wrapper, '创建账号').trigger('click')
    expect(wrapper.text()).toContain('请至少选择一个有效角色')
    expect(write).not.toHaveBeenCalled()
    await wrapper.find('input[value="STUDENT"]').setValue(true)
    vi.mocked(write).mockRejectedValue(new ApiError(409, 'USERNAME_TAKEN', '账号已存在'))
    await button(wrapper, '创建账号').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('账号已存在')
    expect((wrapper.find('[aria-label="校园账号"]').element as HTMLInputElement).value).toBe(
      'staff_user',
    )
    expect(wrapper.find('.test-dialog').exists()).toBe(true)
    wrapper.unmount()
  })
  it('names the target before confirming role and status changes without changing the username', async () => {
    const { wrapper } = await accountPage()
    await rowButton(wrapper, 2, '编辑账号')
    expect(wrapper.find('.account-target').text()).toContain('学生（student）')
    expect(wrapper.find('[aria-label="校园账号"]').attributes('disabled')).toBeDefined()
    await wrapper.find('input[value="APPLICANT"]').setValue(true)
    await wrapper.find('[role="switch"]').trigger('click')
    vi.mocked(write).mockResolvedValue({
      ...student,
      roles: ['STUDENT', 'APPLICANT'],
      enabled: false,
    })
    await button(wrapper, '保存并确认').trigger('click')
    await flushPromises()
    expect(write).toHaveBeenCalledWith(
      '/admin/accounts/2',
      { displayName: '学生', department: 'CS', roles: ['STUDENT', 'APPLICANT'], enabled: false },
      'PUT',
    )
    expect(useAuth().user?.id).toBe(1)
    wrapper.unmount()
  })
  it('prevents removing your own administrator role or disabling your own account', async () => {
    const { wrapper } = await accountPage()
    await rowButton(wrapper, 1, '编辑账号')
    expect(wrapper.find('input[value="ADMIN"]').attributes('disabled')).toBeDefined()
    expect(wrapper.find('[role="switch"]').attributes('aria-disabled')).toBe('true')
    expect(wrapper.text()).toContain('不能停用自己的账号或取消自己的管理员角色')
    wrapper.unmount()
  })
  it('requires matching reset passwords, names the target, and preserves errors for retry', async () => {
    const { wrapper } = await accountPage()
    await rowButton(wrapper, 2, '重置密码')
    expect(wrapper.find('.account-target').text()).toContain('学生（student）')
    await wrapper.find('[aria-label="新密码"]').setValue('Changed-Test123!')
    await wrapper.find('[aria-label="确认新密码"]').setValue('Mismatch-Test123!')
    await button(wrapper, '确认重置密码').trigger('click')
    expect(wrapper.text()).toContain('两次输入的密码不一致')
    expect(write).not.toHaveBeenCalled()
    await wrapper.find('[aria-label="确认新密码"]').setValue('Changed-Test123!')
    vi.mocked(write).mockRejectedValueOnce(new ApiError(503, 'UNAVAILABLE', '请稍后重试'))
    await button(wrapper, '确认重置密码').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('请稍后重试')
    vi.mocked(write).mockResolvedValue({ success: true })
    await button(wrapper, '确认重置密码').trigger('click')
    await flushPromises()
    expect(write).toHaveBeenLastCalledWith('/admin/accounts/2/password', {
      password: 'Changed-Test123!',
    })
    expect(wrapper.find('.test-dialog').exists()).toBe(false)
    expect(useAuth().user?.id).toBe(1)
    wrapper.unmount()
  })
  it('clears local auth and returns to login after resetting your own password without calling logout', async () => {
    const { wrapper, router } = await accountPage()
    await rowButton(wrapper, 1, '重置密码')
    await wrapper.find('[aria-label="新密码"]').setValue('Admin-Changed123!')
    await wrapper.find('[aria-label="确认新密码"]').setValue('Admin-Changed123!')
    vi.mocked(write).mockResolvedValue({ success: true })
    await button(wrapper, '确认重置密码').trigger('click')
    await flushPromises()
    expect(useAuth().user).toBeNull()
    expect(router.currentRoute.value.path).toBe('/login')
    expect(router.currentRoute.value.query).toEqual({ username: 'admin', passwordReset: '1' })
    expect(write).toHaveBeenCalledOnce()
    expect(wrapper.text()).not.toContain('Admin-Changed123!')
    wrapper.unmount()
  })
})
