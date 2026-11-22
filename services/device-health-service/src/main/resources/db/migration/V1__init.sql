create table device (
    device_id                 text primary key,
    firmware_version          text        not null,
    state                     text        not null,
    last_seen_at              timestamptz not null, -- server clock
    last_device_time          timestamptz not null, -- device clock
    last_uptime_seconds       bigint      not null,
    sd_total_bytes            bigint      not null,
    sd_free_bytes             bigint      not null,
    sd_read_only              boolean     not null,
    sd_write_errors           int         not null,
    network                   text        not null,
    signal_dbm                int         not null,
    reboot_window_started_at  timestamptz,
    reboots_in_window         int         not null default 0,
    updated_at                timestamptz not null
);

create index device_state_idx on device (state);
create index device_last_seen_idx on device (last_seen_at) where state <> 'OFFLINE';

create table device_health_issue (
    id           bigserial primary key,
    device_id    text        not null references device (device_id),
    type         text        not null,
    severity     text        not null,
    detail       text        not null,
    opened_at    timestamptz not null,
    resolved_at  timestamptz
);

-- At most one open issue of each type per device: makes "open issue" idempotent.
create unique index device_health_issue_one_open
    on device_health_issue (device_id, type) where resolved_at is null;
create index device_health_issue_device_idx on device_health_issue (device_id, opened_at desc);

-- Lifecycle audit trail: answers "when and why did this camera go DEGRADED?" for Support.
create table device_state_transition (
    id          bigserial primary key,
    device_id   text        not null references device (device_id),
    from_state  text,
    to_state    text        not null,
    reason      text        not null,
    at          timestamptz not null
);

create index device_state_transition_device_idx on device_state_transition (device_id, at desc);
