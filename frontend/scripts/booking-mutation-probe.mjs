// Controlled local mutation: a deadline must close check-in without changing server status.
// Restore exact bytes even if the mutant or verification fails.
import { readFileSync, writeFileSync } from 'node:fs'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
const root = fileURLToPath(new URL('../', import.meta.url))
const source = new URL('../src/lib/bookings.ts', import.meta.url)
const original = readFileSync(source)
const needle = 'if (now >= deadline)'
if (original.toString().split(needle).length !== 2)
  throw new Error('Inspect the mutation target first')
const run = () =>
  spawnSync(
    process.execPath,
    ['node_modules/vitest/vitest.mjs', 'run', 'src/tests/bookings-rules.test.ts'],
    { cwd: root, encoding: 'utf8', timeout: 120000, env: { ...process.env, NO_COLOR: '1' } },
  )
try {
  writeFileSync(
    source,
    original
      .toString()
      .replace(needle, 'if (false /* controlled mutation: deadline guard removed */)'),
  )
  const result = run()
  if (
    result.error ||
    result.status !== 1 ||
    !`${result.stdout}${result.stderr}`.includes('handles boundary 10:15:00')
  )
    throw new Error(
      `Expected deadline assertion failure: ${result.error?.message ?? result.stdout + result.stderr}`,
    )
  console.log('PASS: removing the check-in deadline guard failed its exact-boundary assertion.')
} finally {
  writeFileSync(source, original)
  if (!readFileSync(source).equals(original)) throw new Error('Source restoration failed')
}
const restored = run()
if (restored.error || restored.status !== 0)
  throw new Error(`Restored tests failed: ${restored.stdout}${restored.stderr}`)
console.log('PASS: exact source restored and all booking rules pass.')
