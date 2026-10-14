CREATE TABLE stored_files (
 id uuid PRIMARY KEY,
 owner_id uuid NOT NULL REFERENCES app_users(id),
 bucket text NOT NULL,
 object_key text NOT NULL,
 content_type text NOT NULL,
 size_bytes bigint NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(bucket,object_key)
);
