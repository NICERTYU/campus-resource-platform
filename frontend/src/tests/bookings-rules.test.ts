import { describe, expect, it } from 'vitest'
import { checkInState, reuseDefaults, reuseValidation, validStatisticsDates } from '../lib/bookings'
import type { Reservation } from '../lib/types'
const record = {
  status: 'PENDING',
  startTime: '2026-11-06T10:00:00+08:00',
  endTime: '2026-11-06T11:00:00+08:00',
  checkInDeadline: '2026-11-06T10:15:00+08:00',
} as Reservation
const time = (value: string) => Date.parse(`2026-11-06T${value}+08:00`)
describe('M1 local hints respect authoritative server state', () => {
  it.each([
    ['09:44:59', '待签到，尚未开放签到窗口', false],
    ['09:45:00', '即将开始，可到场签到', true],
    ['10:00:00', '预约已开始，请及时签到', true],
    ['10:10:00', '尚未签到，请在截止前到场', true],
    ['10:14:59', '尚未签到，请在截止前到场', true],
    ['10:15:00', '签到窗口已结束，等待服务端确认', false],
  ])('handles boundary %s', (instant, label, canCheckIn) => {
    expect(checkInState(record, time(instant))).toEqual({ label, canCheckIn })
    expect(record.status).toBe('PENDING')
  })
  it('never turns a checked-in record back into a pending reminder', () => {
    expect(checkInState({ ...record, status: 'CHECKED_IN' }, time('10:12:00')).canCheckIn).toBe(
      false,
    )
  })
  it('preserves campus hours rather than reusing an expired date or UTC hour', () => {
    expect(reuseDefaults(record, time('23:30:00'))).toEqual({
      date: '2026-11-07',
      start: 10,
      end: 11,
    })
  })
  it.each([
    ['2026-11-05', 10, 11, '原时段已过期'],
    ['2026-12-07', 10, 11, '只能提前30天'],
    ['2026-11-07', 11, 10, '先后有序'],
    ['2026-11-07', 10.5, 11, '整点'],
    ['2026-02-30', 10, 11, '有效日期'],
  ])('rejects invalid reuse %s %s-%s', (date, start, end, message) => {
    expect(reuseValidation(date, start, end, time('09:00:00'))).toContain(message)
  })
  it('accepts a valid new interval and leaves room and duration limits to the server', () => {
    expect(reuseValidation('2026-11-07', 10, 11, time('09:00:00'))).toBe('')
  })
  it.each([
    ['2026-11-01', '2026-11-01', true],
    ['2026-11-01', '2026-12-01', true],
    ['2026-11-01', '2026-12-02', false],
    ['2026-11-02', '2026-11-01', false],
    ['2026-02-30', '2026-03-02', false],
    ['', '2026-11-01', false],
  ])('validates inclusive statistics dates %s—%s', (from, to, valid) => {
    expect(validStatisticsDates(from, to)).toBe(valid)
  })
})
