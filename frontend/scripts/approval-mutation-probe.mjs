// Controlled local permission mutation. Always restore the exact original bytes.
import { readFileSync, writeFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { spawnSync } from 'node:child_process'
const root = fileURLToPath(new URL('../', import.meta.url))
const source = new URL('../src/lib/applications.ts', import.meta.url)
const original = readFileSync(source)
const needle = 'record.applicantDepartment === user.department'
const text = original.toString('utf8')
if (text.split(needle).length !== 2)
  throw new Error('Mutation target changed: inspect the permission rule first')
const run = () =>
  spawnSync(
    process.execPath,
    ['node_modules/vitest/vitest.mjs', 'run', 'src/tests/applications-permissions.test.ts'],
    { cwd: root, encoding: 'utf8', timeout: 120000, env: { ...process.env, NO_COLOR: '1' } },
  )
try {
  writeFileSync(
    source,
    text.replace(needle, 'true /* controlled mutation: department boundary removed */'),
  )
  const result = run()
  if (
    result.error ||
    result.status !== 1 ||
    !`${result.stdout}${result.stderr}`.includes('applies department rules')
  )
    throw new Error(
      `Expected department-permission assertion to fail, got ${result.status}: ${result.error?.message ?? result.stderr}`,
    )
  console.log('PASS: removing the department boundary was detected by its permission assertion.')
} finally {
  writeFileSync(source, original)
  if (!readFileSync(source).equals(original)) throw new Error('Source restoration failed')
}
const restored = run()
if (restored.error || restored.status !== 0)
  throw new Error(`Restored permission tests failed: ${restored.error?.message ?? restored.stderr}`)
console.log('PASS: original source bytes restored; all 21 permission tests pass again.')
