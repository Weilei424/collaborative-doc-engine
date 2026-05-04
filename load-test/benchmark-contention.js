import http from 'k6/http';
import ws from 'k6/ws';
import { fail, sleep } from 'k6';
import { Trend, Counter, Rate } from 'k6/metrics';
import { randomBytes } from 'k6/crypto';

// ---------- Configuration ----------
const BASE_URL    = __ENV.BASE_URL    || 'http://localhost:8080';
const WS_BASE_URL = __ENV.WS_BASE_URL || 'ws://localhost:8080';
const STEADY_VUS  = parseInt(__ENV.K6_STEADY_VUS || '30');
const STRESS_VUS  = parseInt(__ENV.K6_STRESS_VUS || '100');

const OPS_PER_ITERATION  = 10;
const THINK_TIME_MS      = 100;   // tighter than baseline to maximise lock contention
const SESSION_TIMEOUT_MS = 60000; // 60 s — lock wait can spike under load
const SUB_READY_DELAY_MS = 200;

// ---------- Custom metrics ----------
const operationLatency   = new Trend('operation_latency', true);
const operationsAccepted = new Counter('operations_accepted');
const operationErrorRate = new Rate('operation_error_rate');

// ---------- Options ----------
export const options = {
  stages: [
    { duration: '30s', target: STEADY_VUS },   // ramp up
    { duration: '60s', target: STEADY_VUS },   // steady at STEADY_VUS concurrent editors
    { duration: '30s', target: STRESS_VUS },   // ramp to peak
    { duration: '60s', target: STRESS_VUS },   // peak concurrent editors
    { duration: '10s', target: 0 },
  ],
  thresholds: {
    // Contention adds lock wait — relax p95 to 2 s
    'operation_latency':   ['p(95)<2000'],
    'operation_error_rate': ['rate<0.05'],
  },
};

// ---------- Helpers (identical to benchmark.js) ----------

function uuidv4() {
  const b = new Uint8Array(randomBytes(16));
  b[6] = (b[6] & 0x0f) | 0x40;
  b[8] = (b[8] & 0x3f) | 0x80;
  const hex = [...b].map(x => x.toString(16).padStart(2, '0')).join('');
  return hex.slice(0, 8) + '-' + hex.slice(8, 12) + '-' +
         hex.slice(12, 16) + '-' + hex.slice(16, 20) + '-' + hex.slice(20);
}

function randomAlphanumeric(len) {
  const chars = 'abcdefghijklmnopqrstuvwxyz0123456789';
  return [...new Uint8Array(randomBytes(len))]
    .map(x => chars[x % chars.length])
    .join('');
}

function encodeStompFrame(command, headers, body) {
  body = body || '';
  let frame = command + '\n';
  for (const key of Object.keys(headers)) {
    frame += key + ':' + headers[key] + '\n';
  }
  return frame + '\n' + body + '\0';
}

function sockjsSend(socket, stompFrame) {
  socket.send(JSON.stringify([stompFrame]));
}

// ---------- Setup: one shared user + one shared document ----------
export function setup() {
  const username = 'contend' + randomAlphanumeric(10);
  const regRes = http.post(
    BASE_URL + '/api/auth/register',
    JSON.stringify({
      username: username,
      email: username + '@bench.test',
      password: 'password123',
    }),
    { headers: { 'Content-Type': 'application/json' } }
  );
  if (regRes.status !== 201) {
    fail('User registration failed: HTTP ' + regRes.status + ' ' + regRes.body);
  }
  const jwt = regRes.json('token');

  const docRes = http.post(
    BASE_URL + '/api/documents',
    JSON.stringify({
      title: 'contention-bench',
      content: '{"children":[{"type":"paragraph","text":"","children":[]}]}',
      visibility: 'PRIVATE',
    }),
    { headers: {
      'Content-Type': 'application/json',
      'Authorization': 'Bearer ' + jwt,
    }}
  );
  if (docRes.status !== 201) {
    fail('Shared document creation failed: HTTP ' + docRes.status + ' ' + docRes.body);
  }

  return { jwt: jwt, docId: docRes.json('id') };
}

