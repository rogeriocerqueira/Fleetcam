create table safety_event (
    message_id              text primary key,            -- idempotency key generated on the device
    device_id               text             not null,
    firmware_version        text             not null,
    type                    text             not null,
    confidence              double precision not null,
    latitude                double precision not null,
    longitude               double precision not null,
    speed_kmh               double precision not null,
    clip_id                 text,
    device_time             timestamptz      not null,   -- raw, device clock
    device_sent_at          timestamptz      not null,   -- raw, device clock
    received_at             timestamptz      not null,   -- server clock
    processed_at            timestamptz      not null,   -- server clock
    event_time              timestamptz      not null,   -- what customers see (corrected if clock skew detected)
    clock_offset_applied_ms bigint           not null default 0
);

create index safety_event_device_time_idx on safety_event (device_id, event_time desc);
create index safety_event_received_idx on safety_event (received_at);
