CREATE TABLE analytics_visits (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 session_id uuid NOT NULL,
 user_id uuid REFERENCES app_users(id),
 path text NOT NULL CHECK (length(path)<=300),
 source text NOT NULL CHECK (source IN ('Direto','Busca orgânica','Redes sociais','Email','Anúncios pagos','Referência')),
 device text NOT NULL CHECK (device IN ('Desktop','Mobile','Tablet')),
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX analytics_visits_date_idx ON analytics_visits(created_at);
CREATE INDEX analytics_visits_session_idx ON analytics_visits(session_id,created_at);
