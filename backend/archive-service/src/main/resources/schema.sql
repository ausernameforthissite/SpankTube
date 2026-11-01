create table if not exists archive_jobs (
    id uuid primary key,
    owner_id text not null,
    owner_role text not null,
    kind text not null,
    target_id text not null,
    priority integer not null,
    status text not null,
    total_count integer not null,
    completed_count integer not null default 0,
    failed_count integer not null default 0,
    created_at timestamptz not null default now(),
    started_at timestamptz,
    finished_at timestamptz,
    next_attempt_at timestamptz not null default now(),
    error text,
    options_key text not null default 'null|null|true|true',
    stage text not null default 'QUEUED',
    current_video_id text,
    bytes_downloaded bigint not null default 0,
    total_bytes bigint not null default 0,
    speed_bps bigint not null default 0,
    download_url text,
    file_path text,
    file_name text,
    temporary_file boolean not null default false,
    title text,
    width integer,
    height integer,
    video_codec text,
    audio_codec text
);

alter table archive_jobs add column if not exists options_key text not null default 'null|null|true|true';
alter table archive_jobs add column if not exists stage text not null default 'QUEUED';
alter table archive_jobs add column if not exists current_video_id text;
alter table archive_jobs add column if not exists bytes_downloaded bigint not null default 0;
alter table archive_jobs add column if not exists total_bytes bigint not null default 0;
alter table archive_jobs add column if not exists speed_bps bigint not null default 0;
alter table archive_jobs add column if not exists download_url text;
alter table archive_jobs add column if not exists file_path text;
alter table archive_jobs add column if not exists file_name text;
alter table archive_jobs add column if not exists temporary_file boolean not null default false;
alter table archive_jobs add column if not exists title text;
alter table archive_jobs add column if not exists width integer;
alter table archive_jobs add column if not exists height integer;
alter table archive_jobs add column if not exists video_codec text;
alter table archive_jobs add column if not exists audio_codec text;
alter table archive_jobs add column if not exists audio_bitrate bigint;
alter table archive_jobs add column if not exists updated_at timestamptz not null default now();

create table if not exists archive_job_items (
    id bigserial primary key,
    job_id uuid not null references archive_jobs(id) on delete cascade,
    video_id text not null,
    status text not null,
    attempts integer not null default 0,
    next_attempt_at timestamptz not null default now(),
    error text,
    unique(job_id, video_id)
);

create table if not exists archive_job_dismissals (
    job_id uuid not null references archive_jobs(id) on delete cascade,
    username text not null,
    dismissed_at timestamptz not null default now(),
    primary key(job_id, username)
);

create index if not exists archive_jobs_order_idx on archive_jobs(status, priority, created_at);
create index if not exists archive_items_order_idx on archive_job_items(job_id, status, id);
create index if not exists archive_jobs_download_reuse_idx on archive_jobs(owner_id, kind, target_id, options_key, status);
create index if not exists archive_jobs_temporary_cleanup_idx on archive_jobs(temporary_file, finished_at);
