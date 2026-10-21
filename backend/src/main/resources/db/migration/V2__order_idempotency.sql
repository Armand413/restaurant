ALTER TABLE customer_order ADD COLUMN request_key UUID UNIQUE;
ALTER TABLE customer_order ADD COLUMN request_fingerprint VARCHAR(64);
