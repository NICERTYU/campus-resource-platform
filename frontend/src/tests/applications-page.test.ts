import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ElementPlus from 'element-plus'
import Applications from '../pages/Applications.vue'
import { useAuth } from '../stores/auth'
import { request, write, ApiError } from '../lib/api'
import type { Application, ApprovalHistory } from '../lib/types'
vi.mock('../lib/api', async () => {
  const actual = await vi.importActual<typeof import('../lib/api')>('../lib/api')
  return { ...actual, request: vi.fn(), write: vi.fn() }
})
const record = (id = 101): Application => ({
  id,
  classroomId: 1,
  classroomName: `验收教室${id}`,
  applicantId: 2,
  applicantName: '申请人',
  applicantDepartment: 'CS',
  startTime: '2030-10-28T10:00:00+08:00',
  endTime: '2030-10-28T11:00:00+08:00',
  purpose: '课程活动',
  status: 'PENDING',
  comment: null,
  pendingAction: null,
  lastError: null,
  currentStep: 1,
  totalSteps: 2,
  revision: 0,
  approvalDeadline: '2030-10-28T09:00:00+08:00',
})
const entry = (id: number, requestId = `req-${id}`): ApprovalHistory => ({
  id,
  step: 1,
  revision: 0,
  action: 'CREATE',
  actorId: 2,
  actorName: '申请人',
  comment: `历史内容${id}`,
  requestId,
  createdAt: '2030-10-27T10:00:00+08:00',
})
let rows: Application[], wrappers: VueWrapper[]
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => {
    resolve = done
  })
  return { promise, resolve }
}
async function render() {
  const wrapper = mount(Applications, {
    attachTo: document.body,
    global: { plugins: [ElementPlus], stubs: { teleport: true } },
  })
  wrappers.push(wrapper)
  await flushPromises()
  return wrapper
}
function button(wrapper: VueWrapper, text: string) {
  const result = wrapper.findAll('button').find((candidate) => candidate.text() === text)
  if (!result) throw new Error(`missing button ${text}`)
  return result
}
beforeEach(() => {
  vi.useFakeTimers()
  vi.setSystemTime(new Date('2030-10-27T10:00:00+08:00'))
  vi.resetAllMocks()
  setActivePinia(createPinia())
  wrappers = []
  rows = [record()]
  useAuth().user = {
    id: 1,
    username: 'admin',
    displayName: '管理员',
    department: 'CS',
    roles: ['ADMIN'],
  }
  vi.mocked(request).mockImplementation(async (path) => {
    if (path === '/classroom-applications/policy')
      return { steps: 2, timeout: 'PT24H', reviewSteps: [1, 2], legacyReview: true }
    if (path === '/classrooms') return []
    if (path.endsWith('/history')) return [entry(1)]
    return structuredClone(rows)
  })
})
afterEach(() => {
  for (const wrapper of wrappers) wrapper.unmount()
  document.body.innerHTML = ''
  vi.useRealTimers()
})
describe('M2 approval page acceptance', () => {
  it('keeps applicant node and history visible without exposing review controls', async () => {
    useAuth().user = {
      id: 2,
      username: 'applicant',
      displayName: '申请人',
      department: 'CS',
      roles: ['APPLICANT'],
    }
    const wrapper = await render()
    expect(wrapper.text()).toContain('第 1 级 · 院系初审')
    expect(wrapper.findAll('button').some((b) => b.text() === '通过申请')).toBe(false)
    await button(wrapper, '审批记录').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('请求编号：req-1')
  })
  it('fails closed when policy fails but still loads records and rooms independently', async () => {
    vi.mocked(request).mockImplementation(async (path) => {
      if (path.endsWith('/policy'))
        throw new ApiError(503, 'UNAVAILABLE', '策略不可用', 'req-policy')
      if (path === '/classrooms') return []
      return rows
    })
    const wrapper = await render()
    expect(wrapper.findAll('.workflow-card')).toHaveLength(1)
    expect(wrapper.text()).toContain('req-policy')
    expect(wrapper.findAll('button').some((b) => b.text() === '通过申请')).toBe(false)
    expect(request).toHaveBeenCalledWith('/classrooms')
  })
  it('refuses an old dialog after a refresh changes revision, without a write', async () => {
    const wrapper = await render()
    await button(wrapper, '通过申请').trigger('click')
    rows[0] = { ...rows[0]!, currentStep: 2, revision: 1 }
    await button(wrapper, '刷新').trigger('click')
    await flushPromises()
    expect(button(wrapper, '确认通过').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('记录版本已变化')
    await button(wrapper, '确认通过').trigger('click')
    expect(write).not.toHaveBeenCalled()
  })
  it('disables an open confirmation exactly when its deadline passes without inventing EXPIRED', async () => {
    rows[0]!.approvalDeadline = '2030-10-27T10:00:01+08:00'
    const wrapper = await render()
    await button(wrapper, '通过申请').trigger('click')
    await vi.advanceTimersByTimeAsync(1000)
    expect(button(wrapper, '确认通过').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('等待服务端确认超时撤销')
    expect(wrapper.find('.workflow-card').text()).toContain('待审批')
    expect(write).not.toHaveBeenCalled()
  })
  it('preserves 409 request number, refreshes, and blocks replay even if revision is unchanged', async () => {
    vi.mocked(write).mockRejectedValue(
      new ApiError(409, 'STATE_CONFLICT', '资源冲突', 'req-conflict'),
    )
    const wrapper = await render()
    await button(wrapper, '通过申请').trigger('click')
    await button(wrapper, '确认通过').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('资源冲突（请求编号 req-conflict）')
    expect(wrapper.text()).toContain('不会自动重放操作')
    expect(button(wrapper, '确认通过').attributes('disabled')).toBeDefined()
    await button(wrapper, '确认通过').trigger('click')
    expect(write).toHaveBeenCalledTimes(1)
    expect(write).toHaveBeenCalledWith('/classroom-applications/101/approve', {
      comment: '',
      revision: 0,
    })
  })
  it('prevents duplicate writes and reports first-level success without claiming occupation', async () => {
    const pending = deferred<Application>()
    vi.mocked(write).mockReturnValue(pending.promise)
    const wrapper = await render()
    await button(wrapper, '通过申请').trigger('click')
    await button(wrapper, '确认通过').trigger('click')
    await button(wrapper, '确认通过').trigger('click')
    expect(write).toHaveBeenCalledTimes(1)
    rows[0] = { ...rows[0]!, currentStep: 2, revision: 1 }
    pending.resolve(rows[0]!)
    await flushPromises()
    expect(wrapper.text()).toContain('本级审核已通过，等待下一审批节点；尚未形成整室占用。')
  })
  it('distinguishes accepted resource synchronization and traces only the current revision', async () => {
    rows[0] = { ...rows[0]!, status: 'PROCESSING', pendingAction: 'WITHDRAW', revision: 3 }
    vi.mocked(request).mockImplementation(async (path) => {
      if (path.endsWith('/policy'))
        return { steps: 2, timeout: 'PT24H', reviewSteps: [1, 2], legacyReview: true }
      if (path === '/classrooms') return []
      if (path.endsWith('/history'))
        return [
          { ...entry(1, 'wrong-revision'), revision: 2 },
          { ...entry(2, 'release-request'), revision: 3 },
        ]
      return rows
    })
    const wrapper = await render()
    expect(wrapper.text()).toContain('尚未确认恢复')
    expect(wrapper.text()).toContain('请求编号：release-request')
    expect(wrapper.find('.workflow-card').text()).not.toContain('wrong-revision')
  })
  it('opens history immediately, clears old content and rejects late responses from other records', async () => {
    rows.push(record(102))
    const first = deferred<ApprovalHistory[]>(),
      second = deferred<ApprovalHistory[]>()
    const original = vi.mocked(request).getMockImplementation()!
    vi.mocked(request).mockImplementation((path) =>
      path.endsWith('/101/history')
        ? first.promise
        : path.endsWith('/102/history')
          ? second.promise
          : original(path),
    )
    const wrapper = await render()
    await wrapper
      .findAll('.workflow-card')[0]!
      .findAll('button')
      .find((b) => b.text() === '审批记录')!
      .trigger('click')
    expect(wrapper.text()).toContain('正在加载审批记录')
    await wrapper
      .findAll('.workflow-card')[1]!
      .findAll('button')
      .find((b) => b.text() === '审批记录')!
      .trigger('click')
    second.resolve([entry(102)])
    await flushPromises()
    first.resolve([entry(101)])
    await flushPromises()
    expect(wrapper.text()).toContain('历史内容102')
    expect(wrapper.text()).not.toContain('历史内容101')
  })
  it('shows history failure with its request id and supports retry without stale entries', async () => {
    const wrapper = await render()
    vi.mocked(request).mockRejectedValueOnce(
      new ApiError(403, 'FORBIDDEN', '无权查看', 'req-history'),
    )
    await button(wrapper, '审批记录').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('req-history')
    expect(wrapper.findAll('.approval-history')).toHaveLength(0)
    await button(wrapper, '重试加载').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('历史内容1')
    expect(wrapper.text()).not.toContain('req-history')
  })
  it.each([401, 403])(
    'clears previously visible private records after list HTTP %s',
    async (status) => {
      const wrapper = await render()
      expect(wrapper.findAll('.workflow-card')).toHaveLength(1)
      vi.mocked(request).mockRejectedValue(
        new ApiError(status, 'FORBIDDEN', '会话或权限变更', 'req-access'),
      )
      await button(wrapper, '刷新').trigger('click')
      await flushPromises()
      expect(wrapper.findAll('.workflow-card')).toHaveLength(0)
      expect(wrapper.text()).toContain('req-access')
    },
  )
  it('invalidates late requests and removes polling on unmount', async () => {
    const wrapper = await render(),
      pending = deferred<Application[]>()
    vi.mocked(request).mockReturnValue(pending.promise)
    await button(wrapper, '刷新').trigger('click')
    wrapper.unmount()
    wrappers = []
    const calls = vi.mocked(request).mock.calls.length
    pending.resolve([record(999)])
    await flushPromises()
    await vi.advanceTimersByTimeAsync(10000)
    expect(request).toHaveBeenCalledTimes(calls)
  })
  it('checks wall time at confirmation even before the next one-second UI tick', async () => {
    rows[0]!.approvalDeadline = '2030-10-27T10:00:00.500+08:00'
    const wrapper = await render()
    await button(wrapper, '通过申请').trigger('click')
    vi.setSystemTime(new Date('2030-10-27T10:00:00.500+08:00'))
    await button(wrapper, '确认通过').trigger('click')
    expect(write).not.toHaveBeenCalled()
    expect(button(wrapper, '确认通过').attributes('disabled')).toBeDefined()
  })
  it('clears administrator records on same-account role demotion and rejects late responses', async () => {
    const wrapper = await render(),
      pending = deferred<Application[]>()
    vi.mocked(request).mockReturnValue(pending.promise)
    await button(wrapper, '刷新').trigger('click')
    useAuth().user = { ...useAuth().user!, roles: ['STUDENT'] }
    await flushPromises()
    pending.resolve([record(999)])
    await flushPromises()
    expect(wrapper.findAll('.workflow-card')).toHaveLength(0)
  })
  it('does not pile up silent polls while a slow list request is still pending', async () => {
    await render()
    const pending = deferred<Application[]>()
    const original = vi.mocked(request).getMockImplementation()!
    vi.mocked(request).mockImplementation((path) =>
      path.includes('?page=') ? pending.promise : original(path),
    )
    await vi.advanceTimersByTimeAsync(5000)
    const calls = vi.mocked(request).mock.calls.length
    await vi.advanceTimersByTimeAsync(15000)
    expect(request).toHaveBeenCalledTimes(calls)
    pending.resolve(rows)
    await flushPromises()
  })
})
