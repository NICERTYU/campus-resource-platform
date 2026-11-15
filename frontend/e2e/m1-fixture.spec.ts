import { expect, test, type Page } from '@playwright/test'
import type { Reservation, User } from '../src/lib/types'
const student: User = {
  id: 1,
  username: 'student',
  displayName: '程同学',
  department: 'CS',
  roles: ['STUDENT'],
}
const record = (overrides: Partial<Reservation> = {}): Reservation => ({
  id: 41,
  seatId: 5,
  classroomId: 1,
  classroomName: '知行楼101',
  seatLabel: 'A01',
  startTime: '2030-11-05T10:00:00+08:00',
  endTime: '2030-11-05T11:00:00+08:00',
  status: 'COMPLETED',
  checkedInAt: '2030-11-05T09:50:00+08:00',
  checkInDeadline: '2030-11-05T10:15:00+08:00',
  cancelReason: null,
  ...overrides,
})
// These fixtures exercise browser layout, error display and local hints; live authorization is separate.
async function fixture(page: Page, user = student) {
  let failure = 409,
    writes = 0,
    statisticsFailure = false
  const queries: string[] = []
  await page.clock.install({ time: new Date('2030-11-06T09:50:00+08:00') })
  await page.route('**/api/v1/**', async (route) => {
    const url = new URL(route.request().url()),
      path = url.pathname.replace('/api/v1', '')
    let body: unknown,
      status = 200
    if (path === '/auth/me') body = user
    else if (path === '/auth/csrf') body = { token: 'fixture', headerName: 'X-CSRF-TOKEN' }
    else if (path === '/classrooms' || path === '/admin/classrooms')
      body = [
        {
          id: 1,
          name: '知行楼101',
          building: '知行楼',
          floor: 1,
          enabled: true,
          seatCount: 1,
          openTime: '08:00',
          closeTime: '22:00',
          department: null,
        },
      ]
    else if (path === '/seats/availability') body = []
    else if (path === '/reservations/mine')
      body =
        url.searchParams.get('statusGroup') === 'history'
          ? [record()]
          : [
              record({
                status: 'PENDING',
                startTime: '2030-11-06T10:00:00+08:00',
                endTime: '2030-11-06T11:00:00+08:00',
                checkInDeadline: '2030-11-06T10:15:00+08:00',
                checkedInAt: null,
              }),
            ]
    else if (path === '/reservations/notifications')
      body = ['STARTING_SOON', 'CHECK_IN_REMINDER', 'NO_SHOW_CANCELLED'].map((type, i) => ({
        id: i + 1,
        reservationId: 41,
        classroomName: '知行楼101',
        seatLabel: 'A01',
        type,
        createdAt: '2030-11-06T09:50:00+08:00',
        notificationStatus: 'AVAILABLE',
      }))
    else if (path === '/reservations/41/reuse') {
      writes++
      status = failure
      expect(route.request().headers()['idempotency-key']).toBeTruthy()
      expect(route.request().postDataJSON()).toEqual({
        startTime: '2030-11-07T10:00:00+08:00',
        endTime: '2030-11-07T11:00:00+08:00',
      })
      body = {
        code: 'FAILED',
        message:
          failure === 400
            ? '所选时段已过期'
            : failure === 403
              ? '没有权限复用'
              : '所选座位发生冲突',
        requestId: `fixture-m1-${failure}-${'a'.repeat(70)}`,
      }
    } else if (path === '/admin/reservation-statistics') {
      queries.push(url.search)
      if (statisticsFailure) {
        status = 503
        body = { code: 'UNAVAILABLE', message: '统计暂不可用', requestId: 'fixture-stats-503' }
      } else
        body = {
          startDate: url.searchParams.get('startDate'),
          endDate: url.searchParams.get('endDate'),
          rows: [
            {
              classroomId: 1,
              classroomName: '知行楼101-较长的教室名称用于验证窄屏换行',
              reservationCount: 12,
              cancelledCount: 2,
              noShowCount: 1,
              maintenanceCancelledCount: 1,
              bookedSeatHours: 8,
              capacitySeatHours: 40,
              maintenanceSeatHours: 3,
              utilization: 0.2,
            },
          ],
        }
    } else throw new Error(`Unexpected M1 fixture ${path}`)
    await route.fulfill({ status, json: body })
  })
  return {
    setFailure: (value: number) => {
      failure = value
    },
    writes: () => writes,
    queries,
    failStatistics: () => {
      statisticsFailure = true
    },
  }
}
async function fits(page: Page) {
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(
    true,
  )
}
test('@fixture M1 复用的过期、无权限和冲突保留确认框及真实编号', async ({ page }) => {
  const state = await fixture(page)
  await page.goto('/bookings')
  await page.getByRole('tab', { name: '历史记录', exact: true }).click()
  await page.getByRole('button', { name: '一键复用' }).click()
  for (const status of [400, 403, 409]) {
    state.setFailure(status)
    await page.getByRole('button', { name: '确认复用', exact: true }).click()
    await expect(page.getByRole('dialog')).toContainText(`fixture-m1-${status}`)
  }
  expect(state.writes()).toBe(3)
  await page.setViewportSize({ width: 380, height: 844 })
  await fits(page)
})
test('@fixture M1 过期日期在本地提示且不会创建新预约', async ({ page }) => {
  const state = await fixture(page)
  await page.goto('/bookings')
  await page.getByRole('tab', { name: '历史记录', exact: true }).click()
  await page.getByRole('button', { name: '一键复用' }).click()
  await page.locator('input[aria-label="新预约日期"]').fill('2030-11-05')
  await page.getByRole('button', { name: '确认复用', exact: true }).click()
  await expect(page.getByRole('dialog')).toContainText('原时段已过期')
  expect(state.writes()).toBe(0)
})
test('@fixture M1 提醒不冒充送达，截止时钟不自行取消预约', async ({ page }) => {
  await fixture(page)
  await page.goto('/bookings')
  await expect(page.locator('.booking-row')).toContainText('即将开始，可到场签到')
  await page.clock.pauseAt(new Date('2030-11-06T10:15:00+08:00'))
  await expect(page.locator('.booking-row')).toContainText('等待服务端确认')
  await expect(page.locator('.booking-row')).toContainText('待签到')
  await expect(page.getByRole('button', { name: '去签到' })).toBeDisabled()
  await page.getByRole('tab', { name: '站内提醒', exact: true }).click()
  await expect(page.getByTestId('reservation-notification')).toHaveCount(3)
  await expect(page.getByText('不代表短信、邮件或微信已送达', { exact: false })).toBeVisible()
  await page.setViewportSize({ width: 380, height: 844 })
  await fits(page)
})
test('@fixture M1 管理员筛选统计、服务错误及桌面窄屏布局', async ({ page }) => {
  const state = await fixture(page, { ...student, roles: ['ADMIN'] })
  await page.goto('/admin/statistics')
  await expect(page.getByTestId('statistics-summary')).toContainText('20.0%')
  await page.getByLabel('统计开始日期', { exact: true }).fill('2030-11-01')
  await page.getByLabel('统计结束日期', { exact: true }).fill('2030-11-03')
  await page.locator('.m1-statistics-filters .el-select').click()
  await page.getByRole('option', { name: '知行楼101', exact: true }).click()
  await page.getByRole('button', { name: '查询统计' }).click()
  await expect(page.locator('.m1-statistics-range')).toContainText('2030-11-01 至 2030-11-03')
  expect(state.queries.at(-1)).toContain('classroomId=1')
  await fits(page)
  await page.setViewportSize({ width: 380, height: 844 })
  await fits(page)
  state.failStatistics()
  await page.getByRole('button', { name: '查询统计' }).click()
  await expect(page.getByRole('alert')).toContainText('fixture-stats-503')
  await expect(page.getByTestId('statistics-summary')).toHaveCount(0)
})
