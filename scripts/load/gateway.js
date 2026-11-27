// Load test for the ingestion gateway (gRPC, port 9090).
//
// Each virtual user is one camera: it sends a batch of heartbeats per iteration, with a fresh
// messageId and current timestamps every time, and an uptime that grows with real time, so the
// health service does not see fake reboots.
//
// Run (from the repo root, with the gateway up: `make run-ingestion`):
//   make load-test
//   make load-test VUS=200 DURATION=2m
//
// Checks: every batch accepted (no rejected items, no UNAVAILABLE).
// Thresholds: p95 < 300 ms and p99 < 1 s. A failing threshold makes k6 exit non-zero.

import grpc from 'k6/net/grpc';
import { check, sleep } from 'k6';

const client = new grpc.Client();
// Paths are relative to this script.
client.load(['../../contracts/src/main/proto'], 'fleetcam/telemetry/v1/telemetry.proto');

const GATEWAY = __ENV.GATEWAY || 'localhost:9090';
const VUS = Number(__ENV.VUS || 50);
const DURATION = __ENV.DURATION || '60s';
const BATCH_ITEMS = Number(__ENV.BATCH_ITEMS || 5);
const THINK_TIME_S = Number(__ENV.THINK_TIME_S || 1);

export const options = {
  scenarios: {
    cameras: { executor: 'constant-vus', vus: VUS, duration: DURATION },
  },
  thresholds: {
    grpc_req_duration: ['p(95)<300', 'p(99)<1000'],
    checks: ['rate>0.99'],
  },
};

// Per-VU state: k6 gives every virtual user its own JS runtime, so these are per camera.
const startedAt = Date.now();

function uuid() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
  });
}

function heartbeat(now, uptimeSeconds) {
  return {
    messageId: uuid(),
    deviceTime: now,
    heartbeat: {
      uptimeSeconds,
      sdCard: {
        totalBytes: 128000000000,
        freeBytes: 60000000000,
        writeErrorsSinceBoot: 0,
        readOnly: false,
      },
      connectivity: { network: 'NETWORK_TYPE_LTE', signalDbm: -80 },
      pendingUploads: 0,
    },
  };
}

export default function () {
  client.connect(GATEWAY, { plaintext: true });

  const now = new Date().toISOString();
  const uptime = 3600 + Math.floor((Date.now() - startedAt) / 1000);
  const items = [];
  for (let i = 0; i < BATCH_ITEMS; i++) {
    items.push(heartbeat(now, uptime));
  }

  const res = client.invoke('fleetcam.telemetry.v1.DeviceGateway/Publish', {
    deviceId: `load-cam-${__VU}`,
    firmwareVersion: '2.3.1',
    deviceSentAt: now,
    items,
  });

  check(res, {
    'gateway accepted the batch': (r) =>
      r && r.status === grpc.StatusOK && r.message.rejected === 0,
  });

  client.close();
  sleep(THINK_TIME_S);
}
