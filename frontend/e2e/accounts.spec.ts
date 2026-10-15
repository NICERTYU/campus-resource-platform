import {
  expect,
  test,
  type APIRequestContext,
  type BrowserContext,
  type Page,
  type Response,
} from '@playwright/test'

// Cloud acceptance may supply private administrator credentials; never retain request bodies.
test.use({ trace: 'off', viewport: { width: 1280, height: 720 } })

const adminUsername = process.env.CAMPUS_ADMIN_USERNAME ?? 'admin'
const adminPassword = process.env.CAMPUS_ADMIN_PASSWORD ?? 'Campus123!'
const initialPassword = 'Account-E2E-Initial-2026!'
const replacementPassword = 'Account-E2E-Replaced-2026!'

function uniqueUsername(prefix: string) {
  return `${prefix}_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 8)}`
}

function apiResponse(path: string, method: string) {
  return (response: Response) =>
    new URL(response.url()).pathname === `/api/v1${path}` && response.request().method() === method
}

async function csrfHeaders(request: APIRequestContext) {
  const response = await request.get('/api/v1/auth/csrf')
  expect(response.status()).toBe(200)
  const csrf = await response.json()
  return { [csrf.headerName]: csrf.token, 'Idempotency-Key': crypto.randomUUID() }
}

async function login(page: Page, username: string, password: string) {
  await page.goto('/login')
  await page.getByPlaceholder('请输入账号').fill(username)
  await page.getByPlaceholder('请输入密码').fill(password)
  const [response] = await Promise.all([
    page.waitForResponse(apiResponse('/auth/login', 'POST')),
    page.getByRole('button', { name: '登录平台' }).click(),
  ])
  expect(response.status()).toBe(200)
  await expect(page).toHaveURL(/\/seats$/)
}

async function rejectLogin(page: Page, username: string, password: string) {
  await page.goto('/login')
  await page.getByPlaceholder('请输入账号').fill(username)
  await page.getByPlaceholder('请输入密码').fill(password)
  const [response] = await Promise.all([
    page.waitForResponse(apiResponse('/auth/login', 'POST')),
    page.getByRole('button', { name: '登录平台' }).click(),
  ])
  expect(response.status()).toBe(401)
  await expect(page.locator('.form-error')).toContainText('用户名或密码错误')
  await expect(page.locator('.form-error')).not.toContainText('连接服务失败')
}

async function searchAccount(page: Page, username: string) {
  await page.getByRole('textbox', { name: '搜索账号或姓名' }).fill(username)
  const [response] = await Promise.all([
    page.waitForResponse((result) => apiResponse('/admin/accounts', 'GET')(result)),
    page.getByRole('button', { name: '搜索账号', exact: true }).click(),
  ])
  expect(response.status()).toBe(200)
}

function accountRow(page: Page, id: number) {
  return page.getByTestId(`account-row-${id}`).locator('xpath=ancestor::tr')
}

async function saveAccount(page: Page, id: number) {
  const [response] = await Promise.all([
    page.waitForResponse(apiResponse(`/admin/accounts/${id}`, 'PUT')),
    page.getByRole('dialog').getByRole('button', { name: '保存并确认' }).click(),
  ])
  expect(response.status()).toBe(200)
  await expect(page.getByRole('dialog')).toHaveCount(0)
}

async function deactivateFixture(context: BrowserContext, id: number, username: string) {
  // Keep the fixture and its audit trail, while preventing later use of its known test password.
  const headers = await csrfHeaders(context.request)
  const loginResponse = await context.request.post('/api/v1/auth/login', {
    headers,
    data: { username: adminUsername, password: adminPassword },
  })
  expect(loginResponse.status()).toBe(200)
  const listResponse = await context.request.get(
    `/api/v1/admin/accounts?query=${encodeURIComponent(username)}`,
  )
  expect(listResponse.status()).toBe(200)
  const account = (await listResponse.json()).find(
    (candidate: { id: number }) => candidate.id === id,
  )
  expect(account).toBeDefined()
  const response = await context.request.put(`/api/v1/admin/accounts/${id}`, {
    headers: await csrfHeaders(context.request),
    data: {
      displayName: account.displayName,
      department: account.department,
      roles: account.roles,
      enabled: false,
    },
  })
  expect(response.status()).toBe(200)
}

