ALTER TABLE conversations
 ADD COLUMN service_case_status text NOT NULL DEFAULT 'active' CHECK(service_case_status IN ('active','cancelled','disputed')),
 ADD COLUMN service_case_reason text NOT NULL DEFAULT '',
 ADD COLUMN service_case_actor uuid REFERENCES app_users(id),
 ADD COLUMN service_case_changed_at timestamptz;
ALTER TABLE service_proposals ADD COLUMN client_viewed_at timestamptz;
CREATE TABLE service_case_events (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 conversation_id uuid NOT NULL REFERENCES conversations(id),
 actor_id uuid NOT NULL REFERENCES app_users(id),
 action text NOT NULL CHECK(action IN ('cancel','dispute','resume','resolve_cancel')),
 reason text NOT NULL CHECK(length(reason) BETWEEN 1 AND 2000),
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX service_case_events_conversation_idx ON service_case_events(conversation_id,created_at,id);
CREATE VIEW service_engagement_status AS
 SELECT c.id AS conversation_id,c.user_id AS customer_id,s.user_id AS provider_user_id,
 customer.full_name AS customer_name,provider.full_name AS provider_name,
 coalesce(p.terms->>'scope','Negociação de serviço') AS scope,
 p.id AS proposal_id,p.status AS proposal_status,p.client_viewed_at,
 k.id AS contract_id,k.amount,k.status AS payment_state,k.execution_status,
 c.service_case_status,c.service_case_reason,c.created_at,
 greatest(c.updated_at,c.created_at,p.created_at,p.decided_at,p.client_viewed_at,k.updated_at,c.service_case_changed_at) AS updated_at,
 CASE
  WHEN c.service_case_status='cancelled' THEN 'cancelled'
  WHEN c.service_case_status='disputed' OR k.status='charged_back' THEN 'disputed'
  WHEN k.execution_status='completed' THEN 'service_completed'
  WHEN k.status='refunded' THEN 'refunded'
  WHEN k.status='payment_review' THEN 'payment_review'
  WHEN k.execution_status='awaiting_confirmation' THEN 'awaiting_delivery_confirmation'
  WHEN k.execution_status='in_progress' THEN 'service_in_progress'
  WHEN k.status='paid' THEN 'payment_confirmed'
  WHEN k.accepted_at IS NOT NULL THEN 'awaiting_payment'
  WHEN p.status='accepted' THEN 'awaiting_contract'
  WHEN p.status='pending' AND p.client_viewed_at IS NOT NULL THEN 'awaiting_client_approval'
  WHEN p.status='pending' THEN 'proposal_sent'
  ELSE 'negotiating'
 END AS engagement_status
 FROM conversations c JOIN service_providers s ON s.id=c.service_provider_id
 LEFT JOIN user_profiles customer ON customer.id=c.user_id
 LEFT JOIN user_profiles provider ON provider.id=s.user_id
 LEFT JOIN LATERAL (SELECT * FROM service_proposals x WHERE x.conversation_id=c.id ORDER BY version DESC LIMIT 1) p ON true
 LEFT JOIN service_contracts k ON k.proposal_id=p.id;
