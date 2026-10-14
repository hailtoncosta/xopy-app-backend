CREATE TABLE rental_requests (
 id uuid PRIMARY KEY, customer_id uuid NOT NULL REFERENCES app_users(id), company_id uuid REFERENCES app_users(id),
 equipment_id uuid NOT NULL, equipment_name text NOT NULL, days integer NOT NULL CHECK(days BETWEEN 1 AND 3650),
 cnpj text NOT NULL, delivery_address text NOT NULL, status text NOT NULL DEFAULT 'pending' CHECK(status IN ('pending','approved','rejected')),
 created_at timestamptz NOT NULL DEFAULT now(), reviewed_at timestamptz
);
CREATE INDEX rental_requests_company_idx ON rental_requests(company_id,created_at);
CREATE INDEX rental_requests_customer_idx ON rental_requests(customer_id,created_at);
CREATE TABLE rental_request_documents (
 id uuid PRIMARY KEY, request_id uuid NOT NULL REFERENCES rental_requests(id), kind text NOT NULL,
 filename text NOT NULL, content_type text NOT NULL, UNIQUE(request_id,kind)
);
