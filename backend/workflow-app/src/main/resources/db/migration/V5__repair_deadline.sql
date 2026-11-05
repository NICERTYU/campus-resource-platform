ALTER TABLE repair_orders ADD COLUMN repair_deadline TIMESTAMPTZ;
-- Legacy rows are initialized from created_at and the configured policy by the scanner.
CREATE INDEX repair_timeout_idx ON repair_orders(repair_deadline,id)
 WHERE status IN ('REPORTED','ASSIGNED','IN_PROGRESS','PROCESSING');
