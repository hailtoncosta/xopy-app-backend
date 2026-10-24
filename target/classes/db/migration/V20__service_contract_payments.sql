CREATE TABLE service_contracts (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 proposal_id uuid NOT NULL UNIQUE REFERENCES service_proposals(id),
 conversation_id uuid NOT NULL REFERENCES conversations(id),
 customer_id uuid NOT NULL REFERENCES app_users(id),
 provider_user_id uuid NOT NULL REFERENCES app_users(id),
 document_id uuid NOT NULL,
 filename text NOT NULL,
 amount numeric(18,2) NOT NULL CHECK(amount>0),
 status text NOT NULL DEFAULT 'awaiting_acceptance' CHECK(status IN ('awaiting_acceptance','awaiting_payment','payment_pending','payment_rejected','payment_cancelled','payment_review','paid','refunded','charged_back')),
 checkout_id uuid UNIQUE,
 accepted_at timestamptz,
 created_at timestamptz NOT NULL DEFAULT now(),
 updated_at timestamptz NOT NULL DEFAULT now(),
 CHECK(customer_id<>provider_user_id)
);
CREATE INDEX service_contracts_customer_idx ON service_contracts(customer_id);
CREATE INDEX service_contracts_provider_idx ON service_contracts(provider_user_id);
