ALTER TABLE rental_requests ADD COLUMN checkout_id uuid;
ALTER TABLE rental_requests ADD COLUMN reviewed_by uuid REFERENCES app_users(id);
CREATE UNIQUE INDEX rental_requests_checkout_idx ON rental_requests(checkout_id) WHERE checkout_id IS NOT NULL;
