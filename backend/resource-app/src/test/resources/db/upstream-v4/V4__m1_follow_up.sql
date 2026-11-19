CREATE TABLE reservation_notifications (
  id BIGSERIAL PRIMARY KEY,
  reservation_id BIGINT NOT NULL REFERENCES reservations(id),
  user_id BIGINT NOT NULL REFERENCES users(id),
  type VARCHAR(40) NOT NULL CHECK (type IN ('PRE_START_REMINDER','NO_SHOW_WARNING','CANCELLED_NOTICE')),
  scheduled_at TIMESTAMPTZ NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','SENT','SKIPPED')),
  delivered_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL,
  UNIQUE(reservation_id,type)
);
CREATE INDEX reservation_notifications_due ON reservation_notifications(status,scheduled_at);
CREATE INDEX reservation_notifications_user ON reservation_notifications(user_id,created_at DESC,id DESC);
