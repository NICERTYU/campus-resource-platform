import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import ElementPlus from 'element-plus'
import Register from '../pages/Register.vue'
import { ApiError, refreshCsrf, write } from '../lib/api'
import { passwordError } from '../lib/accounts'

vi.mock('../lib/api', async () => {
  const actual = await vi.importActual<typeof import('../lib/api')>('../lib/api')
  return { ...actual, refreshCsrf: vi.fn(), write: vi.fn() }
})
beforeEach(() => vi.resetAllMocks())
afterEach(() => vi.unstubAllGlobals())
async function registration() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/register', component: Register },
      { path: '/login', component: { template: '<div>登录</div>' } },
    ],
  })
  await router.push('/register')
  await router.isReady()
  return { router, wrapper: mount(Register, { global: { plugins: [router, ElementPlus] } }) }
}
async function fill(wrapper: ReturnType<typeof mount>, password = 'Student-Test123!') {
  await wrapper.find('[aria-label="校园账号"]').setValue('new_student')
  await wrapper.find('[aria-label="姓名"]').setValue('  新同学  ')
  await wrapper.find('select').setValue('CS')
  await wrapper.find('[aria-label="登录密码"]').setValue(password)
  await wrapper.find('[aria-label="确认密码"]').setValue(password)
}
describe('student registration', () => {
  it('requires valid names, matching passwords and an explicit department before submitting', async () => {
    const { wrapper } = await registration()
    await wrapper.find('form').trigger('submit')
    expect(wrapper.text()).toContain('账号需以字母开头')
    await fill(wrapper)
    await wrapper.find('[aria-label="校园账号"]').setValue('1invalid')
    await wrapper.find('form').trigger('submit')
    expect(wrapper.text()).toContain('账号需以字母开头')
    await wrapper.find('[aria-label="校园账号"]').setValue('new_student')
    await wrapper.find('[aria-label="确认密码"]').setValue('different-test-password')
    await wrapper.find('form').trigger('submit')
    expect(wrapper.text()).toContain('两次输入的密码不一致')
    await wrapper.find('[aria-label="确认密码"]').setValue('Student-Test123!')
    await wrapper.find('select').setValue('')
    await wrapper.find('form').trigger('submit')
    expect(wrapper.text()).toContain('请选择所属院系')
    expect(write).not.toHaveBeenCalled()
    wrapper.unmount()
  })
  it('checks UTF-8 byte length so long multibyte passwords are not silently truncated', () => {
    expect(passwordError('short')).toContain('8个字符')
    expect(passwordError('密'.repeat(25))).toContain('密码过长')
    expect(passwordError('密'.repeat(24))).toBe('')
    expect(passwordError('Student-Test123!')).toBe('')
  })
  it('refreshes CSRF, submits only student fields and redirects to a password-free login URL', async () => {
    const { wrapper, router } = await registration()
    vi.mocked(write).mockResolvedValue({ id: 7, username: 'new_student' })
    await fill(wrapper)
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(refreshCsrf).toHaveBeenCalledOnce()
    expect(write).toHaveBeenCalledWith('/auth/register', {
      username: 'new_student',
      displayName: '新同学',
      password: 'Student-Test123!',
      department: 'CS',
    })
    expect(vi.mocked(write).mock.calls[0]![1]).not.toHaveProperty('roles')
    expect(router.currentRoute.value.path).toBe('/login')
    expect(router.currentRoute.value.query).toEqual({ username: 'new_student', registered: '1' })
    expect(router.currentRoute.value.fullPath).not.toContain('Student-Test123!')
    expect((wrapper.find('[aria-label="登录密码"]').element as HTMLInputElement).value).toBe('')
    wrapper.unmount()
  })
  it('preserves recoverable fields on rejection and prevents duplicate submissions while waiting', async () => {
    const { wrapper } = await registration()
    let rejectRequest: (reason: Error) => void = () => {}
    vi.mocked(write).mockImplementation(
      () =>
        new Promise((_resolve, reject) => {
          rejectRequest = reject
        }),
    )
    await fill(wrapper)
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    await wrapper.find('form').trigger('submit')
    expect(write).toHaveBeenCalledOnce()
    rejectRequest(new ApiError(409, 'USERNAME_TAKEN', '账号已存在', 'register-409'))
    await flushPromises()
    expect(wrapper.text()).toContain('账号已存在（请求编号 register-409）')
    expect((wrapper.find('[aria-label="校园账号"]').element as HTMLInputElement).value).toBe(
      'new_student',
    )
    vi.mocked(write).mockResolvedValue({ id: 7, username: 'another_student' })
    await wrapper.find('[aria-label="校园账号"]').setValue('another_student')
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(write).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })
})
