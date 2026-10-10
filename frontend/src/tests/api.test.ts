import { afterEach, describe, expect, it, vi } from 'vitest'
import { request, refreshCsrf, write, ApiError, errorMessage } from '../lib/api'
const response = (body: unknown, status = 200) => ({
  ok: status >= 200 && status < 300,
  status,
  json: async () => body,
})
afterEach(() => vi.unstubAllGlobals())
describe('same-origin authenticated API', () => {
  it('reads CSRF and includes session credentials and request key on writes', async () => {
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(response({ token: 'session-token', headerName: 'X-CSRF-TOKEN' }))
      .mockResolvedValueOnce(response({ id: 1 }))
    vi.stubGlobal('fetch', fetch)
    await refreshCsrf()
    await write('/reservations', { seatId: 1 })
    expect(fetch.mock.calls[1][0]).toBe('/api/v1/reservations')
    expect(fetch.mock.calls[1][1]).toMatchObject({
      credentials: 'same-origin',
      method: 'POST',
      body: '{"seatId":1}',
      headers: { 'X-CSRF-TOKEN': 'session-token' },
    })
    expect(fetch.mock.calls[1][1].headers['Idempotency-Key']).toBeTruthy()
  })
  it('preserves an idempotency key when retrying an ambiguous network failure', async () => {
    const fetch = vi
      .fn()
      .mockRejectedValueOnce(new TypeError('network unavailable'))
      .mockResolvedValueOnce(response({ id: 77 }))
      .mockResolvedValueOnce(response({ id: 78 }))
    vi.stubGlobal('fetch', fetch)
    await expect(write('/retry-test', { seatId: 77 })).rejects.toThrow()
    await write('/retry-test', { seatId: 77 })
    await write('/retry-test', { seatId: 77 })
    expect(fetch.mock.calls[1][1].headers['Idempotency-Key']).toBe(
      fetch.mock.calls[0][1].headers['Idempotency-Key'],
    )
    expect(fetch.mock.calls[2][1].headers['Idempotency-Key']).not.toBe(
      fetch.mock.calls[1][1].headers['Idempotency-Key'],
    )
  })
  it.each([401, 403, 409])(
    'preserves stable security/conflict error details for %s',
    async (status) => {
      vi.stubGlobal(
        'fetch',
        vi
          .fn()
          .mockResolvedValue(
            response({ code: 'DENIED', message: '无权执行此操作', requestId: 'req-42' }, status),
          ),
      )
      try {
        await request('/protected')
        expect.fail('request should reject')
      } catch (error) {
        expect(error).toBeInstanceOf(ApiError)
        expect(error).toMatchObject({ status, code: 'DENIED', requestId: 'req-42' })
        expect(errorMessage(error)).toBe('无权执行此操作（请求编号 req-42）')
      }
    },
  )
  it('does not attach CSRF to read-only requests', async () => {
    const fetch = vi.fn().mockResolvedValue(response([]))
    vi.stubGlobal('fetch', fetch)
    await request('/classrooms')
    expect(fetch.mock.calls[0][1].headers['X-CSRF-TOKEN']).toBeUndefined()
  })
  it('accepts empty successful responses', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response(undefined, 204)))
    await expect(request('/auth/logout')).resolves.toBeUndefined()
  })
  it('signals session expiration for protected requests without redirecting failed login', async () => {
    const expired = vi.fn()
    window.addEventListener('campus:session-expired', expired)
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(response({ code: 'UNAUTHENTICATED', message: '请重新登录' }, 401)),
    )
    await expect(request('/reservations/mine')).rejects.toThrow('请重新登录')
    expect(expired).toHaveBeenCalledTimes(1)
    await expect(request('/auth/login')).rejects.toThrow('请重新登录')
    expect(expired).toHaveBeenCalledTimes(1)
    window.removeEventListener('campus:session-expired', expired)
  })
})
