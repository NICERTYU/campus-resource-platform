-- Reporter and technician visibility uses OR; give the technician branch its own index.
CREATE INDEX repair_technician_idx ON repair_orders(technician_id, id DESC) WHERE technician_id IS NOT NULL;
