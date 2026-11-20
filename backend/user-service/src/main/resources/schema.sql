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
alter table user_settings add column if not exists auto_archive boolean not null default true;
alter table user_settings add column if not exists local_files_access boolean not null default false;
alter table user_settings add column if not exists archive_target_height integer;

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

alter table invite_tokens add column if not exists kind text not null default 'REGISTRATION';
alter table invite_tokens add column if not exists target_username text;

alter table user_settings add column if not exists feedback_notifications boolean not null default true;

create table if not exists feedback_tickets (
    id uuid primary key,
    author_username text not null,
    subject varchar(160) not null,
    description varchar(8000) not null,
    type varchar(20) not null,
    priority varchar(20) not null,
    status varchar(20) not null,
    resolution varchar(20),
    duplicate_of uuid references feedback_tickets(id),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create table if not exists feedback_ticket_access (
    ticket_id uuid not null references feedback_tickets(id),
    username text not null,
    primary key (ticket_id, username)
);

create table if not exists feedback_ticket_entries (
    id uuid primary key,
    ticket_id uuid not null references feedback_tickets(id),
    author_username text,
    author_role varchar(20) not null,
    kind varchar(20) not null,
    text varchar(8000) not null,
    created_at timestamptz not null default now()
);

alter table feedback_ticket_entries add column if not exists client_request_id uuid;
create unique index if not exists feedback_ticket_entries_request on feedback_ticket_entries(client_request_id)
    where client_request_id is not null;

create index if not exists feedback_ticket_access_user on feedback_ticket_access(username, ticket_id);
create index if not exists feedback_ticket_entries_thread on feedback_ticket_entries(ticket_id, created_at, id);
create index if not exists feedback_tickets_updated on feedback_tickets(updated_at desc);
