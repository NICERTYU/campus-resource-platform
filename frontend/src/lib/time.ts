export const campusDate = (value: Date = new Date()) =>
  new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(value)
export const campusHour = (value: Date = new Date()) =>
  Number(
    new Intl.DateTimeFormat('en-GB', {
      timeZone: 'Asia/Shanghai',
      hour: '2-digit',
      hourCycle: 'h23',
    }).format(value),
  )
export function isoHour(date: string, hour: number) {
  if (hour === 24) {
    const next = new Date(`${date}T12:00:00+08:00`)
    next.setUTCDate(next.getUTCDate() + 1)
    return `${campusDate(next)}T00:00:00+08:00`
  }
  return `${date}T${String(hour).padStart(2, '0')}:00:00+08:00`
}
export function dateTime(value: string) {
  return new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
  }).format(new Date(value))
}
export function interval(start: string, end: string) {
  return `${dateTime(start)} — ${dateTime(end)}`
}
export function nextSlot() {
  const hour = campusHour() + 1
  return hour >= 22
    ? { date: campusDate(new Date(Date.now() + 86400000)), hour: 9 }
    : { date: campusDate(), hour: Math.max(8, hour) }
}
export function validHours(start: number, end: number) {
  return Number.isInteger(start) && Number.isInteger(end) && start >= 0 && end <= 24 && end > start
}

export function isoMinute(date: string, time: string) {
  return `${date}T${time}:00+08:00`
}
export function validTimes(start: string, end: string) {
  return (
    /^([01]\d|2[0-3]):[0-5]\d$/.test(start) && /^([01]\d|2[0-3]):[0-5]\d$/.test(end) && end > start
  )
}
