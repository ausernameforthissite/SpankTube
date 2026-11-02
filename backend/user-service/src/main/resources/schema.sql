create table if not exists user_quotas (
    username text primary key,
    quota_bytes bigint not null
);

create table if not exists user_settings (
    username text primary key,
    autoplay boolean not null default true,
    hide_technical_details boolean not null default false
);

alter table user_settings add column if not exists prefer_best_quality boolean not null default true;
alter table user_settings add column if not exists theme text not null default 'system';
alter table user_settings add column if not exists avatar bytea;
alter table user_settings add column if not exists avatar_content_type text;

create table if not exists local_users (
    username text primary key,
    password_hash text not null,
    role text not null
);

create table if not exists invite_tokens (
    code text primary key,
    created_by text not null,
    created_at timestamptz not null default now(),
    expires_at timestamptz not null,
    used_by text,
    used_at timestamptz
);
