-- Synthetic export that mimics what we'd pull from Snowflake during the incident.
-- 3 days, 1,200 devices, 40 customers. Firmware 2.3.1 is rolled out OTA to ~25% of the
-- fleet on 2026-11-18 13:00 UTC; that firmware reports local time (UTC-3) as UTC.
-- Deterministic: run with threads=1 and a fixed seed.

select setseed(0.42);

create or replace table devices as
select
    printf('cam-%04d', i)                 as device_id,
    printf('cust-%02d', 1 + (i % 40))     as customer_id,
    random() < 0.25                       as in_rollout   -- received 2.3.1 OTA
from generate_series(1, 1200) t(i);

create or replace table firmware_rollout as
select timestamptz '2026-11-18 13:00:00+00' as rolled_out_at, '2.3.1' as version;

create or replace table _raw_events as
select
    i                                                                  as seq,
    printf('cam-%04d', 1 + cast(floor(random() * 1200) as int))        as device_id,
    timestamptz '2026-11-17 00:00:00+00'
        + to_microseconds(cast(random() * 3 * 86400 * 1e6 as bigint))  as real_time,
    -- most events leave the device within seconds; 0.4% wait in the offline buffer for 1-30 min
    case when random() < 0.004 then 60 + random() * 1740 else 0.5 + random() * 8 end as buffer_s,
    0.2 + random() * 1.8                                               as transit_s,
    0.1 + random() * 0.9                                               as processing_s,
    (['DROWSINESS','PHONE_USAGE','HARSH_BRAKING','FORWARD_COLLISION_WARNING','SMOKING'])[1 + cast(floor(random() * 5) as int)] as type,
    random()                                                           as dup_roll
from generate_series(1, 72000) t(i);

create or replace table safety_events as
with e as (
    select r.*, d.customer_id,
           d.in_rollout and r.real_time >= (select rolled_out_at from firmware_rollout) as skewed
    from _raw_events r join devices d using (device_id)
),
stamped as (
    select
        printf('msg-%06d', seq)                                                         as message_id,
        device_id, customer_id, type,
        case when skewed then '2.3.1' else '2.2.0' end                                  as firmware_version,
        -- device clock = real time, minus 3h on the buggy firmware
        real_time - case when skewed then interval 3 hour else interval 0 hour end     as device_time,
        real_time + to_microseconds(cast(buffer_s * 1e6 as bigint))
                  - case when skewed then interval 3 hour else interval 0 hour end     as device_sent_at,
        real_time + to_microseconds(cast((buffer_s + transit_s) * 1e6 as bigint))      as received_at,
        real_time + to_microseconds(cast((buffer_s + transit_s + processing_s) * 1e6 as bigint)) as processed_at,
        dup_roll
    from e
)
select * exclude (dup_roll) from stamped
union all
-- ~0.8% duplicates: device resent the batch after a lost ack, arrives 5-60s later
select message_id, device_id, customer_id, type, firmware_version, device_time, device_sent_at,
       received_at + interval 20 second, processed_at + interval 20 second
from stamped where dup_roll < 0.008;

drop table _raw_events;
