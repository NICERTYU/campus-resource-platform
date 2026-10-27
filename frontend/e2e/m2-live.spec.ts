import { expect, test, type APIRequestContext, type APIResponse } from '@playwright/test'
import { spawnSync } from 'node:child_process'
import { existsSync } from 'node:fs'
import { campusDate } from '../src/lib/time'
import type { Account, Application, ApprovalHistory, Classroom, Seat } from '../src/lib/types'

// Real sessions and HTTP only; no route interception. Do not retain passwords in traces.
test.use({ trace: 'off' })
const adminUsername = process.env.CAMPUS_ADMIN_USERNAME ?? 'admin'
const adminPassword = process.env.CAMPUS_ADMIN_PASSWORD ?? 'Campus123!'
const fixturePassword = 'M2-acceptance-2026!'
async function headers(client: APIRequestContext) {
  const response = await client.get('/api/v1/auth/csrf')
  expect(response.status()).toBe(200)
  const csrf = await response.json()
  return { [csrf.headerName]: csrf.token, 'Idempotency-Key': crypto.randomUUID() }
}
async function post(client: APIRequestContext, path: string, data: unknown = {}) {
  return client.post(`/api/v1${path}`, { headers: await headers(client), data })
}
async function login(client: APIRequestContext, username: string, password = fixturePassword) {
  const response = await post(client, '/auth/login', { username, password })
  expect(response.status()).toBe(200)
}
async function json<T>(response: APIResponse): Promise<T> {
  expect(response.status()).toBeGreaterThanOrEqual(200)
  expect(response.status()).toBeLessThan(300)
  return response.json()
}
async function denied(response: APIResponse, status: number) {
  expect(response.status()).toBe(status)
  const body = await response.json()
  expect(body.code).toBe(
    status === 401 ? 'UNAUTHENTICATED' : status === 403 ? 'FORBIDDEN' : 'STATE_CONFLICT',
  )
  expect(body.requestId).toEqual(expect.any(String))
  expect(body.requestId.length).toBeGreaterThan(0)
  expect(body.message).toEqual(expect.any(String))
}
function times() {
  const date = campusDate(new Date(process.env.E2E_CLOCK ?? Date.now() + 2 * 86400000))
  return { startTime: `${date}T10:00:00+08:00`, endTime: `${date}T11:00:00+08:00` }
}
async function createRoom(admin: APIRequestContext) {
  const room = await json<Classroom>(
    await post(admin, '/admin/classrooms', {
      name: `M2-YZ-${crypto.randomUUID().slice(0, 8)}`,
      building: '验收楼',
      floor: 1,
      department: null,
      openTime: '08:00',
      closeTime: '22:00',
      enabled: true,
    }),
  )
  await json(
    await post(admin, `/admin/classrooms/${room.id}/seats`, {
      label: 'YZ01',
      hasPower: true,
      enabled: true,
    }),
  )
  return room
}
async function createApplication(client: APIRequestContext, room: Classroom, purpose: string) {
  return json<Application>(
    await post(client, '/classroom-applications', { classroomId: room.id, ...times(), purpose }),
  )
}
async function list(client: APIRequestContext) {
  return json<Application[]>(await client.get('/api/v1/classroom-applications?page=1&pageSize=50'))
}
async function available(client: APIRequestContext, room: Classroom) {
  const query = new URLSearchParams({ classroomId: String(room.id), ...times() })
  const seats = await json<Seat[]>(await client.get(`/api/v1/seats/availability?${query}`))
  expect(seats).toHaveLength(1)
  expect(seats[0]!.available).toBe(true)
}
async function createAccount(admin: APIRequestContext, role: string, department = 'CS') {
  return json<Account>(
    await post(admin, '/admin/accounts', {
      username: `yz_${crypto.randomUUID().replaceAll('-', '').slice(0, 16)}`,
      password: fixturePassword,
      displayName: `M2验收${role}`,
      department,
      roles: [role],
      enabled: true,
    }),
  )
}
async function disableAccounts(admin: APIRequestContext, accounts: Account[]) {
  for (const account of accounts) {
    const response = await admin.put(`/api/v1/admin/accounts/${account.id}`, {
      headers: await headers(admin),
      data: {
        displayName: account.displayName,
        department: account.department,
        roles: account.roles,
        enabled: false,
      },
    })
    expect(response.status()).toBe(200)
  }
}

