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
