-- Extend the published upstream V4 without changing its migration checksum.
-- Keep all existing scheduled notifications, processing states and timestamps intact.
ALTER TABLE reservation_notifications
  DROP CONSTRAINT reservation_notifications_type_check;
ALTER TABLE reservation_notifications
  ADD CONSTRAINT reservation_notifications_type_check CHECK (type IN (
    'STARTING_SOON','CHECK_IN_REMINDER','NO_SHOW_CANCELLED',
    'PRE_START_REMINDER','NO_SHOW_WARNING','CANCELLED_NOTICE'
  ));
-- Local in-app event writers supply created_at and do not schedule external delivery.
ALTER TABLE reservation_notifications
  ALTER COLUMN scheduled_at SET DEFAULT CURRENT_TIMESTAMP;
