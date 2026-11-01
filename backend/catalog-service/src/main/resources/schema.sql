create table if not exists channels (
    channel_id text primary key,
    title text not null,
    handle text,
    custom_path text,
    avatar_url text,
    subscriber_count bigint
);

create table if not exists videos (
    video_id text primary key,
    channel_id text not null,
    title text not null,
    published_at text,
    duration_sec bigint,
    archived_path text not null,
    quality text,
    size_bytes bigint not null,
    archived_at timestamptz not null default now()
);

create table if not exists user_views (
    user_id text not null,
    video_id text not null,
    first_watched_at timestamptz not null default now(),
    last_accessed_at timestamptz not null default now(),
    last_position_sec bigint not null default 0,
    watch_percent double precision not null default 0,
    created_at timestamptz not null default now(),
    primary key (user_id, video_id)
);

alter table videos add column if not exists width integer;
alter table videos add column if not exists height integer;
alter table videos add column if not exists video_codec text;
alter table videos add column if not exists audio_codec text;

create index if not exists user_views_lru_idx on user_views(user_id, last_accessed_at);
create index if not exists user_views_video_idx on user_views(video_id);
