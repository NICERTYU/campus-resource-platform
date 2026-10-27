import { expect, test, type Page } from '@playwright/test'
import type { Application, User } from '../src/lib/types'
import type { ApprovalPolicy } from '../src/lib/applications'
const administrator: User = {
  id: 1,
  username: 'admin',
  displayName: '审批管理员',
  department: 'CS',
  roles: ['ADMIN'],
}
const record = (id: number, overrides: Partial<Application> = {}): Application => ({
  id,
  classroomId: 1,
  classroomName: `知行楼 ${id} 室`,
  applicantId: 2,
  applicantName: '杨同学',
  applicantDepartment: 'CS',
  startTime: '2030-10-28T10:00:00+08:00',
  endTime: '2030-10-28T11:00:00+08:00',
  purpose: '课程项目讨论与小组展示，预计参加 20 人。',
  status: 'PENDING',
  comment: null,
  pendingAction: null,
  lastError: null,
  currentStep: 1,
  totalSteps: 2,
  revision: 0,
  approvalDeadline: '2030-10-28T09:00:00+08:00',
  ...overrides,
})
// Fixture-only browser checks: these validate rendering and client guards, not backend authorization.
async function fixture(
  page: Page,
  rows: Application[],
  user = administrator,
  reviewSteps = [1, 2],
) {
  let writes = 0
  const revisions: number[] = []
  const policy: ApprovalPolicy = { steps: 2, timeout: 'PT24H', reviewSteps, legacyReview: true }
  await page.clock.install({ time: new Date('2030-10-27T10:00:00+08:00') })
  await page.route('**/api/v1/**', async (route) => {
    const path = new URL(route.request().url()).pathname.replace('/api/v1', '')
    let body: unknown,
      status = 200
    if (path === '/auth/csrf') body = { token: 'fixture-csrf', headerName: 'X-CSRF-TOKEN' }
    else if (path === '/auth/me') body = user
    else if (path === '/classrooms') body = []
    else if (path === '/classroom-applications/policy') body = policy
    else if (path === '/classroom-applications') body = rows
    else if (path.endsWith('/history'))
      body = [
        {
          id: 1,
          step: 2,
          revision: 3,
          action: 'WITHDRAW',
          actorId: 2,
          actorName: '杨同学',
          comment: '撤销活动申请',
          requestId: `fixture-release-${'a'.repeat(80)}`,
          createdAt: '2030-10-27T09:50:00+08:00',
        },
      ]
    else if (path.endsWith('/approve')) {
      writes++
      revisions.push(route.request().postDataJSON().revision)
      expect(route.request().headers()['idempotency-key']).toBeTruthy()
      rows[0] = { ...rows[0]!, currentStep: 2, revision: 1 }
      status = 409
      body = {
        code: 'STATE_CONFLICT',
        message: '申请已被更新，请刷新后重新操作',
        requestId: 'fixture-conflict-409',
      }
    } else throw new Error(`Unexpected fixture API ${path}`)
    await route.fulfill({ status, json: body })
  })
  await page.goto('/applications')
  await expect(page.locator('.workflow-card')).toHaveCount(rows.length)
  return { writes: () => writes, revisions }
}
async function noHorizontalOverflow(page: Page) {
  expect(
    await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1),
  ).toBe(true)
}
test('@fixture M2 admins see only allowed stage and department; applicant is read-only for review', async ({
  page,
}) => {
  await fixture(
    page,
    [record(101), record(102, { currentStep: 2 }), record(103, { applicantDepartment: 'ART' })],
    administrator,
    [1],
  )
  await expect(page.getByRole('button', { name: '通过申请' })).toHaveCount(1)
  await expect(page.locator('.workflow-card').nth(1)).toContainText('第 2 级 · 资源终审')
  await noHorizontalOverflow(page)
  await page.unrouteAll()
  await fixture(page, [record(101, { currentStep: 2 })], {
    ...administrator,
    id: 2,
    roles: ['APPLICANT'],
  })
  await expect(page.getByRole('button', { name: '通过申请' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: '审批记录' })).toBeVisible()
})
test('@fixture M2 real error payload remains visible and stale revision cannot be replayed', async ({
  page,
}) => {
  const state = await fixture(page, [record(101)])
  await page.getByRole('button', { name: '通过申请' }).click()
  await page.getByRole('button', { name: '确认通过', exact: true }).click()
  await expect(page.getByRole('dialog')).toContainText('请求编号 fixture-conflict-409')
  await expect(page.getByRole('dialog')).toContainText('不会自动重放操作')
  await expect(page.getByRole('button', { name: '确认通过', exact: true })).toBeDisabled()
  expect(state.writes()).toBe(1)
  expect(state.revisions).toEqual([0])
  await page.getByRole('dialog').getByRole('button', { name: '取消', exact: true }).click()
  await expect(page.locator('.workflow-card')).toContainText('审批节点：2 / 2')
  await page.getByRole('button', { name: '通过申请' }).click()
  await expect(page.getByRole('dialog')).toContainText('版本 1')
})
test('@fixture M2 deadline while confirming disables review and waits for server expiry', async ({
  page,
}) => {
  await fixture(page, [record(101, { approvalDeadline: '2030-10-27T10:00:01+08:00' })])
  await page.getByRole('button', { name: '通过申请' }).click()
  await page.clock.runFor(1100)
  await expect(page.getByRole('button', { name: '确认通过', exact: true })).toBeDisabled()
  await expect(page.locator('.workflow-card')).toContainText('等待服务端确认超时撤销')
  await expect(page.locator('.workflow-card')).toContainText('待审批')
})
for (const width of [1440, 380]) {
  test(`@fixture M2 ${width}px layout, honest release status and long request id`, async ({
    page,
  }, testInfo) => {
    await page.setViewportSize({ width, height: 1000 })
    await fixture(page, [
      record(101),
      record(102, { currentStep: 2 }),
      record(103, {
        status: 'PROCESSING',
        currentStep: 2,
        revision: 3,
        pendingAction: 'WITHDRAW',
        lastError: '资源服务暂不可用，正在重试解除占用。',
      }),
    ])
    await expect(page.locator('.workflow-card').nth(2)).toContainText('尚未确认恢复')
    await expect(page.locator('.workflow-card').nth(2)).toContainText('fixture-release-')
    await noHorizontalOverflow(page)
    await page.screenshot({
      path: testInfo.outputPath(`m2-${width}.png`),
      fullPage: true,
      animations: 'disabled',
    })
    await page.locator('.workflow-card').nth(2).getByRole('button', { name: '审批记录' }).click()
    await expect(page.getByRole('dialog')).toContainText('请求编号：fixture-release-')
    await noHorizontalOverflow(page)
    const dialog = await page.getByRole('dialog').boundingBox()
    expect(dialog!.x).toBeGreaterThanOrEqual(0)
    expect(dialog!.x + dialog!.width).toBeLessThanOrEqual(width)
    await page.screenshot({
      path: testInfo.outputPath(`m2-history-${width}.png`),
      fullPage: true,
      animations: 'disabled',
    })
  })
}
