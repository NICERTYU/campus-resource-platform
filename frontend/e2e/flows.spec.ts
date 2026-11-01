import { test, expect, type Page } from '@playwright/test'
const fixedNow = process.env.E2E_CLOCK ?? '2026-10-12T01:50:00Z'
async function login(page: Page, username: string) {
  if (await page.getByRole('button', { name: '退出登录' }).isVisible()) {
    await page.getByRole('button', { name: '退出登录' }).click()
    await expect(page).toHaveURL(/\/login$/)
  }
  await page.goto('/login')
  await page.getByPlaceholder('请输入账号').fill(username)
  await page.getByPlaceholder('请输入密码').fill('Campus123!')
  await page.getByRole('button', { name: '登录平台' }).click()
  await expect(page).toHaveURL(/\/seats$/)
}
async function selectRoom(page: Page, name: string, dialog = false) {
  const root = dialog ? page.getByRole('dialog') : page.locator('.search-form')
  await root.locator('.el-select').first().click()
  await page.locator('.el-select-dropdown:visible').getByText(name, { exact: !dialog }).click()
}
async function createRoom(page: Page) {
  await login(page, 'admin')
  const csrf = await (await page.request.get('/api/v1/auth/csrf')).json()
  const name = `浏览器验收-${Date.now()}-${Math.random().toString(16).slice(2, 7)}`
  const result = await page.request.post('/api/v1/admin/classrooms', {
    headers: { [csrf.headerName]: csrf.token, 'Idempotency-Key': crypto.randomUUID() },
    data: {
      name,
      building: '验收楼',
      floor: 1,
      department: null,
      openTime: '08:00',
      closeTime: '22:00',
      enabled: true,
    },
  })
  expect(result.status()).toBeLessThan(300)
  const room = await result.json()
  for (const label of ['A01', 'A02']) {
    const seat = await page.request.post(`/api/v1/admin/classrooms/${room.id}/seats`, {
      headers: { [csrf.headerName]: csrf.token, 'Idempotency-Key': crypto.randomUUID() },
      data: { label, hasPower: true, enabled: true },
    })
    expect(seat.status()).toBeLessThan(300)
  }
  const code = await page.request.put(`/api/v1/admin/check-in-codes/${room.id}`, {
    headers: { [csrf.headerName]: csrf.token, 'Idempotency-Key': crypto.randomUUID() },
    data: { code: '246810' },
  })
  expect(code.status()).toBeLessThan(300)
  return name
}
async function availability(page: Page, room: string, available: boolean) {
  await page.goto('/seats')
  await selectRoom(page, `${room} · 验收楼`)
  await page.getByRole('button', { name: '查询座位' }).click()
  await expect(page.locator('button.seat')).toHaveCount(2)
  for (const seat of await page.locator('button.seat').all()) {
    if (available) await expect(seat).toBeEnabled()
    else await expect(seat).toBeDisabled()
  }
}
function application(page: Page, room: string) {
  return page
    .locator('.workflow-card')
    .filter({ has: page.getByRole('heading', { name: room, exact: true }) })
}
function repair(page: Page, description: string) {
  return page.locator('.workflow-card').filter({ hasText: description })
}
async function repairAction(
  page: Page,
  description: string,
  button: string,
  confirm: string,
  note?: string,
) {
  await repair(page, description).getByRole('button', { name: button, exact: true }).click()
  if (note) await page.getByRole('dialog').locator('textarea').fill(note)
  await page.getByRole('dialog').getByRole('button', { name: confirm, exact: true }).click()
}
test.beforeEach(async ({ page }) => {
  await page.clock.install({ time: new Date(fixedNow) })
})
test('学生搜索座位、预约、到场签到并取消', async ({ page }) => {
  const room = await createRoom(page)
  await login(page, 'student')
  await availability(page, room, true)
  await page.getByRole('button', { name: '座位 A01 可预约', exact: true }).click()
  await page.getByRole('dialog').getByRole('button', { name: '确认预约', exact: true }).click()
  await expect(page.getByText('预约成功，请在签到窗口内输入教室签到码')).toBeVisible()
  await page.goto('/bookings')
  const booking = page.locator('.booking-row').filter({ hasText: room })
  await expect(booking).toContainText('待签到')
  await booking.getByRole('button', { name: '去签到' }).click()
  await page.getByRole('dialog').getByRole('textbox', { name: '签到码' }).fill('246810')
  await page.getByRole('dialog').getByRole('button', { name: '确认签到' }).click()
  await expect(booking).toContainText('已签到')
  await booking.getByRole('button', { name: '取消预约' }).click()
  await page.getByRole('button', { name: '确认取消', exact: true }).click()
  await expect(booking).toHaveCount(0)
  await page.getByRole('tab', { name: /历史记录/ }).click()
  await expect(page.locator('.booking-row').filter({ hasText: room })).toContainText('已取消')
})
test('申请人提交、管理员批准、整室占用与撤销恢复', async ({ page }) => {
  const room = await createRoom(page)
  await login(page, 'applicant')
  await page.goto('/applications')
  await page.getByRole('button', { name: '新建申请' }).click()
  await selectRoom(page, room, true)
  await page
    .getByRole('dialog')
    .getByPlaceholder('说明活动、参与人数和使用需求')
    .fill('课程项目组讨论，需要整间教室')
  await page.getByRole('dialog').getByRole('button', { name: '提交申请' }).click()
  await expect(application(page, room)).toContainText('待审批')
  await login(page, 'admin')
  await page.goto('/applications')
  await application(page, room).getByRole('button', { name: '通过申请' }).click()
  await page.getByRole('dialog').getByRole('button', { name: '确认通过', exact: true }).click()
  await expect(application(page, room)).toContainText('审批节点：2 / 2')
  await expect(application(page, room)).toContainText('待审批')
  await application(page, room).getByRole('button', { name: '通过申请' }).click()
  await page.getByRole('dialog').getByRole('button', { name: '确认通过', exact: true }).click()
  await expect(application(page, room)).toContainText('已通过')
  await login(page, 'student')
  await availability(page, room, false)
  await login(page, 'applicant')
  await page.goto('/applications')
  await application(page, room).getByRole('button', { name: '撤销申请' }).click()
  await page.getByRole('dialog').getByRole('button', { name: '确认撤销', exact: true }).click()
  await expect(application(page, room)).toContainText('已撤销')
  await login(page, 'student')
  await availability(page, room, true)
})
test('M2退回补充、重提、终审退回初审及完整审批历史', async ({ page }) => {
  const room = await createRoom(page)
  await login(page, 'applicant')
  await page.goto('/applications')
  await page.getByRole('button', { name: '新建申请' }).click()
  await selectRoom(page, room, true)
  await page
    .getByRole('dialog')
    .getByPlaceholder('说明活动、参与人数和使用需求')
    .fill('待补充的活动申请')
  await page.getByRole('dialog').getByRole('button', { name: '提交申请' }).click()
  await expect(application(page, room)).toContainText('待审批')
  await login(page, 'admin')
  await page.goto('/applications')
  await application(page, room).getByRole('button', { name: '退回', exact: true }).click()
  await page.getByRole('dialog').locator('textarea').fill('请补充活动用途')
  await page.getByRole('dialog').getByRole('button', { name: '确认退回', exact: true }).click()
  await expect(application(page, room)).toContainText('待补充重提')
  await login(page, 'applicant')
  await page.goto('/applications')
  await application(page, room).getByRole('button', { name: '补充重提' }).click()
  await page.getByRole('dialog').locator('textarea').fill('课程讨论，参加人数20人')
  await page.getByRole('dialog').getByRole('button', { name: '确认重新提交', exact: true }).click()
  await expect(application(page, room)).toContainText('待审批')
  await login(page, 'admin')
  await page.goto('/applications')
  await application(page, room).getByRole('button', { name: '通过申请' }).click()
  await page.getByRole('dialog').getByRole('button', { name: '确认通过', exact: true }).click()
  await expect(application(page, room)).toContainText('审批节点：2 / 2')
  await application(page, room).getByRole('button', { name: '退回', exact: true }).click()
  await page.getByRole('dialog').locator('textarea').fill('请初审再次核实')
  await page.getByRole('dialog').getByRole('button', { name: '确认退回', exact: true }).click()
  await expect(application(page, room)).toContainText('审批节点：1 / 2')
  for (let step = 1; step <= 2; step++) {
    await application(page, room).getByRole('button', { name: '通过申请' }).click()
    await page.getByRole('dialog').getByRole('button', { name: '确认通过', exact: true }).click()
    await expect(application(page, room)).toContainText(step === 1 ? '审批节点：2 / 2' : '已通过')
  }
  await application(page, room).getByRole('button', { name: '审批记录' }).click()
  await expect(page.getByRole('dialog')).toContainText('请补充活动用途')
  await expect(page.getByRole('dialog')).toContainText('课程讨论，参加人数20人')
  await expect(page.getByRole('dialog')).toContainText('请初审再次核实')
})

