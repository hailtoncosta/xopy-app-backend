ALTER TABLE products ADD COLUMN shipping_cost numeric(12,2) NOT NULL DEFAULT 0 CHECK (shipping_cost >= 0);
ALTER TABLE rental_requests ADD COLUMN shipping_cost numeric(12,2) NOT NULL DEFAULT 0 CHECK (shipping_cost >= 0);