test('学生从登录页注册后独立登录，普通账号不能访问账号管理', async ({ page, browser, baseURL }) => {
  const username = uniqueUsername('e2ereg')
  let fixtureId: number | undefined
  const cleanup = await browser.newContext({ baseURL })
  try {
    await page.goto('/login')
    await page.getByRole('link', { name: '注册学生账号' }).click()
    await expect(page).toHaveURL(/\/register$/)
    await page.getByRole('textbox', { name: '校园账号', exact: true }).fill(username)
    await page.getByRole('textbox', { name: '姓名', exact: true }).fill('浏览器注册学生')
    await page.getByRole('combobox', { name: '所属院系' }).selectOption('CS')
    await page.getByLabel('登录密码', { exact: true }).fill(initialPassword)
    await page.getByLabel('确认密码', { exact: true }).fill(initialPassword)
    const [response] = await Promise.all([
      page.waitForResponse(apiResponse('/auth/register', 'POST')),
      page.getByRole('button', { name: '注册学生账号', exact: true }).click(),
    ])
    expect(response.status()).toBe(201)
    const account = await response.json()
    fixtureId = account.id
    expect(account.roles).toEqual(['STUDENT'])
    expect(account.department).toBe('CS')
    await expect(page).toHaveURL(/\/login\?registered=1&username=/)
    await expect(page.getByText('注册成功，请使用新账号登录')).toBeVisible()
    await expect(page.getByPlaceholder('请输入账号')).toHaveValue(username)
    expect((await page.request.get('/api/v1/auth/me')).status()).toBe(401)

    await page.getByPlaceholder('请输入密码').fill(initialPassword)
    await page.getByRole('button', { name: '登录平台' }).click()
    await expect(page).toHaveURL(/\/seats$/)
    await expect(page.getByRole('link', { name: /账号管理/ })).toHaveCount(0)
    expect((await page.request.get('/api/v1/admin/accounts')).status()).toBe(403)
    await page.goto('/admin/accounts')
    await expect(page).toHaveURL(/\/seats$/)
    await expect(page.getByRole('link', { name: /账号管理/ })).toHaveCount(0)
    await page.getByRole('button', { name: '退出登录' }).click()
    await expect(page).toHaveURL(/\/login$/)
  } finally {
    try {
      if (fixtureId !== undefined) await deactivateFixture(cleanup, fixtureId, username)
    } finally {
      await cleanup.close()
    }
  }
})

