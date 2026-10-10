CREATE TABLE classroom_applications (
 id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 classroom_id BIGINT NOT NULL,
 classroom_name VARCHAR(120) NOT NULL,
 applicant_id BIGINT NOT NULL,
 applicant_name VARCHAR(120) NOT NULL,
 start_time TIMESTAMPTZ NOT NULL,
 end_time TIMESTAMPTZ NOT NULL,
 purpose VARCHAR(1000) NOT NULL,
 status VARCHAR(30) NOT NULL CHECK (status IN ('PENDING','PROCESSING','APPROVED','REJECTED','WITHDRAWN')),
 comment VARCHAR(1000),
 pending_action VARCHAR(30),
 last_error VARCHAR(1000),
 source_version BIGINT NOT NULL DEFAULT 0,
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 CHECK (end_time > start_time)
);
CREATE INDEX application_owner_idx ON classroom_applications(applicant_id, id DESC);
CREATE TABLE repair_orders (
 id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 classroom_id BIGINT NOT NULL,
 classroom_name VARCHAR(120) NOT NULL,
 reporter_id BIGINT NOT NULL,
 reporter_name VARCHAR(120) NOT NULL,
 technician_id BIGINT,
 technician_name VARCHAR(120),
 description VARCHAR(2000) NOT NULL,
 status VARCHAR(30) NOT NULL CHECK (status IN ('REPORTED','ASSIGNED','PROCESSING','IN_PROGRESS','AWAITING_ACCEPTANCE','ACCEPTED')),
 note VARCHAR(2000),
 comment VARCHAR(1000),
 pending_action VARCHAR(30),
 last_error VARCHAR(1000),
 restriction_start TIMESTAMPTZ,
 source_version BIGINT NOT NULL DEFAULT 0,
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX repair_visibility_idx ON repair_orders(reporter_id, technician_id, id DESC);
CREATE TABLE outbox (
 id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 source_type VARCHAR(2) NOT NULL CHECK (source_type IN ('M2','M3')),
 source_id BIGINT NOT NULL,
 version BIGINT NOT NULL,
 active BOOLEAN NOT NULL,
 classroom_id BIGINT NOT NULL,
 start_time TIMESTAMPTZ NOT NULL,
 end_time TIMESTAMPTZ,
 actor_id BIGINT NOT NULL,
 request_id VARCHAR(64) NOT NULL,
 action VARCHAR(30) NOT NULL,
 previous_status VARCHAR(30) NOT NULL,
 target_status VARCHAR(30) NOT NULL,
 status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','SENDING','DONE','REJECTED')),
 attempt_token UUID,
 lease_until TIMESTAMPTZ,
 due_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 attempts INT NOT NULL DEFAULT 0,
 error_code VARCHAR(80),
 error_status INT,
 last_error VARCHAR(1000),
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE (source_type, source_id, version)
);
CREATE INDEX outbox_pending_idx ON outbox(due_at) WHERE status IN ('PENDING','SENDING');
CREATE TABLE idempotency_results (
 actor_id BIGINT NOT NULL,
 action VARCHAR(100) NOT NULL,
 request_key VARCHAR(128) NOT NULL,
 signature VARCHAR(64) NOT NULL,
 source_type VARCHAR(2) NOT NULL,
 source_id BIGINT NOT NULL,
 outbox_id BIGINT,
 response_json TEXT,
 response_status INT,
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY (actor_id, action, request_key)
);
CREATE TABLE audit_logs (
 id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 actor_id BIGINT NOT NULL,
 source_type VARCHAR(2) NOT NULL,
 source_id BIGINT NOT NULL,
 action VARCHAR(30) NOT NULL,
 request_id VARCHAR(64) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
