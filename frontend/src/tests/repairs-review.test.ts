import { beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ElementPlus from 'element-plus'
import Repairs from '../pages/Repairs.vue'
import { useAuth } from '../stores/auth'
import { request, write, ApiError } from '../lib/api'

vi.mock('../lib/api', async () => {
  const actual = await vi.importActual<typeof import('../lib/api')>('../lib/api')
  return { ...actual, request: vi.fn(), write: vi.fn() }
})
let state = 'ACCEPTED'
let reporterId = 1
let saved: unknown = undefined
beforeEach(() => {
  setActivePinia(createPinia())
  useAuth().user = {
    id: 1,
    username: 'student',
    displayName: '同学',
    department: 'CS',
    roles: ['STUDENT'],
  }
  state = 'ACCEPTED'
  reporterId = 1
  saved = undefined
  vi.resetAllMocks()
  vi.mocked(request).mockImplementation(async (path) => {
    if (path === '/classrooms' || path.startsWith('/admin/users') || path.includes('/escalations'))
      return []
    if (path.endsWith('/review')) return saved
    return [
      {
        id: 9,
        reporterId,
        classroomName: 'A101',
        reporterName: '同学',
        status: state,
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
          props: ['modelValue', 'title'],
          template:
            '<section v-if="modelValue" :aria-label="title"><slot/><slot name="footer"/></section>',
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
        ElInputNumber: {
          props: ['modelValue'],
          emits: ['update:modelValue'],
          template:
            '<input aria-label="维修评分" type="number" :value="modelValue" @input="$emit(\'update:modelValue\', Number($event.target.value))" />',
        },
      },
    },
  })
}
async function open(wrapper: ReturnType<typeof page>) {
  await flushPromises()
  await wrapper
    .findAll('button')
    .find((b) => b.text() === '维修评价')!
    .trigger('click')
  await flushPromises()
}
it('submits a reporter rating and shows only the server-confirmed result as plain text', async () => {
  vi.mocked(write).mockResolvedValue({
    repairId: 9,
    reviewerId: 1,
    rating: 4,
    comment: '<b>满意</b>',
    reviewedAt: '2026-11-03T00:00:00Z',
  })
  const wrapper = page()
  try {
    await open(wrapper)
    await wrapper.find('input[aria-label="维修评分"]').setValue(4)
    await wrapper
      .find('textarea[placeholder="评价内容（可选，最多1000字）"]')
      .setValue(' <b>满意</b> ')
    await wrapper
      .findAll('button')
      .find((b) => b.text() === '提交评价')!
      .trigger('click')
    await flushPromises()
    expect(write).toHaveBeenCalledWith('/repair-orders/9/review', {
      rating: 4,
      comment: '<b>满意</b>',
    })
    expect(wrapper.get('[data-testid="repair-review"]').text()).toContain('4/5')
    expect(wrapper.get('[data-testid="repair-review"]').html()).toContain('&lt;b&gt;满意&lt;/b&gt;')
    expect(wrapper.find('section[aria-label="维修评价"]').exists()).toBe(false)
  } finally {
    wrapper.unmount()
  }
})
it('opens existing feedback for reading without offering another submission', async () => {
  saved = { repairId: 9, rating: 3, comment: '已经评价', reviewedAt: '2026-11-03T00:00:00Z' }
  const wrapper = page()
  try {
    await open(wrapper)
    expect(wrapper.text()).toContain('已经评价')
    expect(wrapper.findAll('button').some((b) => b.text() === '提交评价')).toBe(false)
    expect(write).not.toHaveBeenCalled()
  } finally {
    wrapper.unmount()
  }
})
it('rejects a score outside the allowed range before making a write request', async () => {
  const wrapper = page()
  try {
    await open(wrapper)
    await wrapper.find('input[aria-label="维修评分"]').setValue(6)
    await wrapper
      .findAll('button')
      .find((b) => b.text() === '提交评价')!
      .trigger('click')
    expect(write).not.toHaveBeenCalled()
  } finally {
    wrapper.unmount()
  }
})
it('does not expose submission for a different reporter or before acceptance', async () => {
  for (const wrongReporter of [true, false]) {
    reporterId = wrongReporter ? 99 : 1
    state = wrongReporter ? 'ACCEPTED' : 'AWAITING_ACCEPTANCE'
    const wrapper = page()
    try {
      await flushPromises()
      expect(wrapper.findAll('button').some((b) => b.text() === '维修评价')).toBe(false)
    } finally {
      wrapper.unmount()
    }
  }
})
it('keeps unsaved feedback open when the backend rejects a duplicate request', async () => {
  vi.mocked(write).mockRejectedValue(new ApiError(409, 'STATE_CONFLICT', '工单已评价'))
  const wrapper = page()
  try {
    await open(wrapper)
    await wrapper
      .findAll('button')
      .find((b) => b.text() === '提交评价')!
      .trigger('click')
    await flushPromises()
    expect(wrapper.find('section[aria-label="维修评价"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="repair-review"]').exists()).toBe(false)
  } finally {
    wrapper.unmount()
  }
})
