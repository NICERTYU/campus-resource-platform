CREATE TABLE repair_escalations (
 id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 repair_id BIGINT NOT NULL REFERENCES repair_orders(id),
 level INT NOT NULL CHECK (level=1),
 observed_status VARCHAR(30) NOT NULL,
 deadline TIMESTAMPTZ NOT NULL,
 escalated_at TIMESTAMPTZ NOT NULL,
 request_id VARCHAR(64) NOT NULL,
 notification_status VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE'
   CHECK (notification_status='AVAILABLE'),
 CHECK (escalated_at>=deadline),
 UNIQUE(repair_id,level)
);
CREATE INDEX repair_escalations_recent_idx ON repair_escalations(escalated_at DESC,id DESC);
