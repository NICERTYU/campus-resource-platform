import { describe, expect, it } from 'vitest'
import { ApiError } from '../lib/api'
import {
  approvalDate,
  approvalDeadlinePassed,
  approvalFailure,
  approvalNotice,
  approvalResultMessage,
  approvalStage,
  canPerformApprovalAction,
  canResubmitApplication,
  canReviewApplication,
  canViewApprovalHistory,
  isApprovalPolicy,
} from '../lib/applications'
import type { Application, User } from '../lib/types'
import type { ApprovalPolicy } from '../lib/applications'

const now = Date.parse('2026-10-27T09:00:00+08:00')
const applicant: User = {
  id: 2,
  username: 'applicant',
  displayName: '申请人',
  department: 'CS',
  roles: ['APPLICANT'],
}
const admin: User = { ...applicant, id: 1, roles: ['ADMIN'] }
const record: Application = {
  id: 101,
  classroomId: 1,
  classroomName: '知行101',
  applicantId: 2,
  applicantName: '申请人',
  startTime: '2026-10-28T10:00:00+08:00',
  endTime: '2026-10-28T11:00:00+08:00',
  purpose: '课程讨论',
  status: 'PENDING',
  comment: null,
  pendingAction: null,
  lastError: null,
  currentStep: 1,
  totalSteps: 2,
  revision: 0,
  approvalDeadline: '2026-10-27T10:00:00+08:00',
  applicantDepartment: 'CS',
}
const policy: ApprovalPolicy = {
  steps: 2,
  timeout: 'PT24H',
  reviewSteps: [1, 2],
  legacyReview: true,
}
describe('M2-YZ record-level permission matrix', () => {
  it.each([null, applicant, { ...applicant, roles: ['STUDENT'] as User['roles'] }])(
    'never grants review to an anonymous, applicant or student account: %j',
    (user) => {
      expect(canReviewApplication(record, user, policy, now)).toBe(false)
    },
  )
  it('requires current-step authorization, not just ADMIN', () => {
    expect(canReviewApplication(record, admin, policy, now)).toBe(true)
    expect(canReviewApplication(record, admin, { ...policy, reviewSteps: [2] }, now)).toBe(false)
    expect(
      canReviewApplication(
        { ...record, currentStep: 2 },
        admin,
        { ...policy, reviewSteps: [2] },
        now,
      ),
    ).toBe(true)
    expect(
      canReviewApplication(
        { ...record, currentStep: 2 },
        admin,
        { ...policy, reviewSteps: [1] },
        now,
      ),
    ).toBe(false)
  })
  it('applies department rules to first review but not final review or public applicants', () => {
    const other = { ...admin, department: 'ART' }
    expect(canReviewApplication(record, other, policy, now)).toBe(false)
    expect(canReviewApplication({ ...record, currentStep: 2 }, other, policy, now)).toBe(true)
    expect(canReviewApplication({ ...record, applicantDepartment: null }, other, policy, now)).toBe(
      true,
    )
  })
  it('uses legacyReview separately and keeps actual record steps despite new-policy steps', () => {
    const legacy = { ...record, totalSteps: 1 }
    expect(canReviewApplication(legacy, admin, { ...policy, reviewSteps: [] }, now)).toBe(true)
    expect(canReviewApplication(legacy, admin, { ...policy, legacyReview: false }, now)).toBe(false)
    expect(
      canReviewApplication({ ...record, currentStep: 2 }, admin, { ...policy, steps: 1 }, now),
    ).toBe(true)
    expect(approvalStage(legacy)).toBe('历史单级审批')
  })
  it('fails closed when policy or record metadata is absent or invalid', () => {
    expect(canReviewApplication(record, admin, null, now)).toBe(false)
    for (const changes of [
      { totalSteps: 0 },
      { currentStep: 3 },
      { currentStep: 1.5 },
      { approvalDeadline: 'invalid' },
      { pendingAction: 'APPROVE' },
    ])
      expect(canReviewApplication({ ...record, ...changes }, admin, policy, now)).toBe(false)
    expect(isApprovalPolicy(policy)).toBe(true)
    for (const value of [
      null,
      [],
      {},
      { ...policy, reviewSteps: [0] },
      { ...policy, legacyReview: undefined },
    ])
      expect(isApprovalPolicy(value)).toBe(false)
  })
  it('closes review and resubmission at the exact deadline without changing server state', () => {
    const deadline = Date.parse(record.approvalDeadline)
    expect(canReviewApplication(record, admin, policy, deadline - 1)).toBe(true)
    expect(canReviewApplication(record, admin, policy, deadline)).toBe(false)
    expect(canReviewApplication(record, admin, policy, deadline + 1)).toBe(false)
    expect(approvalDeadlinePassed(record, deadline)).toBe(true)
    expect(record.status).toBe('PENDING')
    expect(approvalNotice(record, deadline)).toContain('等待服务端确认')
    expect(canResubmitApplication({ ...record, status: 'RETURNED' }, applicant, deadline)).toBe(
      false,
    )
  })
  it.each(['RETURNED', 'PROCESSING', 'APPROVED', 'REJECTED', 'WITHDRAWN', 'EXPIRED'])(
    'does not expose review for %s',
    (status) => {
      expect(canReviewApplication({ ...record, status }, admin, policy, now)).toBe(false)
    },
  )
  it('allows only a qualified owner to resubmit, never a different admin or a demoted owner', () => {
    const returned = { ...record, status: 'RETURNED' }
    expect(canResubmitApplication(returned, applicant, now)).toBe(true)
    expect(canResubmitApplication(returned, admin, now)).toBe(false)
    expect(canResubmitApplication(returned, { ...applicant, roles: ['STUDENT'] }, now)).toBe(false)
    expect(canResubmitApplication(returned, null, now)).toBe(false)
  })
  it('restricts histories to owner/admin and keeps safe withdrawal actions available', () => {
    expect(canViewApprovalHistory(record, applicant)).toBe(true)
    expect(canViewApprovalHistory(record, admin)).toBe(true)
    expect(canViewApprovalHistory(record, { ...applicant, id: 3 })).toBe(false)
    expect(
      canPerformApprovalAction(
        'withdraw',
        { ...record, status: 'PROCESSING', pendingAction: 'APPROVE' },
        applicant,
        null,
        now,
      ),
    ).toBe(true)
    expect(
      canPerformApprovalAction(
        'withdraw',
        { ...record, status: 'PROCESSING', pendingAction: 'EXPIRE' },
        applicant,
        policy,
        now,
      ),
    ).toBe(false)
    expect(
      canPerformApprovalAction('resubmit', { ...record, status: 'RETURNED' }, applicant, null, now),
    ).toBe(true)
  })
})
describe('M2-YZ honest progress and traceable errors', () => {
  it('distinguishes confirmed expiry from pending resource establishment and release', () => {
    expect(approvalNotice({ ...record, status: 'EXPIRED' }, now)).toContain('已超时撤销')
    expect(
      approvalNotice({ ...record, status: 'PROCESSING', pendingAction: 'EXPIRE' }, now),
    ).toContain('尚未确认恢复')
    expect(
      approvalNotice({ ...record, status: 'PROCESSING', pendingAction: 'WITHDRAW' }, now),
    ).toContain('尚未确认恢复')
    expect(
      approvalNotice({ ...record, status: 'PROCESSING', pendingAction: 'APPROVE' }, now),
    ).toContain('尚未确认占用')
    expect(approvalResultMessage('approve', { ...record, currentStep: 2 })).toContain(
      '等待下一审批节点',
    )
    expect(
      approvalResultMessage('approve', {
        ...record,
        status: 'PROCESSING',
        pendingAction: 'APPROVE',
      }),
    ).toContain('尚未确认占用')
  })
  it.each([401, 403, 409])('keeps actual requestId and guidance for HTTP %s', (status) => {
    const result = approvalFailure(new ApiError(status, 'TEST', '服务端说明', 'request-real-42'))
    expect(result.message).toBe('服务端说明（请求编号 request-real-42）')
    expect(result.hint).toBeTruthy()
    if (status === 409) expect(result.hint).toContain('不会自动重放')
  })
  it('formats campus dates and does not invent a trace id on network failure', () => {
    expect(approvalDate(record.approvalDeadline)).toContain('10:00')
    expect(approvalDate(null)).toBe('未记录')
    expect(approvalDate('invalid')).toBe('未记录')
    expect(approvalFailure(new TypeError('network')).message).not.toContain('请求编号')
  })
})
