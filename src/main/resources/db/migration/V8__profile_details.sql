CREATE TABLE profile_details (
 user_id uuid PRIMARY KEY REFERENCES app_users(id) ON DELETE CASCADE,
 details jsonb NOT NULL,
 completed_at timestamptz NOT NULL DEFAULT now(),
 updated_at timestamptz NOT NULL DEFAULT now()
);
