create table if not exists user_quotas (
    username text primary key,
    quota_bytes bigint not null
);