// ---------- Helper: submit next INSERT_TEXT ----------
function submitNext(socket, docId, state) {
  state.pendingOpId = uuidv4();
  state.t0 = Date.now();
  sockjsSend(socket, encodeStompFrame(
    'SEND',
    {
      'destination': '/app/documents/' + docId + '/operations.submit',
      'content-type': 'application/json',
    },
    JSON.stringify({
      operationId: state.pendingOpId,
      baseVersion: state.baseVersion,
      operationType: 'INSERT_TEXT',
      // Always insert at offset 0. OT transforms concurrent ops at the same
      // offset, so all ops are applied correctly regardless of ordering.
      payload: { path: [0], offset: 0, text: 'x' },
    })
  ));
}

// ---------- VU default function ----------
export default function(data) {
  const { jwt, docId } = data;

  const wsUrl = WS_BASE_URL + '/ws/100/' + randomAlphanumeric(20) + '/websocket?token=' + jwt;

  const state = {
    phase: 'handshake',   // 'handshake' | 'subscribed' | 'operating' | 'done'
    opCount: 0,
    baseVersion: 0,
    t0: 0,
    pendingOpId: null,    // operationId of the in-flight op; null when idle
  };

  ws.connect(wsUrl, {}, function(socket) {

    socket.on('open', function() {
      sockjsSend(socket, encodeStompFrame('CONNECT', {
        'accept-version': '1.2',
        'heart-beat': '0,0',
      }, ''));
    });

    socket.on('message', function(msg) {
      if (state.phase === 'handshake' && msg.includes('CONNECTED')) {
        sockjsSend(socket, encodeStompFrame('SUBSCRIBE', {
          'id': 'sub-0',
          'destination': '/topic/documents/' + docId + '/operations',
        }, ''));
        state.phase = 'subscribed';
        socket.setTimeout(function() {
          state.phase = 'operating';
          submitNext(socket, docId, state);
        }, SUB_READY_DELAY_MS);
        return;
      }

      if (state.phase !== 'operating') return;

      // STOMP ERROR frame (e.g. OperationConflictException after max CAS retries).
      // Spring keeps the WebSocket open after sending ERROR, so we must handle it
      // explicitly — otherwise the VU hangs until the 60 s session timeout fires.
      if (msg.includes('"ERROR') || msg.includes('\nERROR\n')) {
        if (state.pendingOpId) {
          operationErrorRate.add(1);
          state.pendingOpId = null;
          state.opCount++;
        }
        if (state.opCount >= OPS_PER_ITERATION) {
          state.phase = 'done';
          sockjsSend(socket, encodeStompFrame('DISCONNECT', {}, ''));
          socket.close();
        } else {
          socket.setTimeout(function() { submitNext(socket, docId, state); }, THINK_TIME_MS);
        }
        return;
      }

      if (!msg.includes('serverVersion')) return;

      // Keep baseVersion current from ANY accepted op on this document.
      // Every VU subscribed to the same topic receives every broadcast —
      // advancing baseVersion here ensures our next submission always
      // reflects the true document version and avoids unnecessary OT work.
      const svMatch = msg.match(/"serverVersion":(\d+)/);
      if (svMatch) {
        const sv = parseInt(svMatch[1]);
        if (sv > state.baseVersion) {
          state.baseVersion = sv;
        }
      }

      // Only record latency and advance the op counter when OUR op is confirmed.
      // AcceptedOperationResponse.operationId is echoed back in the broadcast body.
      if (!state.pendingOpId || !msg.includes(state.pendingOpId)) return;

      const latency = Date.now() - state.t0;
      operationLatency.add(latency);
      operationsAccepted.add(1);
      operationErrorRate.add(0);
      state.opCount++;
      state.pendingOpId = null;

      if (state.opCount >= OPS_PER_ITERATION) {
        state.phase = 'done';
        sockjsSend(socket, encodeStompFrame('DISCONNECT', {}, ''));
        socket.close();
        return;
      }

      socket.setTimeout(function() {
        submitNext(socket, docId, state);
      }, THINK_TIME_MS);
    });

    socket.on('error', function() {
      const remaining = OPS_PER_ITERATION - state.opCount;
      for (let i = 0; i < remaining; i++) {
        operationErrorRate.add(1);
      }
      socket.close();
    });

    socket.setTimeout(function() {
      if (state.phase !== 'done') {
        const remaining = OPS_PER_ITERATION - state.opCount;
        for (let i = 0; i < remaining; i++) {
          operationErrorRate.add(1);
        }
        socket.close();
      }
    }, SESSION_TIMEOUT_MS);
  });
}
