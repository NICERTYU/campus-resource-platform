ALTER TABLE classroom_applications
  DROP CONSTRAINT classroom_applications_status_check,
  ADD COLUMN total_steps INT NOT NULL DEFAULT 1 CHECK (total_steps IN (1,2)),
  ADD COLUMN current_step INT NOT NULL DEFAULT 1,
  ADD COLUMN revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0),
  ADD COLUMN applicant_department VARCHAR(80),
  ADD COLUMN approval_deadline TIMESTAMPTZ;
UPDATE classroom_applications SET approval_deadline=LEAST(created_at+INTERVAL '24 hours',start_time);
ALTER TABLE classroom_applications
  ALTER COLUMN approval_deadline SET NOT NULL,
  ALTER COLUMN approval_deadline SET DEFAULT (CURRENT_TIMESTAMP+INTERVAL '24 hours'),
  ADD CHECK (current_step BETWEEN 1 AND total_steps),
  ADD CONSTRAINT classroom_applications_status_check
    CHECK (status IN ('PENDING','RETURNED','PROCESSING','APPROVED','REJECTED','WITHDRAWN','EXPIRED'));
CREATE INDEX application_expiry_idx ON classroom_applications(approval_deadline,id)
  WHERE status IN ('PENDING','RETURNED','PROCESSING');
CREATE TABLE application_history (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  application_id BIGINT NOT NULL REFERENCES classroom_applications(id) ON DELETE CASCADE,
  step INT NOT NULL CHECK (step IN (1,2)),
  revision BIGINT NOT NULL,
  action VARCHAR(30) NOT NULL,
  actor_id BIGINT,
  actor_name VARCHAR(120) NOT NULL,
  comment VARCHAR(1000),
  request_id VARCHAR(64) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  UNIQUE(application_id,revision,action)
);
CREATE INDEX application_history_owner_idx ON application_history(application_id,id);
ALTER TABLE outbox DROP CONSTRAINT outbox_status_check;
ALTER TABLE outbox ADD CONSTRAINT outbox_status_check
  CHECK (status IN ('PENDING','SENDING','DONE','REJECTED','SUPERSEDED'));
