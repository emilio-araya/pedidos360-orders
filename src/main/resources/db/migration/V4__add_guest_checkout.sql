-- Guest checkout: the buyer leaves only an email, and the order is managed
-- through a secret tracking link. Only the SHA-256 of the link token is stored.
ALTER TABLE orders ADD customer_email VARCHAR(254);
ALTER TABLE orders ADD guest_token_hash VARCHAR(64);
ALTER TABLE orders ADD CONSTRAINT uk_orders_guest_token_hash UNIQUE (guest_token_hash);
