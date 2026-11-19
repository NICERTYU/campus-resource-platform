import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ElementPlus from 'element-plus'
import Statistics from '../pages/Statistics.vue'
import { useAuth } from '../stores/auth'
import { ApiError, request } from '../lib/api'
import type { ReservationStatistics } from '../lib/types'
vi.mock('../lib/api', async () => ({ ...(await vi.importActual('../lib/api')), request: vi.fn() }))
const report: ReservationStatistics = {
  startDate: '2026-11-01',
  endDate: '2026-11-07',
  rows: [
    {
      classroomId: 1,
      classroomName: '知行101',
      reservationCount: 6,
      cancelledCount: 2,
      noShowCount: 1,
      maintenanceCancelledCount: 1,
      bookedSeatHours: 20,
      capacitySeatHours: 40,
      maintenanceSeatHours: 2,
      utilization: 0.5,
    },
    {
      classroomId: 2,
      classroomName: '知行102',
      reservationCount: 4,
      cancelledCount: 1,
      noShowCount: 0,
      maintenanceCancelledCount: 0,
      bookedSeatHours: 4,
      capacitySeatHours: 8,
      maintenanceSeatHours: 0,
      utilization: 0.5,
    },
  ],
}
let wrapper: VueWrapper
beforeEach(() => {
  vi.resetAllMocks()
  setActivePinia(createPinia())
  useAuth().user = {
    id: 1,
    username: 'admin',
    displayName: '管理员',
    department: 'CS',
    roles: ['ADMIN'],
  }
  vi.mocked(request).mockImplementation(async (path) =>
    path === '/admin/classrooms' ? [{ id: 1, name: '知行101' }] : report,
  )
})
afterEach(() => wrapper?.unmount())
async function render() {
  wrapper = mount(Statistics, { global: { plugins: [ElementPlus] } })
  await flushPromises()
}
async function query() {
  await wrapper
    .findAll('button')
    .find((b) => b.text() === '查询统计')!
    .trigger('click')
  await flushPromises()
}
describe('M1 administrator statistics', () => {
  it('renders server aggregates and explicit units rather than page record counts', async () => {
    await render()
    expect(wrapper.find('[data-testid="statistics-summary"]').text()).toContain('预约数量10')
    expect(wrapper.text()).toContain('50.0%')
    expect(wrapper.text()).toContain('2.0 座位小时')
    expect(wrapper.text()).toContain('不能当作实际到场率')
    expect(wrapper.text()).toContain('2026-11-01 至 2026-11-07')
  })
  it('invalid filters clear previous data and do not make a new request', async () => {
    await render()
    const calls = vi.mocked(request).mock.calls.length
    await wrapper.find('input[aria-label="统计开始日期"]').setValue('2026-11-20')
    await wrapper.find('input[aria-label="统计结束日期"]').setValue('2026-11-01')
    await query()
    expect(request).toHaveBeenCalledTimes(calls)
    expect(wrapper.text()).toContain('不超过31天')
    expect(wrapper.find('[data-testid="statistics-summary"]').exists()).toBe(false)
  })
  it('reports a real forbidden response and never keeps stale totals as success', async () => {
    await render()
    vi.mocked(request).mockRejectedValue(new ApiError(403, 'FORBIDDEN', '无权查看', 'stats-403'))
    await query()
    expect(wrapper.text()).toContain('无权查看（请求编号 stats-403）')
    expect(wrapper.findAll('[data-testid="statistics-room"]')).toHaveLength(0)
  })
  it('does not request or reveal aggregate data for a student', async () => {
    useAuth().user!.roles = ['STUDENT']
    await render()
    expect(request).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('仅管理员')
  })
  it('discards a slow response after administrator permission is removed', async () => {
    let resolve!: (value: unknown) => void
    vi.mocked(request).mockImplementation(async (path) =>
      path === '/admin/classrooms'
        ? []
        : new Promise((done) => {
            resolve = done
          }),
    )
    await render()
    useAuth().user!.roles = ['STUDENT']
    await flushPromises()
    resolve(report)
    await flushPromises()
    expect(wrapper.find('[data-testid="statistics-summary"]').exists()).toBe(false)
  })
  it('shows zero capacity as undefined utilization, not a made-up percentage', async () => {
    vi.mocked(request).mockImplementation(async (path) =>
      path === '/admin/classrooms'
        ? []
        : {
            ...report,
            rows: [
              { ...report.rows[0]!, capacitySeatHours: 0, bookedSeatHours: 0, utilization: null },
            ],
          },
    )
    await render()
    expect(wrapper.text()).toContain('无可用容量')
    expect(wrapper.text()).not.toContain('NaN')
  })
})
