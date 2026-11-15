import { campusDate, campusHour, isoHour, validHours } from './time'
import type { Reservation, ReservationNotification } from './types'

export function checkInState(record: Reservation, now = Date.now()) {
  if (record.status !== 'PENDING') return { label: '', canCheckIn: false }
  const start = Date.parse(record.startTime),
    deadline = Date.parse(record.checkInDeadline)
  if (now >= deadline) return { label: '签到窗口已结束，等待服务端确认', canCheckIn: false }
  if (now < start - 15 * 60000) return { label: '待签到，尚未开放签到窗口', canCheckIn: false }
  if (now >= start + 10 * 60000) return { label: '尚未签到，请在截止前到场', canCheckIn: true }
  if (now >= start) return { label: '预约已开始，请及时签到', canCheckIn: true }
  return { label: '即将开始，可到场签到', canCheckIn: true }
}

export function reuseDefaults(record: Reservation, now = Date.now()) {
  return {
    date: campusDate(new Date(now + 86400000)),
    start: campusHour(new Date(record.startTime)),
    end: campusHour(new Date(record.endTime)),
  }
}

export function reuseValidation(date: string, start: number, end: number, now = Date.now()) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(date) || !validHours(start, end))
    return '请选择有效日期和先后有序的整点时间'
  const instant = Date.parse(isoHour(date, start))
  if (!Number.isFinite(instant) || campusDate(new Date(instant)) !== date) return '请选择有效日期'
  if (instant < now) return '原时段已过期，请选择未来时段'
  if (instant > now + 30 * 86400000) return '只能提前30天预约'
  return ''
}

export const notificationLabel: Record<ReservationNotification['type'], string> = {
  STARTING_SOON: '预约即将开始',
  CHECK_IN_REMINDER: '预约开始后仍未签到',
  NO_SHOW_CANCELLED: '未签到，预约已取消',
}

export function validStatisticsDates(start: string, end: string) {
  if (![start, end].every((value) => /^\d{4}-\d{2}-\d{2}$/.test(value))) return false
  const from = Date.parse(`${start}T00:00:00+08:00`),
    to = Date.parse(`${end}T00:00:00+08:00`)
  return (
    Number.isFinite(from) &&
    Number.isFinite(to) &&
    campusDate(new Date(from)) === start &&
    campusDate(new Date(to)) === end &&
    to >= from &&
    to - from < 31 * 86400000
  )
}
