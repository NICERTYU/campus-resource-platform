let csrfToken = ''
let csrfHeader = 'X-CSRF-TOKEN'
export class ApiError extends Error {
  constructor(
    public status: number,
    public code: string,
    message: string,
    public requestId?: string,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}
export async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  const response = await fetch(`/api/v1${path}`, {
    ...options,
    credentials: 'same-origin',
    headers: {
      'Content-Type': 'application/json',
      ...(options.method && options.method !== 'GET' ? { [csrfHeader]: csrfToken } : {}),
      ...options.headers,
    },
  })
  const body = response.status === 204 ? undefined : await response.json().catch(() => undefined)
  if (!response.ok) {
    if (response.status === 401 && !['/auth/me', '/auth/login', '/auth/csrf'].includes(path)) {
      window.dispatchEvent(new Event('campus:session-expired'))
    }
    throw new ApiError(
      response.status,
      body?.code ?? 'REQUEST_FAILED',
      body?.message ?? '请求未完成，请稍后重试',
      body?.requestId,
    )
  }
  return body as T
}
export async function refreshCsrf() {
  const result = await request<{ token: string; headerName: string }>('/auth/csrf')
  csrfToken = result.token
  csrfHeader = result.headerName
}
const pendingKeys = new Map<string, string>()
function newRequestKey() {
  if (typeof crypto.randomUUID === 'function') return crypto.randomUUID()
  // Public HTTP cannot use randomUUID, but still supports secure random bytes.
  const bytes = crypto.getRandomValues(new Uint8Array(16))
  bytes[6] = (bytes[6]! & 0x0f) | 0x40
  bytes[8] = (bytes[8]! & 0x3f) | 0x80
  const hex = Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}
export async function write<T>(
  path: string,
  body: unknown = {},
  method = 'POST',
  explicitKey?: string,
) {
  const serialized = JSON.stringify(body),
    operation = `${method}:${path}:${serialized}`
  const key = explicitKey ?? pendingKeys.get(operation) ?? newRequestKey()
  pendingKeys.set(operation, key)
  try {
    const result = await request<T>(path, {
      method,
      headers: { 'Idempotency-Key': key },
      body: serialized,
    })
    pendingKeys.delete(operation)
    return result
  } catch (error) {
    if (error instanceof ApiError && error.status < 500) pendingKeys.delete(operation)
    throw error
  }
}
export function errorMessage(error: unknown) {
  if (error instanceof ApiError)
    return `${error.message}${error.requestId ? `（请求编号 ${error.requestId}）` : ''}`
  return '连接服务失败，请检查网络后重试'
}
