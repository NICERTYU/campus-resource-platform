import { expect, test } from '@playwright/test'

// Cloud acceptance may use private credentials; do not retain login request bodies.
test.use({ trace: 'off' })

test('缺少 randomUUID 时可以登录、退出并显示错误口令的业务提示', async ({ page, baseURL }) => {
  const loginUrl = new URL('/login', baseURL ?? 'http://localhost:5173')
  const username = process.env.CAMPUS_LOGIN_USERNAME ?? 'student'
  const password = process.env.CAMPUS_LOGIN_PASSWORD ?? 'Campus123!'
  const wrongPassword =
    password === 'runtime-regression-invalid-password'
      ? 'runtime-regression-invalid-password-2'
      : 'runtime-regression-invalid-password'
  const loginButton = page.getByRole('button', { name: '登录平台' })
  const isLoginResponse = (response: import('@playwright/test').Response) =>
    new URL(response.url()).pathname === '/api/v1/auth/login' &&
    response.request().method() === 'POST'

  await page.addInitScript(() => {
    Object.defineProperty(window.crypto, 'randomUUID', { value: undefined, configurable: true })
  })
  await page.goto(loginUrl.toString())
  expect(await page.evaluate(() => typeof window.crypto.randomUUID)).toBe('undefined')
  await page.getByPlaceholder('请输入账号').fill(username)
  await page.getByPlaceholder('请输入密码').fill(password)
  const [loginResponse] = await Promise.all([
    page.waitForResponse(isLoginResponse, { timeout: 15000 }),
    loginButton.click(),
  ])
  expect(loginResponse.status()).toBe(200)
  await expect(page).toHaveURL(new URL('/seats', loginUrl).toString())

  const [logoutResponse] = await Promise.all([
    page.waitForResponse(
      (response) =>
        new URL(response.url()).pathname === '/api/v1/auth/logout' &&
        response.request().method() === 'POST',
      { timeout: 15000 },
    ),
    page.getByRole('button', { name: '退出登录' }).click(),
  ])
  expect(logoutResponse.ok()).toBe(true)
  await expect(page).toHaveURL(loginUrl.toString())

  await page.getByPlaceholder('请输入账号').fill(username)
  await page.getByPlaceholder('请输入密码').fill(wrongPassword)
  const [failedResponse] = await Promise.all([
    page.waitForResponse(isLoginResponse, { timeout: 15000 }),
    loginButton.click(),
  ])
  expect(failedResponse.status()).toBe(401)
  await expect(page.locator('.form-error')).toContainText('账号或密码错误')
  await expect(page.locator('.form-error')).not.toContainText('连接服务失败')
  await expect(page).toHaveURL(loginUrl.toString())
})
