import { describe, expect, it, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ElementPlus from 'element-plus'
import Seats from '../pages/Seats.vue'
import { useAuth } from '../stores/auth'
import { request, write } from '../lib/api'
vi.mock('../lib/api', async () => {
  const actual = await vi.importActual<typeof import('../lib/api')>('../lib/api')
  return { ...actual, request: vi.fn(), write: vi.fn() }
})
beforeEach(() => {
  setActivePinia(createPinia())
  useAuth().user = {
    id: 1,
    username: 'student',
    displayName: '学生',
    department: 'CS',
    roles: ['STUDENT'],
  }
  vi.resetAllMocks()
  vi.mocked(request).mockImplementation(async (path) =>
    path === '/classrooms'
      ? [
          {
            id: 1,
            name: '知行101',
            building: '知行楼',
            floor: 1,
            department: null,
            openTime: '08:00:00',
            closeTime: '22:00:00',
            enabled: true,
            seatCount: 2,
          },
        ]
      : [
          {
            id: 1,
            classroomId: 1,
            label: 'A01',
            hasPower: true,
            available: true,
            restrictionTypes: [],
          },
          {
            id: 2,
            classroomId: 1,
            label: 'A02',
            hasPower: false,
            available: false,
            restrictionTypes: ['M2'],
          },
        ],
  )
})
describe('available seat interaction', () => {
  it('renders availability clearly and prevents selecting unavailable seats', async () => {
    const wrapper = mount(Seats, {
      global: {
        plugins: [ElementPlus],
        stubs: {
          RouterLink: { template: '<a><slot/></a>' },
          ElTooltip: { template: '<div><slot/></div>' },
        },
      },
    })
    await flushPromises()
    const seats = wrapper.findAll('button.seat')
    expect(seats).toHaveLength(2)
    expect(seats[0].attributes('disabled')).toBeUndefined()
    expect(seats[1].attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('可预约')
    expect(wrapper.text()).toContain('不可预约')
    wrapper.unmount()
  })
  it('opens confirmation and sends reservation for the selected seat', async () => {
    vi.mocked(write).mockResolvedValue({ id: 1 })
    const wrapper = mount(Seats, {
      global: {
        plugins: [ElementPlus],
        stubs: {
          RouterLink: { template: '<a><slot/></a>' },
          ElTooltip: { template: '<div><slot/></div>' },
          ElDialog: {
            template: '<div v-if="modelValue"><slot/><slot name="footer"/></div>',
            props: ['modelValue'],
          },
        },
      },
    })
    await flushPromises()
    await wrapper.find('button.seat').trigger('click')
    expect(wrapper.text()).toContain('知行101 · A01')
    const confirm = wrapper.findAll('button').find((b) => b.text() === '确认预约')
    await confirm!.trigger('click')
    await flushPromises()
    expect(write).toHaveBeenCalledWith(
      '/reservations',
      expect.objectContaining({ seatId: 1, startTime: expect.stringContaining('+08:00') }),
    )
    wrapper.unmount()
  })
  it('disables reservation selection for a non-student account', async () => {
    useAuth().user!.roles = ['TECHNICIAN']
    const wrapper = mount(Seats, {
      global: {
        plugins: [ElementPlus],
        stubs: { RouterLink: true, ElTooltip: { template: '<div><slot/></div>' } },
      },
    })
    await flushPromises()
    expect(wrapper.find('button.seat').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('预约座位需要学生角色')
    wrapper.unmount()
  })
})
