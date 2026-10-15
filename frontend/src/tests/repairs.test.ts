import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ElementPlus from 'element-plus'
import Repairs from '../pages/Repairs.vue'
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
    username: 'admin',
    displayName: '管理员',
    department: 'CS',
    roles: ['ADMIN'],
  }
  vi.resetAllMocks()
})

describe('repair technician discovery', () => {
  it('assigns a later-created technician even when the general user list contains 200 students', async () => {
    const students = Array.from({ length: 200 }, (_, index) => ({
      id: index + 1,
      username: `student_${index}`,
      displayName: `学生${index}`,
      department: 'CS',
      roles: ['STUDENT'],
    }))
    vi.mocked(request).mockImplementation(async (path) => {
      if (path === '/admin/users') return students
      if (path === '/admin/users?role=TECHNICIAN')
        return [
          {
            id: 211,
            username: 'late_technician',
            displayName: '新增维修人员',
            department: 'CS',
            roles: ['TECHNICIAN'],
          },
        ]
      if (path === '/classrooms') return []
      return [
        {
          id: 9,
          classroomId: 1,
          classroomName: '图书馆',
          reporterId: 2,
          reporterName: '同学',
          description: '灯具损坏',
          status: 'REPORTED',
          technicianId: null,
          technicianName: null,
          pendingAction: null,
          lastError: null,
        },
      ]
    })
    const wrapper = mount(Repairs, {
      global: {
        plugins: [ElementPlus],
        stubs: {
          ElDialog: {
            props: ['modelValue'],
            template: '<section v-if="modelValue"><slot/><slot name="footer"/></section>',
          },
          ElSelect: { template: '<select><slot/></select>' },
          ElOption: {
            props: ['label', 'value'],
            template: '<option :value="value">{{ label }}</option>',
          },
        },
      },
    })
    try {
      await flushPromises()
      await wrapper
        .findAll('button')
        .find((button) => button.text() === '派单')!
        .trigger('click')
      expect(wrapper.text()).toContain('新增维修人员')
      await wrapper
        .findAll('button')
        .find((button) => button.text() === '确认分派维修人员')!
        .trigger('click')
      await flushPromises()
      expect(write).toHaveBeenCalledWith('/repair-orders/9/assign', { technicianId: 211 })
      expect(request).not.toHaveBeenCalledWith('/admin/users')
    } finally {
      wrapper.unmount()
    }
  })
})
