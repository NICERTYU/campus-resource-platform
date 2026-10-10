import type { Application, User } from './types'
export function canWithdraw(
  application: Pick<Application, 'applicantId' | 'status' | 'startTime'>,
  user: User | null,
  now = Date.now(),
) {
  if (!user || (!user.roles.includes('ADMIN') && application.applicantId !== user.id)) return false
  return (
    application.status === 'PENDING' ||
    (application.status === 'APPROVED' && now < new Date(application.startTime).getTime())
  )
}
