-- Investigation queries for docs/postmortems/001-firmware-clock-skew.md
-- Written for DuckDB; they are plain ANSI-ish SQL and port to Snowflake with minor changes
-- (date_trunc / epoch / quantile_cont -> percentile_cont ... within group).
-- Each block starts with "-- name: <id>" so investigate.py can run them one by one.

-- name: q1_hourly_slo_naive
-- What the alert saw: % of events "within 30s" using the OLD latency formula
-- (processed_at - device_time), which subtracts a device timestamp from a server timestamp.
select
    date_trunc('hour', received_at)                                                        as hour_utc,
    count(*)                                                                               as events,
    round(avg(case when epoch(processed_at - device_time) <= 30 then 1 else 0 end) * 100, 2) as pct_within_30s_naive
from safety_events
where received_at between timestamptz '2026-11-18 10:00:00+00' and timestamptz '2026-11-18 17:00:00+00'
group by 1
order by 1;

-- name: q2_slo_by_firmware
-- Hypothesis 1 "the backend got slow" predicts every firmware is affected equally. It isn't.
select
    firmware_version,
    count(*)                                                                                 as events,
    round(avg(case when epoch(processed_at - device_time) <= 30 then 1 else 0 end) * 100, 2) as pct_within_30s_naive,
    round(quantile_cont(epoch(processed_at - device_time), 0.5) / 60, 1)                     as p50_naive_latency_min
from safety_events
where received_at >= (select rolled_out_at from firmware_rollout)
group by 1
order by 1;

-- name: q3_decompose_latency
-- Hypothesis 2 "2.3.1 devices are buffering (connectivity)" predicts large buffer delay.
-- Split latency into same-clock components: buffer (device-device), server (server-server),
-- and the cross-clock offset that should be ~transit time (~1s).
select
    firmware_version,
    round(quantile_cont(epoch(device_sent_at - device_time), 0.5), 1)   as p50_buffer_s,
    round(quantile_cont(epoch(processed_at - received_at), 0.5), 2)     as p50_server_s,
    round(quantile_cont(epoch(received_at - device_sent_at), 0.5), 1)   as p50_clock_offset_s
from safety_events
where received_at >= (select rolled_out_at from firmware_rollout)
group by 1
order by 1;

-- name: q4_offset_histogram
-- A network problem gives a smeared distribution; a clock bug gives a spike at a round value.
select
    round(epoch(received_at - device_sent_at) / 60)  as offset_min,
    count(*)                                         as events
from safety_events
where firmware_version = '2.3.1'
group by 1
order by events desc
limit 5;

-- name: q5_slo_robust_formula
-- After the fix: latency = buffer (device clock) + server delay (server clock). Same data.
select
    firmware_version,
    round(avg(case when epoch(device_sent_at - device_time) + epoch(processed_at - received_at) <= 30
                   then 1 else 0 end) * 100, 2) as pct_within_30s_robust
from safety_events
group by 1
order by 1;

-- name: q6_customer_impact
-- Real customer impact: events displayed with a timestamp more than 5 min off.
select
    count(*)                       as events_with_wrong_time,
    count(distinct device_id)      as devices,
    count(distinct customer_id)    as customers,
    min(received_at)               as first_seen,
    max(received_at)               as last_seen
from safety_events
where abs(epoch(received_at - device_sent_at)) > 300;

-- name: q7_duplicates
-- Duplicates from lost acks: must be absorbed by idempotency on message_id.
select
    count(*)                                         as rows_received,
    count(distinct message_id)                       as unique_events,
    round((1 - count(distinct message_id) / count(*)) * 100, 2) as pct_duplicates
from safety_events;
