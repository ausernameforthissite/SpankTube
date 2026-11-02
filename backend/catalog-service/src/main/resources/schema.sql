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
alter table videos add column if not exists description text;
alter table videos add column if not exists audio_bitrate bigint;

create table if not exists video_versions (
    id uuid primary key,
    video_id text not null,
    channel_id text not null,
    title text not null,
    description text,
    published_at text,
    duration_sec bigint,
    archived_path text not null unique,
    quality text,
    size_bytes bigint not null,
    width integer,
    height integer,
    video_codec text,
    audio_codec text,
    created_at timestamptz not null default now(),
    superseded_at timestamptz
);

alter table video_versions add column if not exists deleted_at timestamptz;
alter table video_versions add column if not exists audio_bitrate bigint;

insert into video_versions(id, video_id, channel_id, title, description, published_at, duration_sec,
    archived_path, quality, size_bytes, width, height, video_codec, audio_codec, audio_bitrate)
select gen_random_uuid(), video_id, channel_id, title, description, published_at, duration_sec,
    archived_path, quality, size_bytes, width, height, video_codec, audio_codec, audio_bitrate from videos
on conflict(archived_path) do nothing;

create index if not exists video_versions_history_idx on video_versions(video_id, created_at desc);
create index if not exists video_versions_current_idx on video_versions(video_id, superseded_at);

create index if not exists user_views_lru_idx on user_views(user_id, last_accessed_at);
create index if not exists user_views_video_idx on user_views(video_id);

create table if not exists library_playlists (
    user_id text not null,
    playlist_id text not null,
    channel_id text not null,
    title text not null,
    created_at timestamptz not null default now(),
    primary key (user_id, playlist_id)
);

create table if not exists library_playlist_items (
    user_id text not null,
    playlist_id text not null,
    video_id text not null,
    primary key (user_id, playlist_id, video_id)
);

create index if not exists library_playlists_channel_idx on library_playlists(user_id, channel_id);
create index if not exists library_playlist_items_video_idx on library_playlist_items(user_id, video_id);
