CREATE TABLE professional_reviews (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 provider_user_id uuid NOT NULL REFERENCES app_users(id),
 reviewer_user_id uuid NOT NULL REFERENCES app_users(id),
 rating integer NOT NULL CHECK (rating BETWEEN 1 AND 5),
 comment text NOT NULL DEFAULT '' CHECK (length(comment) <= 5000),
 created_at timestamptz NOT NULL DEFAULT now(),
 CHECK (provider_user_id <> reviewer_user_id)
);
CREATE INDEX professional_reviews_provider_idx ON professional_reviews(provider_user_id, created_at DESC);
