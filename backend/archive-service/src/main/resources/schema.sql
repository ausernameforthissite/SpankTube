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
    error text
);

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

create index if not exists archive_jobs_order_idx on archive_jobs(status, priority, created_at);
create index if not exists archive_items_order_idx on archive_job_items(job_id, status, id);
