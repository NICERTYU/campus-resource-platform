import { beforeEach, expect, it, vi } from 'vitest'
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
    id: 3,
    username: 'admin',
    displayName: '管理员',
    department: 'CS',
    roles: ['ADMIN'],
  }
  vi.resetAllMocks()
  vi.mocked(request).mockImplementation(async (path) => {
    if (path === '/classrooms') return []
    if (path.includes('/admin/users'))
      return [{ id: 6, displayName: '新维修人员', roles: ['TECHNICIAN'] }]
    if (path.endsWith('/history'))
      return [
        {
          id: 1,
          action: 'RETURN',
          actorName: '管理员',
          reason: '仍然漏水',
          createdAt: '2026-11-03T00:00:00Z',
        },
      ]
    return [
      {
        id: 9,
        classroomName: 'A101',
        reporterName: '同学',
        status: 'ASSIGNED',
        technicianId: 4,
        description: '漏水',
      },
    ]
  })
})
function page() {
  return mount(Repairs, {
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
        ElInput: {
          props: ['modelValue', 'placeholder'],
          emits: ['update:modelValue'],
          template:
            '<textarea :placeholder="placeholder" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
        },
      },
    },
  })
}
it('requires a reassignment reason and sends the displayed previous technician', async () => {
  const wrapper = page()
  try {
    await flushPromises()
    await wrapper
      .findAll('button')
      .find((b) => b.text() === '改派')!
      .trigger('click')
    const confirm = () => wrapper.findAll('button').find((b) => b.text() === '确认分派维修人员')!
    await confirm().trigger('click')
    expect(write).not.toHaveBeenCalled()
    await wrapper.find('textarea[placeholder="填写改派原因"]').setValue('人员交接')
    await confirm().trigger('click')
    await flushPromises()
    expect(write).toHaveBeenCalledWith('/repair-orders/9/assign', {
      technicianId: 6,
      expectedTechnicianId: 4,
      reason: '人员交接',
    })
  } finally {
    wrapper.unmount()
  }
})
it('loads and displays immutable repair reasons on demand', async () => {
  const wrapper = page()
  try {
    await flushPromises()
    await wrapper
      .findAll('button')
      .find((b) => b.text() === '派单与返修记录')!
      .trigger('click')
    await flushPromises()
    expect(request).toHaveBeenCalledWith('/repair-orders/9/history')
    expect(wrapper.text()).toContain('仍然漏水')
    expect(wrapper.text()).toContain('退回维修')
  } finally {
    wrapper.unmount()
  }
})
