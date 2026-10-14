create table seller_mp_accounts (
 seller_id uuid primary key references app_users(id),
 collector_id text not null unique,
 access_token_enc text not null,
 refresh_token_enc text not null,
 expires_at timestamptz not null,
 sandbox boolean not null,
 receiving_enabled boolean not null default true,
 updated_at timestamptz not null default now()
);
create table seller_mp_oauth_states (
 state_hash text primary key,
 seller_id uuid not null references app_users(id),
 token_version integer not null,
 verifier_enc text not null,
 expires_at timestamptz not null,
 used_at timestamptz
);
alter table payment_sessions add column seller_id uuid references app_users(id);
alter table payment_sessions drop constraint payment_sessions_buyer_id_request_id_key;
create unique index payment_sessions_per_seller on payment_sessions(buyer_id,request_id,seller_id) where seller_id is not null;
create unique index payment_sessions_legacy on payment_sessions(buyer_id,request_id) where seller_id is null;
