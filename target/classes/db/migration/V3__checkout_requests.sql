CREATE TABLE checkout_requests (
 buyer_id uuid NOT NULL REFERENCES app_users(id),
 request_id uuid NOT NULL,
 fingerprint text NOT NULL,
 result jsonb,
 created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(buyer_id, request_id)
);
