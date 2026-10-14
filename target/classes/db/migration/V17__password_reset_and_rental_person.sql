CREATE TABLE password_reset_tokens (
 token_hash varchar(64) PRIMARY KEY,
 user_id uuid NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
 expires_at timestamptz NOT NULL,
 used_at timestamptz,
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX password_reset_user_idx ON password_reset_tokens(user_id);
ALTER TABLE rental_requests ADD COLUMN person_type varchar(20) NOT NULL DEFAULT 'business';
ALTER TABLE rental_requests ADD CONSTRAINT rental_person_type_check CHECK (person_type IN ('individual','business'));
