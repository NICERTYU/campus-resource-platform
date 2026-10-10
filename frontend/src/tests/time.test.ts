import { describe, expect, it, vi, afterEach } from 'vitest'
import {
  campusDate,
  campusHour,
  isoHour,
  isoMinute,
  validHours,
  validTimes,
  nextSlot,
} from '../lib/time'
afterEach(() => vi.useRealTimers())
describe('campus timezone and interval inputs', () => {
  it('uses Shanghai date across UTC midnight', () => {
    const date = new Date('2026-10-10T18:30:00Z')
    expect(campusDate(date)).toBe('2026-10-11')
    expect(campusHour(date)).toBe(2)
  })
  it('serializes hour and minute precision with explicit offset', () => {
    expect(isoHour('2026-10-10', 9)).toBe('2026-10-10T09:00:00+08:00')
    expect(isoHour('2026-10-10', 24)).toBe('2026-10-11T00:00:00+08:00')
    expect(isoMinute('2026-10-10', '09:35')).toBe('2026-10-10T09:35:00+08:00')
  })
  it('rejects empty and reversed ranges and accepts adjacent hours', () => {
    expect(validHours(9, 10)).toBe(true)
    expect(validHours(9, 9)).toBe(false)
    expect(validHours(9.5, 10)).toBe(false)
    expect(validHours(23, 25)).toBe(false)
    expect(validTimes('09:05', '09:06')).toBe(true)
    expect(validTimes('10:00', '09:59')).toBe(false)
    expect(validTimes('29:00', '30:00')).toBe(false)
  })
  it('offers tomorrow morning after opening hours', () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-10-10T15:50:00Z'))
    expect(nextSlot()).toEqual({ date: '2026-10-11', hour: 9 })
  })
})