test('管理员维护新账号、失效旧会话、重置密码并停启用，保留自己的管理员权限', async ({
  page,
  browser,
  baseURL,
}) => {
  const username = uniqueUsername('e2emgr')
  let fixtureId: number | undefined
  const studentContext = await browser.newContext({ baseURL })
  const student = await studentContext.newPage()
  try {
    await login(page, adminUsername, adminPassword)
    const admin = await (await page.request.get('/api/v1/auth/me')).json()
    await page.getByRole('link', { name: /账号管理/ }).click()
    await expect(page).toHaveURL(/\/admin\/accounts$/)
    await page.getByTestId('account-create').click()
    const editor = page.getByRole('dialog')
    await editor.getByRole('textbox', { name: '校园账号', exact: true }).fill(username)
    await editor.getByRole('textbox', { name: '姓名', exact: true }).fill('浏览器账号管理学生')
    await editor.getByRole('textbox', { name: '院系', exact: true }).fill('CS')
    await editor.getByLabel('初始密码', { exact: true }).fill(initialPassword)
    const [createdResponse] = await Promise.all([
      page.waitForResponse(apiResponse('/admin/accounts', 'POST')),
      editor.getByRole('button', { name: '创建账号', exact: true }).click(),
    ])
    expect(createdResponse.status()).toBe(201)
    fixtureId = (await createdResponse.json()).id
    const id = fixtureId!
    await expect(editor).toHaveCount(0)
    await searchAccount(page, username)
    const row = accountRow(page, id)
    await expect(row).toContainText(username)
    await expect(row).toContainText('浏览器账号管理学生')
    await login(student, username, initialPassword)

    // A name-only change must retain the user's active session.
    await row.getByRole('button', { name: '编辑账号' }).click()
    await editor.getByRole('textbox', { name: '姓名', exact: true }).fill('已更新姓名')
    await saveAccount(page, id)
    await expect(row).toContainText('已更新姓名')
    const preservedSession = await student.request.get('/api/v1/auth/me')
    expect(preservedSession.status()).toBe(200)
    expect((await preservedSession.json()).displayName).toBe('已更新姓名')

    // Permission and department changes revoke already-issued session cookies.
    await row.getByRole('button', { name: '编辑账号' }).click()
    await editor.getByRole('textbox', { name: '院系', exact: true }).fill('ART')
    await editor.getByText('教室申请人', { exact: true }).click()
    await expect(editor.getByRole('checkbox', { name: '教室申请人', exact: true })).toBeChecked()
    await saveAccount(page, id)
    await expect(row).toContainText('艺术学院')
    await expect(row).toContainText('教室申请人')
    expect((await student.request.get('/api/v1/auth/me')).status()).toBe(401)
    await login(student, username, initialPassword)
    await expect(student.getByRole('link', { name: /教室申请/ })).toBeVisible()
    await expect(student.getByRole('link', { name: /账号管理/ })).toHaveCount(0)

    await row.getByRole('button', { name: '重置密码' }).click()
    const passwordDialog = page.getByRole('dialog')
    await passwordDialog.getByLabel('新密码', { exact: true }).fill(replacementPassword)
    await passwordDialog.getByLabel('确认新密码', { exact: true }).fill(replacementPassword)
    const [passwordResponse] = await Promise.all([
      page.waitForResponse(apiResponse(`/admin/accounts/${id}/password`, 'POST')),
      passwordDialog.getByRole('button', { name: '确认重置密码' }).click(),
    ])
    expect(passwordResponse.status()).toBe(200)
    await expect(passwordDialog).toHaveCount(0)
    expect((await student.request.get('/api/v1/auth/me')).status()).toBe(401)
    await rejectLogin(student, username, initialPassword)
    await login(student, username, replacementPassword)

    await row.getByRole('button', { name: '编辑账号' }).click()
    await editor.getByRole('switch', { name: '账号启用状态' }).locator('..').click()
    await expect(editor.getByRole('switch', { name: '账号启用状态' })).not.toBeChecked()
    await saveAccount(page, id)
    await expect(row).toContainText('停用')
    expect((await student.request.get('/api/v1/auth/me')).status()).toBe(401)
    await rejectLogin(student, username, replacementPassword)

    await row.getByRole('button', { name: '编辑账号' }).click()
    await editor.getByRole('switch', { name: '账号启用状态' }).locator('..').click()
    await expect(editor.getByRole('switch', { name: '账号启用状态' })).toBeChecked()
    await saveAccount(page, id)
    await expect(row).toContainText('启用')
    await login(student, username, replacementPassword)
    await student.getByRole('button', { name: '退出登录' }).click()
    await expect(student).toHaveURL(/\/login$/)

    await searchAccount(page, adminUsername)
    await accountRow(page, admin.id).getByRole('button', { name: '编辑账号' }).click()
    await expect(editor.getByRole('checkbox', { name: '管理员', exact: true })).toBeChecked()
    await expect(editor.getByRole('checkbox', { name: '管理员', exact: true })).toBeDisabled()
    await expect(editor.getByRole('switch', { name: '账号启用状态' })).toBeChecked()
    await expect(editor.getByRole('switch', { name: '账号启用状态' })).toBeDisabled()
    await editor.getByRole('button', { name: '取消', exact: true }).click()
    // This click also guards the administrator sidebar at the common 1280×720 viewport.
    await expect(page.getByRole('button', { name: '退出登录' })).toBeInViewport()
    await page.getByRole('button', { name: '退出登录' }).click()
    await expect(page).toHaveURL(/\/login$/)
  } finally {
    try {
      if (fixtureId !== undefined) await deactivateFixture(page.context(), fixtureId, username)
    } finally {
      await studentContext.close()
    }
  }
})
