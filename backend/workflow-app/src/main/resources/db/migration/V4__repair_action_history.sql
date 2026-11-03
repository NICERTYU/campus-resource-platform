CREATE TABLE repair_history (
 id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 repair_id BIGINT NOT NULL REFERENCES repair_orders(id),
 action VARCHAR(30) NOT NULL CHECK (action IN ('ASSIGN','REASSIGN','RETURN')),
 actor_id BIGINT NOT NULL,
 actor_name VARCHAR(120) NOT NULL,
 previous_technician_id BIGINT,
 technician_id BIGINT,
 reason VARCHAR(1000),
 request_id VARCHAR(64) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL,
 CHECK (action='ASSIGN' OR (reason IS NOT NULL AND length(btrim(reason))>0))
);
CREATE INDEX repair_history_owner_idx ON repair_history(repair_id,id);
