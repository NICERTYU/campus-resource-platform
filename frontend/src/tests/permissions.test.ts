import { describe, expect, it } from 'vitest'
import { canWithdraw } from '../lib/permissions'
import type { User, Application } from '../lib/types'
const applicant: User = {
  id: 2,
  username: 'applicant',
  displayName: '申请人',
  department: 'CS',
  roles: ['APPLICANT'],
}
const admin: User = {
  id: 1,
  username: 'admin',
  displayName: '管理员',
  department: 'CS',
  roles: ['ADMIN'],
}
const application: Pick<Application, 'applicantId' | 'status' | 'startTime'> = {
  applicantId: 2,
  status: 'APPROVED',
  startTime: '2026-10-12T10:00:00+08:00',
}
const before = Date.parse('2026-10-12T09:50:00+08:00')
describe('application withdrawal eligibility', () => {
  it('allows administrators to withdraw another applicant approved request before start', () => {
    expect(canWithdraw(application, admin, before)).toBe(true)
  })
  it('allows the owner while rejecting another applicant or anonymous user', () => {
    expect(canWithdraw(application, applicant, before)).toBe(true)
    expect(canWithdraw(application, { ...applicant, id: 3 }, before)).toBe(false)
    expect(canWithdraw(application, null, before)).toBe(false)
  })
  it('rejects approved withdrawals at or after start for administrators and owners', () => {
    for (const user of [admin, applicant]) {
      expect(canWithdraw(application, user, Date.parse(application.startTime))).toBe(false)
      expect(canWithdraw(application, user, Date.parse(application.startTime) + 1)).toBe(false)
    }
  })
  it('allows pending withdrawal and rejects a processing request', () => {
    expect(
      canWithdraw(
        { ...application, status: 'PENDING' },
        admin,
        Date.parse(application.startTime) + 1,
      ),
    ).toBe(true)
    expect(canWithdraw({ ...application, status: 'PROCESSING' }, admin, before)).toBe(false)
  })
})
