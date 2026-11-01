export const statusLabel: Record<string, string> = {
  PENDING: '待处理',
  CHECKED_IN: '已签到',
  CANCELLED: '已取消',
  NO_SHOW: '未签到',
  COMPLETED: '已结束',
  PROCESSING: '同步处理中',
  APPROVED: '已通过',
  REJECTED: '已驳回',
  WITHDRAWN: '已撤销',
  RETURNED: '待补充重提',
  EXPIRED: '超时已撤销',
  REPORTED: '待派单',
  ASSIGNED: '已派单',
  IN_PROGRESS: '维修中',
  AWAITING_ACCEPTANCE: '待验收',
  ACCEPTED: '已验收',
}
export const roleLabel: Record<string, string> = {
  STUDENT: '学生',
  APPLICANT: '教室申请人',
  ADMIN: '管理员',
  TECHNICIAN: '维修人员',
}
export const restrictionLabel: Record<string, string> = {
  M1: '已被预约',
  M2: '整室占用',
  M3: '维修下架',
  RESERVATION: '已被预约',
  APPLICATION: '整室占用',
  REPAIR: '维修下架',
  DISABLED: '已停用',
  DEPARTMENT: '院系限制',
  CLOSED: '未开放',
}
export function statusType(status: string): 'success' | 'warning' | 'info' | 'danger' {
  return ['CHECKED_IN', 'APPROVED', 'ACCEPTED', 'COMPLETED'].includes(status)
    ? 'success'
    : ['CANCELLED', 'NO_SHOW', 'REJECTED', 'WITHDRAWN'].includes(status)
      ? 'info'
      : ['IN_PROGRESS', 'PROCESSING'].includes(status)
        ? 'danger'
        : 'warning'
}

export const reasonLabel: Record<string, string> = {
  USER: '用户取消',
  NO_SHOW: '未在签到窗口内到场签到',
  MAINTENANCE: '教室维修取消（不计违约）',
}
export const actionLabel: Record<string, string> = {
  'account.register': '注册学生账号',
  'account.create': '创建校园账号',
  'account.update': '更新账号与权限',
  'account.password': '重置账号密码',
  RESERVATION_CREATED: '创建座位预约',
  RESERVATION_CANCELLED: '取消座位预约',
  RESERVATION_CHECKED_IN: '到场签到',
  RESERVATION_COMPLETED: '预约结束',
  RESERVATION_MAINTENANCE_CANCELLED: '维修取消预约',
  RESERVATION_NO_SHOW: '记录未签到违约',
  RESTRICTION_ESTABLISHED: '建立教室限制',
  RESTRICTION_RELEASED: '解除教室限制',
  SETTINGS_UPDATED: '更新预约参数',
  CLASSROOM_CREATED: '新增教室',
  CLASSROOM_UPDATED: '编辑教室',
  SEAT_CREATED: '新增座位',
  SEAT_UPDATED: '编辑座位',
  CHECK_IN_CODE_UPDATED: '设置当天签到码',
}
