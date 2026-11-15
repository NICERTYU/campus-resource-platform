import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ElementPlus from 'element-plus'
import Bookings from '../pages/Bookings.vue'
import { useAuth } from '../stores/auth'
import { ApiError, request, write } from '../lib/api'
import type { Reservation } from '../lib/types'
vi.mock('../lib/api', async () => ({
  ...(await vi.importActual('../lib/api')),
  request: vi.fn(),
  write: vi.fn(),
}))
const record: Reservation = {
  id: 41,
  seatId: 5,
  classroomId: 1,
  classroomName: '知行101',
  seatLabel: 'A01',
  startTime: '2026-11-05T10:00:00+08:00',
  endTime: '2026-11-05T11:00:00+08:00',
  checkInDeadline: '2026-11-05T10:15:00+08:00',
  status: 'COMPLETED',
  checkedInAt: '2026-11-05T09:50:00+08:00',
  cancelReason: null,
}
let wrapper: VueWrapper
beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date', 'setInterval', 'clearInterval'] })
  vi.setSystemTime(new Date('2026-11-06T09:50:00+08:00'))
  vi.resetAllMocks()
  setActivePinia(createPinia())
  useAuth().user = {
    id: 1,
    username: 'student',
    displayName: '同学',
    roles: ['STUDENT'],
    department: 'CS',
  }
  vi.mocked(request).mockResolvedValue([record])
})
afterEach(() => {
  wrapper?.unmount()
  vi.useRealTimers()
})
async function render() {
  wrapper = mount(Bookings, {
    global: {
      plugins: [ElementPlus],
      stubs: {
        RouterLink: { template: '<a><slot/></a>' },
        ElDialog: {
          props: ['modelValue', 'title'],
          template:
            '<div v-if="modelValue" role="dialog"><h2>{{ title }}</h2><slot/><slot name="footer"/></div>',
        },
      },
    },
  })
  await flushPromises()
  return wrapper
}
async function click(label: string) {
  const button = wrapper.findAll('button').find((b) => b.text() === label)
  expect(button, label).toBeDefined()
  await button!.trigger('click')
  await flushPromises()
}
async function tab(label: string) {
  const item = wrapper.findAll('[role="tab"]').find((t) => t.text() === label)
  await item!.trigger('click')
  await flushPromises()
}
async function openReuse() {
  await render()
  await tab('历史记录')
  await click('一键复用')
}
describe('M1 bookings page', () => {
  it('creates from source ID and new times, then reloads current records', async () => {
    vi.mocked(write).mockResolvedValue({ id: 42 })
    await openReuse()
    await click('确认复用')
    expect(write).toHaveBeenCalledExactlyOnceWith('/reservations/41/reuse', {
      startTime: '2026-11-07T10:00:00+08:00',
      endTime: '2026-11-07T11:00:00+08:00',
    })
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
    expect(request).toHaveBeenLastCalledWith(expect.stringContaining('statusGroup=current'))
  })
  it.each([
    [400, '原时段已过期'],
    [403, '无权限复用'],
    [409, '座位冲突'],
  ])('keeps %s failure and true request ID visible without retry', async (status, message) => {
    vi.mocked(write).mockRejectedValue(new ApiError(status, 'FAILED', message, `m1-${status}`))
    await openReuse()
    await click('确认复用')
    expect(wrapper.find('[role="dialog"]').text()).toContain(`${message}（请求编号 m1-${status}）`)
    expect(write).toHaveBeenCalledTimes(1)
  })
  it('rejects an expired new date before submitting', async () => {
    await openReuse()
    await wrapper.find('input[aria-label="新预约日期"]').setValue('2026-11-05')
    await click('确认复用')
    expect(wrapper.text()).toContain('原时段已过期')
    expect(write).not.toHaveBeenCalled()
  })
  it('guards concurrent submits and preserves input until the response', async () => {
    let resolve!: (value: unknown) => void
    vi.mocked(write).mockReturnValue(
      new Promise((done) => {
        resolve = done
      }),
    )
    await openReuse()
    await click('确认复用')
    await click('确认复用')
    expect(write).toHaveBeenCalledTimes(1)
    expect(wrapper.find('input[aria-label="新预约日期"]').attributes('disabled')).toBeDefined()
    resolve({ id: 42 })
    await flushPromises()
  })
  it('hides reuse when the student role is no longer present', async () => {
    useAuth().user!.roles = ['TECHNICIAN']
    await render()
    await tab('历史记录')
    expect(wrapper.text()).not.toContain('一键复用')
  })
  it('shows only persisted notifications and handles their request failure', async () => {
    await render()
    vi.mocked(request).mockResolvedValue([
      {
        id: 1,
        reservationId: 41,
        classroomName: '知行101',
        seatLabel: 'A01',
        type: 'NO_SHOW_CANCELLED',
        createdAt: record.startTime,
        notificationStatus: 'AVAILABLE',
      },
    ])
    await tab('站内提醒')
    expect(request).toHaveBeenLastCalledWith('/reservations/notifications?page=1&pageSize=50')
    expect(wrapper.text()).toContain('未签到，预约已取消')
    expect(wrapper.text()).toContain('不代表短信、邮件或微信已送达')
    vi.mocked(request).mockRejectedValue(
      new ApiError(503, 'UNAVAILABLE', '通知加载失败', 'm1-notify'),
    )
    await click('刷新记录')
    expect(wrapper.text()).toContain('通知加载失败（请求编号 m1-notify）')
    expect(wrapper.findAll('[data-testid="reservation-notification"]')).toHaveLength(0)
  })
  it('changes only the local window hint at the deadline', async () => {
    vi.mocked(request).mockResolvedValue([
      {
        ...record,
        status: 'PENDING',
        startTime: '2026-11-06T10:00:00+08:00',
        endTime: '2026-11-06T11:00:00+08:00',
        checkInDeadline: '2026-11-06T10:15:00+08:00',
      },
    ])
    await render()
    expect(wrapper.text()).toContain('即将开始，可到场签到')
    vi.setSystemTime(new Date('2026-11-06T10:15:00+08:00'))
    await vi.advanceTimersByTimeAsync(1000)
    expect(wrapper.text()).toContain('等待服务端确认')
    expect(wrapper.text()).toContain('待签到')
    expect(
      wrapper
        .findAll('button')
        .find((b) => b.text() === '去签到')!
        .attributes('disabled'),
    ).toBeDefined()
    expect(write).not.toHaveBeenCalled()
  })
})