test('M2-YZ 真实HTTP权限矩阵：401、写操作与历史403、院系节点、旧版本409', async ({
  playwright,
  baseURL,
}) => {
  const clients = await Promise.all(
    Array.from({ length: 5 }, () => playwright.request.newContext({ baseURL })),
  )
  const [admin, owner, other, student, outsider] = clients as [
    APIRequestContext,
    APIRequestContext,
    APIRequestContext,
    APIRequestContext,
    APIRequestContext,
  ]
  const accounts: Account[] = []
  try {
    await denied(await owner.get('/api/v1/classroom-applications'), 401)
    await login(admin, adminUsername, adminPassword)
    const room = await createRoom(admin)
    for (const [client, role, department] of [
      [owner, 'APPLICANT', 'CS'],
      [other, 'APPLICANT', 'CS'],
      [student, 'STUDENT', 'CS'],
      [outsider, 'ADMIN', 'ART'],
    ] as const) {
      const account = await createAccount(admin, role, department)
      accounts.push(account)
      await login(client, account.username)
    }
    const mine = await createApplication(owner, room, 'M2-YZ 本人范围'),
      theirs = await createApplication(other, room, 'M2-YZ 他人范围')
    expect((await list(owner)).map((record) => record.id)).toEqual([mine.id])
    expect((await list(other)).map((record) => record.id)).toEqual([theirs.id])
    // Current contract permits students to list their own (empty) records, but not submit or review.
    expect(await list(student)).toEqual([])
    await denied(
      await post(student, '/classroom-applications', {
        classroomId: room.id,
        ...times(),
        purpose: '越权提交',
      }),
      403,
    )
    await denied(
      await post(student, `/classroom-applications/${mine.id}/approve`, {
        revision: mine.revision,
      }),
      403,
    )
    await denied(
      await post(owner, `/classroom-applications/${mine.id}/approve`, { revision: mine.revision }),
      403,
    )
    await denied(await other.get(`/api/v1/classroom-applications/${mine.id}/history`), 403)
    await denied(await post(other, `/classroom-applications/${mine.id}/withdraw`), 403)
    await denied(
      await post(outsider, `/classroom-applications/${mine.id}/approve`, {
        revision: mine.revision,
      }),
      403,
    )
    const all = await list(admin)
    expect(all.map((record) => record.id)).toEqual(expect.arrayContaining([mine.id, theirs.id]))
    const next = await json<Application>(
      await post(admin, `/classroom-applications/${mine.id}/approve`, {
        revision: mine.revision,
        comment: '同院系初审',
      }),
    )
    expect(next).toMatchObject({ status: 'PENDING', currentStep: 2, revision: mine.revision + 1 })
    await available(student, room)
    await denied(
      await post(admin, `/classroom-applications/${mine.id}/approve`, { revision: mine.revision }),
      409,
    )
    expect((await list(owner))[0]).toMatchObject({ currentStep: 2, revision: next.revision })
    // Final review has no first-stage department restriction. Reject to leave the fixture unoccupied.
    const rejected = await json<Application>(
      await post(outsider, `/classroom-applications/${mine.id}/reject`, {
        revision: next.revision,
        comment: '终审驳回验收',
      }),
    )
    expect(rejected.status).toBe('REJECTED')
    const history = await json<ApprovalHistory[]>(
      await owner.get(`/api/v1/classroom-applications/${mine.id}/history`),
    )
    expect(history.map((entry) => entry.action)).toEqual(['CREATE', 'APPROVE', 'REJECT'])
    expect(history.every((entry) => !!entry.requestId)).toBe(true)
    await json(await post(other, `/classroom-applications/${theirs.id}/withdraw`))
    await available(student, room)
  } finally {
    try {
      if (accounts.length) await disableAccounts(admin, accounts)
    } finally {
      await Promise.all(clients.map((client) => client.dispose()))
    }
  }
})

