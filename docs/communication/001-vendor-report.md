# Bug report — Firmware 2.3.1 reports local time as UTC

**To:** Camera firmware vendor — engineering support
**Severity:** High (customer-visible data error on ~25% of the fleet)
**Affected version:** 2.3.1 · **Not affected:** 2.2.0

## Summary
Since firmware 2.3.1, every timestamp produced by the device (`device_time` on events and
`device_sent_at` on batches) is **exactly 3 hours behind UTC**. Our devices are configured with the
`America/Sao_Paulo` time zone (UTC-3), so it looks like the firmware formats the local wall-clock time
and labels it as UTC (`Z` suffix).

## Evidence
- 9,171 events from 310 devices on 2.3.1. The offset between the device clock and our server clock is
  **180 minutes for 100% of them**. On 2.2.0 the median offset is 1.1 s (network transit).
- The offset is constant: it doesn't drift over time and doesn't depend on signal or connectivity.
  NTP sync status on the affected devices reports OK.
- Reproduced on a bench unit: NTP-synced device, real time `13:00:00Z`, payload `device_time = 10:00:00Z`.

## Steps to reproduce
1. Flash 2.3.1 on a unit with time zone set to `America/Sao_Paulo`.
2. Let it sync via NTP and trigger any event (or wait for a heartbeat).
3. Compare the payload `device_time` with the real UTC time: it's 3h behind.
4. (Expected) Changing the time zone to UTC should make the offset disappear. Please confirm.

## Expected behavior
All timestamps in the telemetry protocol are UTC, independent of the configured display time zone.

## Questions
1. Which change between 2.2.0 and 2.3.1 touched RTC or time formatting?
2. Does the bug also affect timestamps stored in video clip metadata on the SD card?
3. When can we expect a fixed build? We can validate it on our bench within 24h of receiving it.

## Our mitigation in the meantime
We now estimate each device's clock offset server-side and correct displayed times when the offset
exceeds 5 minutes, so there's no customer impact while we wait for the fix. We'd still like the
firmware fix so we can remove this workaround.
