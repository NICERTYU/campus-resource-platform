-- Preserve the applied local V4 migration and its saved in-app notification events.
-- Add the upstream scheduler fields and event types without rebuilding the table.
ALTER TABLE reservation_notifications
  DROP CONSTRAINT reservation_notifications_type_check;
ALTER TABLE reservation_notifications
  ADD CONSTRAINT reservation_notifications_type_check CHECK (type IN (
    'STARTING_SOON','CHECK_IN_REMINDER','NO_SHOW_CANCELLED',
    'PRE_START_REMINDER','NO_SHOW_WARNING','CANCELLED_NOTICE'
  )),
  ADD COLUMN scheduled_at TIMESTAMPTZ,
  ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
    CHECK (status IN ('PENDING','SENT','SKIPPED')),
  ADD COLUMN delivered_at TIMESTAMPTZ;
UPDATE reservation_notifications
  SET scheduled_at=created_at, status='SENT', delivered_at=created_at;
ALTER TABLE reservation_notifications
  ALTER COLUMN scheduled_at SET DEFAULT CURRENT_TIMESTAMP,
  ALTER COLUMN scheduled_at SET NOT NULL;
CREATE INDEX reservation_notifications_due ON reservation_notifications(status,scheduled_at);
