-- Index-only upgrade preserves all business records while improving timeout and availability lookups.
CREATE INDEX reservations_user_time ON reservations(user_id,start_time,end_time);
CREATE INDEX reservations_due ON reservations(check_in_deadline) WHERE status='PENDING';
CREATE INDEX seat_slots_reservation ON seat_slots(reservation_id);
CREATE INDEX restrictions_active ON restrictions(classroom_id,start_time,end_time) WHERE active;
