CREATE TABLE repair_reviews (
 repair_id BIGINT PRIMARY KEY REFERENCES repair_orders(id),
 reviewer_id BIGINT NOT NULL,
 rating INT NOT NULL CHECK (rating BETWEEN 1 AND 5),
 comment VARCHAR(1000),
 request_key VARCHAR(128) NOT NULL,
 reviewed_at TIMESTAMPTZ NOT NULL
);
