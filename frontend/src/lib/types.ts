export type Role = 'STUDENT' | 'APPLICANT' | 'ADMIN' | 'TECHNICIAN'
export interface User {
  id: number
  username: string
  displayName: string
  department: string
  roles: Role[]
}
export interface Account extends Omit<User, 'department'> {
  department: string | null
  enabled: boolean
}
export interface Classroom {
  id: number
  name: string
  building: string
  floor: number
  department: string | null
  openTime: string
  closeTime: string
  enabled: boolean
  seatCount: number
}
export interface Seat {
  id: number
  classroomId: number
  label: string
  hasPower: boolean
  available: boolean
  enabled?: boolean
  restrictionTypes: string[]
}
export interface Reservation {
  id: number
  seatId: number
  seatLabel: string
  classroomId: number
  classroomName: string
  startTime: string
  endTime: string
  status: string
  checkInDeadline: string
  checkedInAt: string | null
  cancelReason: string | null
}
export interface Notification {
  id: number
  reservationId: number
  type: 'PRE_START_REMINDER' | 'NO_SHOW_WARNING' | 'CANCELLED_NOTICE'
  scheduledAt: string
  status: 'PENDING' | 'SENT' | 'SKIPPED'
  deliveredAt: string | null
}
export interface SeatStatistic {
  classroomId: number
  classroomName: string
  seatCount: number
  reservedHours: number
  usedHours: number
  utilizationRate: number
  cancelledCount: number
  noShowCount: number
  maintenanceHours: number
  from: string
  to: string
}
export interface Application {
  id: number
  classroomId: number
  classroomName: string
  applicantId: number
  applicantName: string
  startTime: string
  endTime: string
  purpose: string
  status: string
  comment: string | null
  pendingAction: string | null
  lastError: string | null
  currentStep: number
  totalSteps: number
  revision: number
  approvalDeadline: string
  applicantDepartment: string | null
}
export interface ApprovalHistory {
  id: number
  step: number
  revision: number
  action: string
  actorId: number | null
  actorName: string
  comment: string | null
  requestId: string
  createdAt: string
}
export interface RepairHistory {
  id: number
  action: string
  actorId: number
  actorName: string
  previousTechnicianId: number | null
  technicianId: number | null
  reason: string | null
  requestId: string
  createdAt: string
}
export interface RepairReview {
  repairId: number
  reviewerId: number
  rating: number
  comment: string | null
  reviewedAt: string
}
export interface RepairEscalation {
  id: number
  repairId: number
  level: number
  deadline: string
  escalatedAt: string
  notificationStatus: 'AVAILABLE'
}
export interface Repair {
  id: number
  classroomId: number
  classroomName: string
  reporterId: number
  reporterName: string
  technicianId: number | null
  technicianName: string | null
  createdAt?: string
  repairDeadline?: string | null
  description: string
  status: string
  note: string | null
  comment: string | null
  pendingAction: string | null
  lastError: string | null
}
export interface Violation {
  id: number
  reservationId: number
  reason: string
  createdAt: string
}
export interface Audit {
  id: number
  actorId?: number
  actorName?: string
  action: string
  targetType?: string
  targetId?: number
  requestId: string
  createdAt: string
}
