create table payment_sessions (
 id uuid primary key,
 buyer_id uuid not null,
 request_id uuid not null,
 amount numeric(18,2) not null check (amount > 0),
 collector_id text not null,
 sandbox boolean not null,
 preference_id text unique,
 checkout_url text,
 status text not null default 'creating',
 approved_payment_id text unique,
 created_at timestamptz not null default now(),
 updated_at timestamptz not null default now(),
 unique(buyer_id,request_id),
 foreign key(buyer_id,request_id) references checkout_requests(buyer_id,request_id)
);
create table payment_events (
 payment_id text primary key,
 session_id uuid not null references payment_sessions(id),
 status text not null,
 method text not null,
 amount numeric(18,2) not null,
 updated_at timestamptz not null default now()
);
create index orders_checkout_request on orders(buyer_id,(metadata->>'checkout_request_id'));
