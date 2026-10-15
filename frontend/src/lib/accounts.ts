import type { Role } from './types'

export const accountRoles: Role[] = ['STUDENT', 'APPLICANT', 'TECHNICIAN', 'ADMIN']
export function usernameError(username: string) {
  return /^[a-zA-Z][a-zA-Z0-9_]{2,31}$/.test(username)
    ? ''
    : '账号需以字母开头，由3–32位字母、数字或下划线组成'
}
export function passwordError(password: string) {
  if (Array.from(password).length < 8) return '密码至少需要8个字符'
  if (new TextEncoder().encode(password).length > 72) return '密码过长，请缩短后重试'
  return ''
}
export function displayNameError(name: string) {
  return name.trim().length >= 1 && name.trim().length <= 120 ? '' : '姓名需为1–120个字符'
}
