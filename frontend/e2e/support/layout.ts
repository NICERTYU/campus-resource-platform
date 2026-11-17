import { expect, type Page } from '@playwright/test'

export async function expectFitsViewport(page: Page) {
  // Resizing can precede a popper reposition or CSS transition; persistent overflow still fails.
  await expect
    .poll(() => page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth), {
      message: '页面应在响应式布局稳定后完整适配视口',
      timeout: 5000,
    })
    .toBeLessThanOrEqual(1)
}
