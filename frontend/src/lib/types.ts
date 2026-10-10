export type Role = 'STUDENT' | 'APPLICANT' | 'ADMIN' | 'TECHNICIAN'
export interface User {
  id: number
  username: string
  displayName: string
  department: string
  roles: Role[]
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
}
export interface Repair {
  id: number
  classroomId: number
  classroomName: string
  reporterId: number
  reporterName: string
  technicianId: number | null
  technicianName: string | null
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