test('M2-YZ 真实浏览器驳回、服务端超时扫描、历史请求编号与空闲座位', async ({
  page,
  playwright,
  baseURL,
}) => {
  test.skip(
    !process.env.CI && process.env.CAMPUS_DB_TEST !== '1',
    'Requires isolated Compose DB: CAMPUS_DB_TEST=1',
  )
  if (
    !baseURL ||
    !['localhost', '127.0.0.1', '[::1]'].includes(new URL(baseURL).hostname) ||
    !existsSync(new URL('../../.local/dev.env', import.meta.url))
  )
    throw new Error('Expiry fixture is restricted to the local Compose stack')
  const owner = await playwright.request.newContext({ baseURL }),
    accounts: Account[] = []
  try {
    await login(page.request, adminUsername, adminPassword)
    const room = await createRoom(page.request),
      account = await createAccount(page.request, 'APPLICANT')
    accounts.push(account)
    await login(owner, account.username)
    const rejected = await createApplication(owner, room, 'M2-YZ 浏览器驳回')
    await page.goto('/applications')
    const card = page.locator('.workflow-card').filter({ hasText: `申请 #${rejected.id}` })
    await card.getByRole('button', { name: '驳回', exact: true }).click()
    await page.getByRole('dialog').locator('textarea').fill('活动信息不完整，请重新申请')
    await page.getByRole('dialog').getByRole('button', { name: '确认驳回', exact: true }).click()
    await expect(card).toContainText('已驳回')
    await expect(card.getByRole('button', { name: '通过申请' })).toHaveCount(0)
    await available(owner, room)
    const marker = `M2-YZ-expire-${crypto.randomUUID()}`
    const pending = await createApplication(owner, room, marker),
      returned = await createApplication(owner, room, marker)
    await json(
      await post(page.request, `/classroom-applications/${returned.id}/return`, {
        revision: returned.revision,
        comment: '补充材料期限不延长',
      }),
    )
    await page.reload()
    const pendingCard = page.locator('.workflow-card').filter({ hasText: `申请 #${pending.id}` })
    const returnedCard = page.locator('.workflow-card').filter({ hasText: `申请 #${returned.id}` })
    await expect(pendingCard).toContainText('待审批')
    await expect(returnedCard).toContainText('待补充重提')
    // Move only these two uniquely marked test records past the deadline. The real scanner performs EXPIRE.
    expect([pending.id, returned.id].every((id) => Number.isSafeInteger(id) && id > 0)).toBe(true)
    const sql = `UPDATE workflow.classroom_applications SET approval_deadline='2000-01-01T00:00:00Z' WHERE id IN (${pending.id},${returned.id}) AND purpose='${marker}' RETURNING id`
    const probe = spawnSync(
      'docker',
      [
        'exec',
        process.env.CAMPUS_DB_CONTAINER ?? 'campus-dev-db-1',
        'bash',
        '-c',
        'export PGPASSWORD="$WORKFLOW_DB_PASSWORD"; exec psql -h 127.0.0.1 -v ON_ERROR_STOP=1 -U campus_workflow -d campus -tAc "$1"',
        'm2-yz-expiry-fixture',
        sql,
      ],
      { encoding: 'utf8', timeout: 10000 },
    )
    expect(probe.status, probe.error?.message ?? probe.stderr).toBe(0)
    expect(probe.stdout).toContain(String(pending.id))
    expect(probe.stdout).toContain(String(returned.id))
    await expect(pendingCard).toContainText('超时已撤销', { timeout: 25000 })
    await expect(returnedCard).toContainText('超时已撤销', { timeout: 25000 })
    await expect(pendingCard).toContainText('请求编号：m2-expire-')
    await expect(pendingCard.getByRole('button', { name: '通过申请' })).toHaveCount(0)
    await pendingCard.getByRole('button', { name: '审批记录' }).click()
    await expect(page.getByRole('dialog')).toContainText('超时撤销')
    await expect(page.getByRole('dialog')).toContainText('请求编号：m2-expire-')
    const current = (await list(owner)).find((record) => record.id === pending.id)!
    await denied(
      await post(page.request, `/classroom-applications/${pending.id}/approve`, {
        revision: current.revision,
      }),
      409,
    )
    await available(owner, room)
  } finally {
    try {
      if (accounts.length) await disableAccounts(page.request, accounts)
    } finally {
      await owner.dispose()
    }
  }
})
