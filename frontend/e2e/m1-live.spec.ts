import { expect, test, type APIRequestContext, type APIResponse } from '@playwright/test'
import { campusDate } from '../src/lib/time'
import type { Classroom, Reservation, Seat } from '../src/lib/types'
import { expectFitsViewport } from './support/layout'
// Real browser, Cookie/CSRF and HTTP. No route interception; test accounts contain no real identities.
test.use({ trace: 'off' })
const fixturePassword = 'M1-acceptance-2026!'
async function headers(client: APIRequestContext) {
  const response = await client.get('/api/v1/auth/csrf')
  expect(response.status()).toBe(200)
  const csrf = await response.json()
  return { [csrf.headerName]: csrf.token, 'Idempotency-Key': crypto.randomUUID() }
}
async function post(client: APIRequestContext, path: string, data: unknown = {}) {
  return client.post(`/api/v1${path}`, { headers: await headers(client), data })
}
async function json<T>(response: APIResponse): Promise<T> {
  expect(response.ok(), await response.text()).toBe(true)
  return response.json()
}
async function login(client: APIRequestContext, username: string, password = fixturePassword) {
  expect((await post(client, '/auth/login', { username, password })).status()).toBe(200)
}
async function denied(response: APIResponse, status: number) {
  expect(response.status()).toBe(status)
  expect((await response.json()).requestId).toEqual(expect.any(String))
}
test('M1-CYF 真实浏览器预约、签到、取消、历史复用及服务端越权拒绝', async ({
  page,
  playwright,
  baseURL,
}) => {
  test.setTimeout(100000)
  const fixed = process.env.E2E_CLOCK ?? '2026-10-12T01:50:00Z'
  await page.clock.install({ time: new Date(fixed) })
  const admin = await playwright.request.newContext({ baseURL }),
    other = await playwright.request.newContext({ baseURL })
  const accounts: {
    id: number
    username: string
    displayName: string
    department: string
    roles: string[]
  }[] = []
  try {
    await login(
      admin,
      process.env.CAMPUS_ADMIN_USERNAME ?? 'admin',
      process.env.CAMPUS_ADMIN_PASSWORD ?? 'Campus123!',
    )
    const room = await json<Classroom>(
      await post(admin, '/admin/classrooms', {
        name: `M1-CYF-${crypto.randomUUID().slice(0, 8)}`,
        building: '验收楼',
        floor: 1,
        department: null,
        openTime: '08:00',
        closeTime: '22:00',
        enabled: true,
      }),
    )
    await json<Seat>(
      await post(admin, `/admin/classrooms/${room.id}/seats`, {
        label: 'CYF01',
        hasPower: true,
        enabled: true,
      }),
    )
    expect(
      (
        await admin.put(`/api/v1/admin/check-in-codes/${room.id}`, {
          headers: await headers(admin),
          data: { code: '246810' },
        })
      ).ok(),
    ).toBe(true)
    for (const client of [page.request, other]) {
      const account = await json<(typeof accounts)[number]>(
        await post(admin, '/admin/accounts', {
          username: `cyf_${crypto.randomUUID().replaceAll('-', '').slice(0, 16)}`,
          password: fixturePassword,
          displayName: 'M1验收同学',
          department: 'CS',
          roles: ['STUDENT'],
          enabled: true,
        }),
      )
      accounts.push(account)
      await login(client, account.username)
    }
    await page.goto('/seats')
    await page.locator('.search-form .el-select').first().click()
    await page.getByRole('option', { name: `${room.name} · 验收楼`, exact: true }).click()
    await page.getByRole('button', { name: '查询座位' }).click()
    await page.getByRole('button', { name: '座位 CYF01 可预约', exact: true }).click()
    await page.getByRole('button', { name: '确认预约', exact: true }).click()
    await expect(page.getByText('预约成功，请在签到窗口内输入教室签到码')).toBeVisible()
    await page.goto('/bookings')
    const booking = page.locator('.booking-row').filter({ hasText: room.name })
    await expect(booking).toContainText('即将开始')
    const records = await json<Reservation[]>(
      await page.request.get('/api/v1/reservations/mine?statusGroup=current'),
    )
    const source = records.find((r) => r.classroomId === room.id)!
    expect(source).toBeTruthy()
    await expect
      .poll(
        async () =>
          (
            await json<{ reservationId: number }[]>(
              await page.request.get('/api/v1/reservations/notifications'),
            )
          ).some((n) => n.reservationId === source.id),
        { timeout: 35000 },
      )
      .toBe(true)
    await page.getByRole('tab', { name: '站内提醒', exact: true }).click()
    await expect(page.getByTestId('reservation-notification')).toContainText('预约即将开始')
    await page.getByRole('tab', { name: '当前预约', exact: true }).click()
    await booking.getByRole('button', { name: '去签到' }).click()
    await page.getByRole('textbox', { name: '签到码', exact: true }).fill('246810')
    await page.getByRole('button', { name: '确认签到', exact: true }).click()
    await expect(booking).toContainText('已签到')
    await booking.getByRole('button', { name: '取消预约', exact: true }).click()
    await page.getByRole('button', { name: '确认取消', exact: true }).click()
    await expect(booking).toHaveCount(0)
    await page.getByRole('tab', { name: '历史记录', exact: true }).click()
    await expect(booking).toContainText('已取消')
    await booking.getByRole('button', { name: '一键复用' }).click()
    await page.getByRole('button', { name: '确认复用', exact: true }).click()
    await expect(booking).toContainText('待签到')
    const current = await json<Reservation[]>(
      await page.request.get('/api/v1/reservations/mine?statusGroup=current'),
    )
    const reused = current.find((r) => r.classroomId === room.id)!
    expect(reused.id).not.toBe(source.id)
    expect(reused.seatId).toBe(source.seatId)
    const input = { startTime: reused.startTime, endTime: reused.endTime }
    await denied(await post(other, `/reservations/${source.id}/reuse`, input), 403)
    await denied(await post(other, `/reservations/${reused.id}/check-in`, { code: '246810' }), 403)
    await denied(await post(other, `/reservations/${reused.id}/cancel`), 403)
    expect(await json<Reservation[]>(await other.get('/api/v1/reservations/mine'))).toEqual([])
    await denied(await post(page.request, `/reservations/${source.id}/reuse`, input), 409)
    await denied(
      await post(page.request, `/reservations/${source.id}/reuse`, {
        startTime: '2000-01-01T10:00:00+08:00',
        endTime: '2000-01-01T11:00:00+08:00',
      }),
      400,
    )
    const date = campusDate(new Date(fixed))
    await denied(
      await other.get(`/api/v1/admin/reservation-statistics?startDate=${date}&endDate=${date}`),
      403,
    )
    await json(await post(page.request, `/reservations/${reused.id}/cancel`))
    await login(
      page.request,
      process.env.CAMPUS_ADMIN_USERNAME ?? 'admin',
      process.env.CAMPUS_ADMIN_PASSWORD ?? 'Campus123!',
    )
    await page.goto('/admin/statistics')
    await page.getByLabel('统计开始日期', { exact: true }).fill(date)
    await page
      .getByLabel('统计结束日期', { exact: true })
      .fill(campusDate(new Date(Date.parse(fixed) + 86400000)))
    await page.locator('.m1-statistics-filters .el-select').click()
    await page.getByRole('option', { name: room.name, exact: true }).click()
    await expect(page.getByRole('listbox', { name: '统计教室', exact: true })).toBeHidden()
    await page.getByRole('button', { name: '查询统计' }).click()
    await expect(page.getByTestId('statistics-room')).toHaveCount(1)
    await expect(page.getByTestId('statistics-summary')).toContainText('预约数量2')
    if (process.env.M1_CAPTURE === '1')
      await page.screenshot({
        path: '../docs/images/m1-cyf-statistics-desktop.png',
        fullPage: true,
      })
    await page.setViewportSize({ width: 380, height: 844 })
    await expectFitsViewport(page)
  } finally {
    for (const account of accounts) {
      expect(
        (
          await admin.put(`/api/v1/admin/accounts/${account.id}`, {
            headers: await headers(admin),
            data: {
              displayName: account.displayName,
              department: account.department,
              roles: account.roles,
              enabled: false,
            },
          })
        ).ok(),
      ).toBe(true)
      if (process.env.M1_CAPTURE === '1')
        await page.screenshot({
          path: '../docs/images/m1-cyf-statistics-mobile.png',
          fullPage: true,
        })
    }
    await admin.dispose()
    await other.dispose()
  }
})
