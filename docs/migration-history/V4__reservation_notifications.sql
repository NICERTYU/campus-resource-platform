CREATE TABLE reservation_notifications (
    id BIGSERIAL PRIMARY KEY,
    reservation_id BIGINT NOT NULL REFERENCES reservations(id),
    user_id BIGINT NOT NULL REFERENCES users(id),
    type VARCHAR(40) NOT NULL CHECK (type IN ('STARTING_SOON','CHECK_IN_REMINDER','NO_SHOW_CANCELLED')),
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (reservation_id, type)
);
CREATE INDEX reservation_notifications_mine ON reservation_notifications(user_id, created_at DESC, id DESC);
