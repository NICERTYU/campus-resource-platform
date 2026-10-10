import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ElementPlus from 'element-plus'
import PagePager from '../components/PagePager.vue'
import Applications from '../pages/Applications.vue'
import Bookings from '../pages/Bookings.vue'
import { useAuth } from '../stores/auth'
import { request, ApiError } from '../lib/api'
import { usePagination } from '../lib/pagination'
vi.mock('../lib/api', async () => {
  const actual = await vi.importActual<typeof import('../lib/api')>('../lib/api')
  return { ...actual, request: vi.fn() }
})
beforeEach(() => {
  setActivePinia(createPinia())
  useAuth().user = {
    id: 1,
    username: 'admin',
    displayName: '管理员',
    department: 'CS',
    roles: ['ADMIN'],
  }
  vi.resetAllMocks()
})
const room = {
  id: 1,
  name: '知行101',
  building: '知行楼',
  floor: 1,
  department: null,
  openTime: '08:00:00',
  closeTime: '22:00:00',
  enabled: true,
  seatCount: 24,
}
const application = (id: number) => ({
  id,
  classroomId: 1,
  classroomName: '知行101',
  applicantId: 2,
  applicantName: '申请人',
  startTime: '2026-10-12T10:00:00+08:00',
  endTime: '2026-10-12T11:00:00+08:00',
  purpose: `申请用途${id}`,
  status: 'PENDING',
  comment: null,
  pendingAction: null,
  lastError: null,
})
const reservation = (id: number, status = 'PENDING') => ({
  id,
  seatId: id,
  seatLabel: `A${id}`,
  classroomId: 1,
  classroomName: '知行101',
  startTime: '2026-10-12T10:00:00+08:00',
  endTime: '2026-10-12T11:00:00+08:00',
  status,
  checkInDeadline: '2026-10-12T10:15:00+08:00',
  checkedInAt: null,
  cancelReason: null,
})
describe('bounded list pagination', () => {
  it('emits a next page only for a full page and prevents negative/empty navigation', async () => {
    const wrapper = mount(PagePager, {
      props: { page: 1, pageSize: 50, rowCount: 50, loading: false },
      global: { plugins: [ElementPlus] },
    })
    const buttons = wrapper.findAll('button')
    expect(buttons[0].attributes('disabled')).toBeDefined()
    await buttons[0].trigger('click')
    expect(wrapper.emitted('change')).toBeUndefined()
    await buttons[1].trigger('click')
    expect(wrapper.emitted('change')).toEqual([[2]])
    await wrapper.setProps({ page: 2, rowCount: 0 })
    expect(buttons[1].attributes('disabled')).toBeDefined()
    await buttons[0].trigger('click')
    expect(wrapper.emitted('change')).toEqual([[2], [1]])
    wrapper.unmount()
  })
  it('disables both paging actions while fetching', () => {
    const wrapper = mount(PagePager, {
      props: { page: 2, pageSize: 50, rowCount: 50, loading: true },
      global: { plugins: [ElementPlus] },
    })
    expect(
      wrapper.findAll('button').every((button) => button.attributes('disabled') !== undefined),
    ).toBe(true)
    wrapper.unmount()
  })
  it('keeps paging state valid and resets for a new list filter', () => {
    const pager = usePagination()
    expect(pager.query()).toBe('page=1&pageSize=50')
    pager.accept(2, 12)
    expect(pager.page).toBe(2)
    pager.accept(0, 0)
    expect(pager.page).toBe(2)
    pager.reset()
    expect(pager.page).toBe(1)
    expect(pager.rowCount).toBe(0)
  })
  it('requests only the next bounded page and preserves visible records when that request fails', async () => {
    let secondAttempts = 0
    vi.mocked(request).mockImplementation(async (path) => {
      if (path === '/classrooms') return [room]
      const query = new URL(path, 'http://unit.test').searchParams
      expect(query.get('pageSize')).toBe('50')
      if (query.get('page') === '2') {
        secondAttempts++
        if (secondAttempts === 1)
          throw new ApiError(503, 'UNAVAILABLE', '分页请求暂不可用', 'req-page')
        return [application(51)]
      }
      return Array.from({ length: 50 }, (_, i) => application(i + 1))
    })
    const wrapper = mount(Applications, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    expect(wrapper.findAll('.workflow-card')).toHaveLength(50)
    let next = wrapper.findAll('button').find((button) => button.text() === '下一页')!
    await next.trigger('click')
    await flushPromises()
    expect(request).toHaveBeenCalledWith('/classroom-applications?page=2&pageSize=50')
    expect(wrapper.text()).toContain('分页请求暂不可用（请求编号 req-page）')
    expect(wrapper.find('.pagination-bar').text()).toContain('第 1 页')
    expect(wrapper.findAll('.workflow-card')).toHaveLength(50)
    await next.trigger('click')
    await flushPromises()
    expect(wrapper.find('.pagination-bar').text()).toContain('第 2 页')
    expect(wrapper.findAll('.workflow-card')).toHaveLength(1)
    expect(next.attributes('disabled')).toBeDefined()
    wrapper.unmount()
  })
  it('filters current/history on the server before paging and resets page when switching tabs', async () => {
    vi.mocked(request).mockImplementation(async (path) => {
      if (path.startsWith('/violations')) return []
      const query = new URL(path, 'http://unit.test').searchParams
      if (query.get('statusGroup') === 'history') return [reservation(77, 'CANCELLED')]
      return Array.from({ length: 50 }, (_, i) => reservation(i + 1))
    })
    const wrapper = mount(Bookings, {
      global: { plugins: [ElementPlus], stubs: { RouterLink: { template: '<a><slot/></a>' } } },
    })
    await flushPromises()
    expect(request).toHaveBeenCalledWith(
      '/reservations/mine?page=1&pageSize=50&statusGroup=current',
    )
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '下一页')!
      .trigger('click')
    await flushPromises()
    expect(wrapper.find('.pagination-bar').text()).toContain('第 2 页')
    await wrapper
      .findAll('[role="tab"]')
      .find((tab) => tab.text() === '历史记录')!
      .trigger('click')
    await flushPromises()
    expect(request).toHaveBeenCalledWith(
      '/reservations/mine?page=1&pageSize=50&statusGroup=history',
    )
    expect(wrapper.find('.pagination-bar').text()).toContain('第 1 页')
    expect(wrapper.findAll('.booking-row')).toHaveLength(1)
    expect(wrapper.find('.booking-row').text()).toContain('已取消')
    wrapper.unmount()
  })
})
