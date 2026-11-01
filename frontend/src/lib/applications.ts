import { ApiError, errorMessage } from './api'
import { canWithdraw } from './permissions'
import type { Application, User } from './types'

export interface ApprovalPolicy {
  steps: number
  timeout: string
  reviewSteps: number[]
  legacyReview: boolean
}
export type ApprovalAction = 'approve' | 'reject' | 'return' | 'resubmit' | 'withdraw'
export interface ApprovalFeedback {
  message: string
  hint: string
}

export function isApprovalPolicy(value: unknown): value is ApprovalPolicy {
  if (!value || typeof value !== 'object') return false
  const policy = value as Partial<ApprovalPolicy>
  return (
    Number.isInteger(policy.steps) &&
    [1, 2].includes(policy.steps!) &&
    typeof policy.timeout === 'string' &&
    Array.isArray(policy.reviewSteps) &&
    policy.reviewSteps.every((step) => Number.isInteger(step) && [1, 2].includes(step)) &&
    typeof policy.legacyReview === 'boolean'
  )
}

export function approvalDeadlinePassed(record: Application, now = Date.now()) {
  const deadline = Date.parse(record.approvalDeadline)
  return Number.isFinite(deadline) && now >= deadline
}

function withinDeadline(record: Application, now: number) {
  const deadline = Date.parse(record.approvalDeadline)
  return Number.isFinite(deadline) && now < deadline
}

export function canReviewApplication(
  record: Application,
  user: User | null,
  policy: ApprovalPolicy | null,
  now = Date.now(),
) {
  if (
    !user?.roles.includes('ADMIN') ||
    !policy ||
    record.status !== 'PENDING' ||
    record.pendingAction ||
    !withinDeadline(record, now) ||
    ![1, 2].includes(record.totalSteps) ||
    !Number.isInteger(record.currentStep) ||
    record.currentStep < 1 ||
    record.currentStep > record.totalSteps
  )
    return false
  if (record.totalSteps === 1) return policy.legacyReview
  return (
    policy.reviewSteps.includes(record.currentStep) &&
    (record.currentStep !== 1 ||
      !record.applicantDepartment?.trim() ||
      record.applicantDepartment === user.department)
  )
}

export function canResubmitApplication(record: Application, user: User | null, now = Date.now()) {
  return (
    !!user &&
    user.id === record.applicantId &&
    user.roles.some((role) => ['APPLICANT', 'ADMIN'].includes(role)) &&
    record.status === 'RETURNED' &&
    !record.pendingAction &&
    withinDeadline(record, now)
  )
}

export function canViewApprovalHistory(record: Application, user: User | null) {
  return !!user && (user.id === record.applicantId || user.roles.includes('ADMIN'))
}

export function canPerformApprovalAction(
  action: ApprovalAction,
  record: Application,
  user: User | null,
  policy: ApprovalPolicy | null,
  now = Date.now(),
) {
  if (action === 'withdraw') return canWithdraw(record, user, now)
  if (action === 'resubmit') return canResubmitApplication(record, user, now)
  return canReviewApplication(record, user, policy, now)
}

export function approvalStage(record: Application) {
  if (record.totalSteps === 1) return '历史单级审批'
  if (record.totalSteps === 2 && record.currentStep === 1) return '第 1 级 · 院系初审'
  if (record.totalSteps === 2 && record.currentStep === 2) return '第 2 级 · 资源终审'
  return '审批节点信息待确认'
}

export function approvalDate(value: string | null | undefined) {
  if (!value || !Number.isFinite(Date.parse(value))) return '未记录'
  return new Date(value).toLocaleString('zh-CN', {
    timeZone: 'Asia/Shanghai',
    hourCycle: 'h23',
  })
}

export function approvalNotice(record: Application, now = Date.now()) {
  if (record.status === 'PROCESSING') {
    if (record.pendingAction === 'EXPIRE')
      return '审批期限已到，正在解除本申请占用；尚未确认恢复，请勿据此判断座位已空闲。'
    if (record.pendingAction === 'WITHDRAW')
      return '撤销请求已受理，正在解除本申请占用；尚未确认恢复。'
    return '终审资源确认仍在处理中，系统将继续处理；尚未确认占用，请勿重复提交审批。'
  }
  if (['PENDING', 'RETURNED'].includes(record.status) && approvalDeadlinePassed(record, now))
    return '已到审批截止时间，等待服务端确认超时撤销；当前不能再审核或补充重提。'
  if (record.status === 'EXPIRED') return '审批已超时撤销；如仍需使用教室，请重新申请。'
  if (record.status === 'RETURNED') return '请按退回意见补充用途后重新提交，原审批截止时间不延长。'
  if (record.status === 'PENDING' && record.currentStep === 2 && record.totalSteps === 2)
    return '院系初审已通过，等待资源终审；目前尚未形成整室占用。'
  return ''
}

export function approvalFailure(error: unknown): ApprovalFeedback {
  const hint =
    error instanceof ApiError && error.status === 401
      ? '登录已失效，请重新登录后查看申请。'
      : error instanceof ApiError && error.status === 403
        ? '服务端拒绝了本次操作，已重新核对记录范围和审批权限。'
        : error instanceof ApiError && error.status === 409
          ? '状态、版本或资源条件已变化。请核对刷新后的记录，关闭对话框并重新确认；不会自动重放操作。'
          : '本次操作未确认完成。请核对最新状态后再决定是否重试。'
  return { message: errorMessage(error), hint }
}

export function approvalResultMessage(action: ApprovalAction, record: Application) {
  if (record.status === 'PROCESSING') return approvalNotice(record)
  if (action === 'approve' && record.status === 'PENDING')
    return '本级审核已通过，等待下一审批节点；尚未形成整室占用。'
  if (record.status === 'APPROVED') return '审批已通过，资源服务已确认整室占用。'
  if (record.status === 'WITHDRAWN') return '申请已撤销；其他申请或维修产生的限制仍可能生效。'
  if (record.status === 'REJECTED') return '申请已驳回。'
  if (record.status === 'RETURNED') return '申请已退回补充，原审批期限不延长。'
  if (action === 'resubmit') return '用途已补充并重新提交，等待院系初审。'
  if (action === 'return') return '申请已退回院系初审，原审批期限不延长。'
  return '操作已提交，请以最新记录为准。'
}
