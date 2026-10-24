CREATE TABLE service_proposals (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 conversation_id uuid NOT NULL REFERENCES conversations(id),
 version integer NOT NULL CHECK(version>0),
 terms jsonb NOT NULL,
 status text NOT NULL DEFAULT 'pending' CHECK(status IN ('pending','accepted','rejected','changes_requested')),
 created_by uuid NOT NULL REFERENCES app_users(id),
 created_at timestamptz NOT NULL DEFAULT now(),
 decided_by uuid REFERENCES app_users(id),
 decided_at timestamptz,
 decision_note text,
 UNIQUE(conversation_id,version)
);
CREATE UNIQUE INDEX service_proposals_one_pending ON service_proposals(conversation_id) WHERE status='pending';