test('学生报修、管理员派单、维修完工和返修验收恢复', async ({ page }) => {
  const room = await createRoom(page)
  const description = `${room} 插座无法供电，请检查`
  await login(page, 'student')
  await page.goto('/repairs')
  await page.getByRole('button', { name: '上报故障' }).click()
  await selectRoom(page, `${room} · 验收楼`, true)
  await page
    .getByRole('dialog')
    .getByPlaceholder('说明具体位置、故障现象和影响，便于维修人员处理')
    .fill(description)
  await page.getByRole('dialog').getByRole('button', { name: '提交报修' }).click()
  await expect(repair(page, description)).toContainText('待派单')
  await login(page, 'admin')
  await page.goto('/repairs')
  await repairAction(page, description, '派单', '确认分派维修人员')
  await expect(repair(page, description)).toContainText('已派单')
  await login(page, 'technician')
  await page.goto('/repairs')
  await repairAction(page, description, '开始维修', '确认开始维修')
  await expect(repair(page, description)).toContainText('维修中')
  await login(page, 'student')
  await availability(page, room, false)
  await login(page, 'technician')
  await page.goto('/repairs')
  await repairAction(page, description, '提交完工', '确认提交完工', '已更换插座，通电检查正常')
  await expect(repair(page, description)).toContainText('待验收')
  await login(page, 'student')
  await availability(page, room, false)
  await login(page, 'admin')
  await page.goto('/repairs')
  await repairAction(page, description, '退回维修', '确认退回维修', '请再检查相邻插座')
  await expect(repair(page, description)).toContainText('维修中')
  await login(page, 'technician')
  await page.goto('/repairs')
  await repairAction(page, description, '提交完工', '确认提交完工', '相邻插座已检查完成')
  await expect(repair(page, description)).toContainText('待验收')
  await login(page, 'admin')
  await page.goto('/repairs')
  await repairAction(page, description, '验收通过', '确认验收通过', '全部检查通过')
  await expect(repair(page, description)).toContainText('已验收')
  await login(page, 'student')
  await availability(page, room, true)
})
