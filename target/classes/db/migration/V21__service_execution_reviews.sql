ALTER TABLE service_contracts
 ADD COLUMN execution_status text NOT NULL DEFAULT 'scheduled' CHECK(execution_status IN ('scheduled','in_progress','awaiting_confirmation','completed')),
 ADD COLUMN started_at timestamptz,
 ADD COLUMN reported_completed_at timestamptz,
 ADD COLUMN delivery_confirmed_at timestamptz;
CREATE TABLE service_execution_events (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 contract_id uuid NOT NULL REFERENCES service_contracts(id),
 actor_id uuid NOT NULL REFERENCES app_users(id),
 event_type text NOT NULL CHECK(event_type IN ('start','progress','complete','confirm_delivery','request_adjustments','review')),
 note text NOT NULL DEFAULT '' CHECK(length(note)<=5000),
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX service_execution_events_contract_idx ON service_execution_events(contract_id,created_at,id);
ALTER TABLE professional_reviews ADD COLUMN contract_id uuid UNIQUE REFERENCES service_contracts(id);
